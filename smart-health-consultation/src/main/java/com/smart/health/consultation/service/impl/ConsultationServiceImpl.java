package com.smart.health.consultation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.health.common.exception.BusinessException;
import com.smart.health.common.constant.CommonConstants;
import com.smart.health.consultation.dto.ConsultStreamRequest;
import com.smart.health.consultation.dto.ConsultStreamResponse;
import com.smart.health.consultation.dto.SessionHistoryVO;
import com.smart.health.consultation.dto.SessionVO;
import com.smart.health.consultation.entity.ConsultationMessage;
import com.smart.health.consultation.entity.ConsultationSession;
import com.smart.health.consultation.mapper.ConsultationMessageMapper;
import com.smart.health.consultation.mapper.ConsultationSessionMapper;
import com.smart.health.consultation.service.ConsultationService;
import com.smart.health.consultation.service.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 问诊服务实现
 * 核心链路：RAG 检索 → 组装 Prompt（系统角色 + 知识上下文 + 多轮历史 + 当前问题）→ 流式调用 LLM → SSE 推送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultationServiceImpl implements ConsultationService {

    private final ConsultationSessionMapper sessionMapper;
    private final ConsultationMessageMapper messageMapper;
    private final RagRetrievalService ragRetrievalService;
    private final OpenAiChatClient chatClient;
    private final ObjectMapper objectMapper;

    /** SSE 超时时间：5 分钟 */
    private static final long SSE_TIMEOUT = 5 * 60 * 1000L;

    @Override
    public SseEmitter streamConsult(ConsultStreamRequest request, Long patientId) {
        // 1. 参数校验
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BusinessException("消息内容不能为空");
        }

        // 2. 查询或自动创建会话
        ConsultationSession session;
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            // 自动创建新会话
            String sessionSn = CommonConstants.SESSION_SN_PREFIX + System.currentTimeMillis() + "_" +
                    UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            session = new ConsultationSession();
            session.setSessionSn(sessionSn);
            session.setPatientId(patientId);
            session.setDraftId(request.getDraftId());
            session.setSymptomDraft(null);
            session.setChatLog(null);
            sessionMapper.insert(session);
            log.info("自动创建问诊会话, sessionSn={}, patientId={}", sessionSn, patientId);
        } else {
            session = sessionMapper.selectBySessionSn(request.getSessionId());
            if (session == null) {
                throw new BusinessException("问诊会话不存在: " + request.getSessionId());
            }
            if (!session.getPatientId().equals(patientId)) {
                throw new BusinessException("无权访问该问诊会话");
            }
        }

        // 3. 加载多轮对话历史
        List<Map<String, String>> chatHistory = parseChatLog(session.getChatLog());

        // 4. RAG 检索：从 ES 知识库检索相关医学文献（用于 Prompt 上下文）
        String ragContext = ragRetrievalService.retrieveAsContext(request.getMessage(), 3);

        // 4b. RAG 检索：获取引用来源（用于 SSE 响应中的 citations）
        List<ConsultStreamResponse.Citation> citations =
                ragRetrievalService.retrieveCitations(request.getMessage(), 3);

        // 5. 构建 Prompt
        List<Message> messages = buildPromptMessages(ragContext, chatHistory, request.getMessage(),
                session.getSymptomDraft());

        // 6. 创建 SseEmitter 并发起流式调用
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        StringBuilder fullResponse = new StringBuilder();
        AtomicReference<String> sessionSnRef = new AtomicReference<>(session.getSessionSn());

        Disposable disposable = chatClient.stream(new Prompt(messages))
                .subscribe(
                        chatResponse -> {
                            try {
                                String content = extractContent(chatResponse);
                                if (content != null && !content.isEmpty()) {
                                    fullResponse.append(content);
                                    ConsultStreamResponse resp = ConsultStreamResponse.builder()
                                            .content(content)
                                            .build();
                                    emitter.send(SseEmitter.event()
                                            .data(objectMapper.writeValueAsString(resp)));
                                }
                            } catch (IOException e) {
                                log.error("SSE 发送失败", e);
                            }
                        },
                        error -> {
                            log.error("AI 流式调用异常, sessionSn={}", sessionSnRef.get(), error);
                            try {
                                // 发送 error 事件（携带结构化错误信息）
                                ConsultStreamResponse errResp = ConsultStreamResponse.builder()
                                        .error("AI 服务暂时不可用，请稍后重试。")
                                        .build();
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(objectMapper.writeValueAsString(errResp)));
                                emitter.complete();
                            } catch (IOException ignored) {
                            }
                        },
                        () -> {
                            try {
                                // 发送 citations 事件（携带引用来源）
                                if (citations != null && !citations.isEmpty()) {
                                    ConsultStreamResponse citResp = ConsultStreamResponse.builder()
                                            .content("")
                                            .citations(citations)
                                            .build();
                                    emitter.send(SseEmitter.event()
                                            .data(objectMapper.writeValueAsString(citResp)));
                                }

                                // 发送结束标记
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();

                                // 保存对话记录到会话（含 citations 持久化）
                                saveChatTurn(session, chatHistory, request.getMessage(),
                                        fullResponse.toString(), citations);
                                log.info("问诊流式响应完成, sessionSn={}, 本轮回答长度={}",
                                        sessionSnRef.get(), fullResponse.length());
                            } catch (IOException e) {
                                log.error("SSE 完成发送失败", e);
                            }
                        }
                );

        // 超时/错误/完成时释放订阅
        emitter.onTimeout(() -> {
            log.warn("SSE 超时, sessionSn={}", sessionSnRef.get());
            disposable.dispose();
        });
        emitter.onError(ex -> disposable.dispose());
        emitter.onCompletion(disposable::dispose);

        return emitter;
    }

    @Override
    public String createSession(Long patientId, String draftId, String symptomDraft) {
        String sessionSn = CommonConstants.SESSION_SN_PREFIX + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ConsultationSession session = new ConsultationSession();
        session.setSessionSn(sessionSn);
        session.setPatientId(patientId);
        session.setDraftId(draftId);
        session.setSymptomDraft(symptomDraft);
        session.setChatLog(null);
        sessionMapper.insert(session);
        log.info("创建问诊会话, sessionSn={}, patientId={}", sessionSn, patientId);
        return sessionSn;
    }

    @Override
    public List<SessionVO> listSessions(Long patientId) {
        List<ConsultationSession> sessions = sessionMapper.selectByPatientId(patientId);
        List<SessionVO> result = new ArrayList<>();
        for (ConsultationSession s : sessions) {
            int turnCount = parseChatLog(s.getChatLog()).size() / 2;
            String summary = (s.getSymptomDraft() != null && s.getSymptomDraft().length() > 100)
                    ? s.getSymptomDraft().substring(0, 100) + "..."
                    : s.getSymptomDraft();
            result.add(SessionVO.builder()
                    .id(s.getId())
                    .sessionSn(s.getSessionSn())
                    .symptomDraftSummary(summary)
                    .turnCount(turnCount)
                    .createTime(s.getCreateTime())
                    .build());
        }
        return result;
    }

    @Override
    public List<SessionHistoryVO> getSessionHistory(String sessionSn, Long patientId) {
        ConsultationSession session = sessionMapper.selectBySessionSn(sessionSn);
        if (session == null) {
            throw new BusinessException("问诊会话不存在: " + sessionSn);
        }
        if (!session.getPatientId().equals(patientId)) {
            throw new BusinessException("无权访问该会话");
        }
        List<Map<String, String>> chatLog = parseChatLog(session.getChatLog());
        List<SessionHistoryVO> persisted = messageMapper.selectHistoryBySessionId(session.getId());
        if (!persisted.isEmpty()) {
            return persisted;
        }
        List<SessionHistoryVO> history = new ArrayList<>();
        for (Map<String, String> turn : chatLog) {
            history.add(SessionHistoryVO.builder()
                    .role(turn.get("role"))
                    .content(turn.get("content"))
                    .timestamp(turn.get("timestamp"))
                    .build());
        }
        return history;
    }

    /**
     * 构建 Prompt 消息列表
     * 结构：SystemMessage(角色 + RAG上下文 + 症状草稿) → 多轮历史 → 当前用户消息
     */
    private List<Message> buildPromptMessages(String ragContext, List<Map<String, String>> chatHistory,
                                               String currentMessage, String symptomDraft) {
        List<Message> messages = new ArrayList<>();

        // System Message: 角色设定 + RAG 知识上下文 + 症状草稿
        StringBuilder systemContent = new StringBuilder();
        systemContent.append("你是一位经验丰富的全科AI医生助手，基于提供的医学知识和患者的症状信息，");
        systemContent.append("为患者提供专业、准确、易懂的医疗健康咨询服务。\n");
        systemContent.append("重要规则：\n");
        systemContent.append("1. 回答必须基于下方提供的【医学知识库】内容，不要编造不存在的医学事实。\n");
        systemContent.append("2. 在回答正文中使用 [1]、[2] 等编号标注引用来源，编号对应【医学知识库参考】中的【知识1】、【知识2】。\n");
        systemContent.append("3. 始终提醒患者：AI建议仅供参考，具体诊疗请遵医嘱。\n");
        systemContent.append("4. 语言要通俗易懂，避免过多专业术语。\n\n");

        if (symptomDraft != null && !symptomDraft.isBlank()) {
            systemContent.append("【患者症状自查草稿】\n").append(symptomDraft).append("\n\n");
        }

        if (ragContext != null && !ragContext.isBlank()) {
            systemContent.append("【医学知识库参考】\n").append(ragContext).append("\n\n");
        }

        systemContent.append("请基于以上信息回答患者的问题。");
        messages.add(new SystemMessage(systemContent.toString()));

        // 多轮对话历史（最近 10 轮）
        int historySize = chatHistory.size();
        int startIdx = Math.max(0, historySize - 20); // 保留最近 20 条消息（10 轮对话）
        for (int i = startIdx; i < historySize; i++) {
            Map<String, String> turn = chatHistory.get(i);
            String role = turn.get("role");
            String content = turn.get("content");
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }

        // 当前用户消息
        messages.add(new UserMessage(currentMessage));

        return messages;
    }

    /**
     * 从 ChatResponse 中提取文本内容
     */
    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null) return null;
        var result = chatResponse.getResult();
        if (result != null && result.getOutput() != null) {
            return result.getOutput().getContent();
        }
        return null;
    }

    /**
     * 解析 chatLog JSON 为对话历史列表
     */
    private List<Map<String, String>> parseChatLog(String chatLog) {
        if (chatLog == null || chatLog.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(chatLog, new TypeReference<List<Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            log.error("解析 chatLog 失败，将重置为空: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 保存一轮对话（用户消息 + AI 回复）到会话的 chatLog
     */
    private void saveChatTurn(ConsultationSession session, List<Map<String, String>> existingHistory,
                               String userMessage, String assistantResponse,
                               List<ConsultStreamResponse.Citation> citations) {
        Map<String, String> userTurn = new LinkedHashMap<>();
        userTurn.put("role", "user");
        userTurn.put("content", userMessage);
        userTurn.put("timestamp", LocalDateTime.now().toString());
        existingHistory.add(userTurn);

        Map<String, String> assistantTurn = new LinkedHashMap<>();
        assistantTurn.put("role", "assistant");
        assistantTurn.put("content", assistantResponse);
        assistantTurn.put("timestamp", LocalDateTime.now().toString());
        existingHistory.add(assistantTurn);

        try {
            String updatedChatLog = objectMapper.writeValueAsString(existingHistory);
            sessionMapper.updateChatLog(session.getId(), updatedChatLog);
        } catch (JsonProcessingException e) {
            log.error("序列化 chatLog 失败", e);
        }

        ConsultationMessage userMsg = new ConsultationMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        messageMapper.insert(userMsg);

        ConsultationMessage assistantMsg = new ConsultationMessage();
        assistantMsg.setSessionId(session.getId());
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(assistantResponse);
        assistantMsg.setCitations(citations == null || citations.isEmpty() ? null : citations);
        messageMapper.insert(assistantMsg);

        log.debug("对话记录已保存, sessionSn={}, 总轮数={}", session.getSessionSn(), existingHistory.size() / 2);
    }
}

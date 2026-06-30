package com.smart.health.consultation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.health.consultation.dto.ConsultStreamRequest;
import com.smart.health.consultation.dto.ConsultStreamResponse;
import com.smart.health.consultation.dto.SessionHistoryVO;
import com.smart.health.consultation.dto.SessionVO;
import com.smart.health.consultation.service.ConsultationService;
import com.smart.health.consultation.service.MultimodalService;
import com.smart.health.consultation.service.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AI问诊控制器单元测试
 * 使用 standalone MockMvc 验证控制器路由和参数传递行为
 * 注：@AuthenticationPrincipal 在 standalone 模式下为 null，patientId 默认为 0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI问诊控制器 - Issue #6 验收测试")
class AiConsultControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AiConsultController controller;

    @Mock
    private ConsultationService consultationService;

    @Mock
    private MultimodalService multimodalService;

    @Mock
    private RagRetrievalService ragRetrievalService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("会话管理接口")
    class SessionManagement {

        @Test
        @DisplayName("POST /api/v1/ai/sessions 创建问诊会话，返回会话编号")
        void createSession_validRequest_returnsSessionSn() throws Exception {
            when(consultationService.createSession(eq(0L), any(), any()))
                    .thenReturn("session_1234567890_ABCDEF");

            mockMvc.perform(post("/api/v1/ai/sessions")
                            .param("draftId", "draft_001")
                            .param("symptomDraft", "患者主诉头痛三天"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("session_1234567890_ABCDEF"));

            verify(consultationService).createSession(eq(0L), any(), any());
        }

        @Test
        @DisplayName("GET /api/v1/ai/sessions 返回患者问诊会话列表")
        void listSessions_returnsSessionList() throws Exception {
            var sessions = List.of(
                    SessionVO.builder()
                            .id(1L)
                            .sessionSn("session_001")
                            .symptomDraftSummary("头痛")
                            .turnCount(3)
                            .createTime(LocalDateTime.of(2026, 6, 28, 10, 0))
                            .build(),
                    SessionVO.builder()
                            .id(2L)
                            .sessionSn("session_002")
                            .symptomDraftSummary("咳嗽")
                            .turnCount(1)
                            .createTime(LocalDateTime.of(2026, 6, 29, 14, 0))
                            .build()
            );
            when(consultationService.listSessions(0L)).thenReturn(sessions);

            mockMvc.perform(get("/api/v1/ai/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].sessionSn").value("session_001"))
                    .andExpect(jsonPath("$.data[0].turnCount").value(3))
                    .andExpect(jsonPath("$.data[1].sessionSn").value("session_002"));

            verify(consultationService).listSessions(0L);
        }

        @Test
        @DisplayName("GET /api/v1/ai/sessions/{sessionSn}/history 返回完整对话历史含 citations")
        void getSessionHistory_validSession_returnsHistory() throws Exception {
            var citations = List.of(
                    ConsultStreamResponse.Citation.builder()
                            .title("Guide A")
                            .category("Clinical Guideline")
                            .snippet("Sample snippet")
                            .build()
            );
            var history = List.of(
                    SessionHistoryVO.builder()
                            .role("user")
                            .content("我头痛怎么办")
                            .timestamp("2026-06-28T10:00:00")
                            .build(),
                    SessionHistoryVO.builder()
                            .role("assistant")
                            .content("建议您先休息，如果持续请就医。")
                            .timestamp("2026-06-28T10:00:05")
                            .citations(citations)
                            .build()
            );
            when(consultationService.getSessionHistory("session_001", 0L))
                    .thenReturn(history);

            mockMvc.perform(get("/api/v1/ai/sessions/session_001/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].role").value("user"))
                    .andExpect(jsonPath("$.data[0].content").value("我头痛怎么办"))
                    .andExpect(jsonPath("$.data[0].timestamp").value("2026-06-28T10:00:00"))
                    .andExpect(jsonPath("$.data[0].createTime").doesNotExist())
                    .andExpect(jsonPath("$.data[1].role").value("assistant"))
                    .andExpect(jsonPath("$.data[1].citations.length()").value(1))
                    .andExpect(jsonPath("$.data[1].citations[0].title").value("Guide A"))
                    .andExpect(jsonPath("$.data[1].citations[0].category").value("Clinical Guideline"))
                    .andExpect(jsonPath("$.data[1].citations[0].snippet").value("Sample snippet"));
        }

        @Test
        @DisplayName("GET /api/v1/ai/sessions 无会话时返回空列表")
        void listSessions_empty_returnsEmptyList() throws Exception {
            when(consultationService.listSessions(0L)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/ai/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    @Nested
    @DisplayName("SSE流式问诊接口")
    class StreamConsult {

        @Test
        @DisplayName("POST /api/v1/ai/consult/stream 返回SSE content-type")
        void streamConsult_withSessionId_returnsSseStream() throws Exception {
            when(consultationService.streamConsult(any(ConsultStreamRequest.class), eq(0L)))
                    .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(5000L));

            ConsultStreamRequest request = ConsultStreamRequest.builder()
                    .sessionId("session_001")
                    .message("我头痛怎么办")
                    .build();

            mockMvc.perform(post("/api/v1/ai/consult/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            verify(consultationService).streamConsult(argThat(req ->
                    "session_001".equals(req.getSessionId()) &&
                    "我头痛怎么办".equals(req.getMessage())
            ), eq(0L));
        }

        @Test
        @DisplayName("POST /api/v1/ai/consult/stream 无sessionId时传递给service自动创建")
        void streamConsult_withoutSessionId_passesNullToService() throws Exception {
            when(consultationService.streamConsult(any(ConsultStreamRequest.class), eq(0L)))
                    .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(5000L));

            ConsultStreamRequest request = ConsultStreamRequest.builder()
                    .message("我头痛怎么办")
                    .build();

            mockMvc.perform(post("/api/v1/ai/consult/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            verify(consultationService).streamConsult(argThat(req ->
                    req.getSessionId() == null && "我头痛怎么办".equals(req.getMessage())
            ), eq(0L));
        }
    }

    @Nested
    @DisplayName("知识导入接口")
    class KnowledgeImport {

        @Test
        @DisplayName("POST /api/v1/ai/knowledge/import 成功导入文档")
        void importKnowledge_validRequest_returnsCount() throws Exception {
            when(ragRetrievalService.importDocument(anyString(), anyString(), anyString())).thenReturn(1);

            String body = "{\"title\":\"测试文档\",\"content\":\"测试内容\",\"category\":\"测试科\"}";

            mockMvc.perform(post("/api/v1/ai/knowledge/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(1));

            verify(ragRetrievalService).importDocument("测试文档", "测试内容", "测试科");
        }
    }
}

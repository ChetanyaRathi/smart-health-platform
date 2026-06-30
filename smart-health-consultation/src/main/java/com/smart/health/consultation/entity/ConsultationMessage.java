package com.smart.health.consultation.entity;

import com.smart.health.consultation.dto.ConsultStreamResponse;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问诊会话消息实体
 */
@Data
public class ConsultationMessage {

    /** 消息ID */
    private Long id;

    /** 所属会话ID */
    private Long sessionId;

    /** 消息角色: user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** RAG 引用来源（JSON，仅 assistant 消息） */
    private List<ConsultStreamResponse.Citation> citations;

    /** 创建时间 */
    private LocalDateTime createTime;
}

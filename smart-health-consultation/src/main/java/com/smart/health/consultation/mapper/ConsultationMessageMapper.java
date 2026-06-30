package com.smart.health.consultation.mapper;

import com.smart.health.consultation.dto.SessionHistoryVO;
import com.smart.health.consultation.entity.ConsultationMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 问诊会话消息 Mapper
 */
@Mapper
public interface ConsultationMessageMapper {

    /**
     * 插入一条会话消息
     */
    int insert(ConsultationMessage message);

    /**
     * 查询会话历史（按时间升序）
     */
    List<SessionHistoryVO> selectHistoryBySessionId(@Param("sessionId") Long sessionId);
}

package com.agentforge.session.impl.mapper;

import com.agentforge.session.impl.entity.MessageToolCall;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 工具调用记录 Mapper —— 审计型数据, 基本只 insert + 按 messageId 查
 */
@Mapper
public interface MessageToolCallMapper extends BaseMapper<MessageToolCall> {

    @Update("""
        UPDATE message_tool_call
        SET call_result = #{callResult},
            status = #{status},
            duration_ms = #{durationMs},
            error_msg = #{errorMsg},
            finished_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND call_id = #{callId}
        """)
    void finishByCallId(Long tenantId, String callId, String callResult,
                        Integer status, Integer durationMs, String errorMsg);
}

package com.agentforge.session.impl.mapper;

import com.agentforge.session.impl.entity.MessageSkillCall;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 技能调用记录 Mapper —— 审计型数据，基本只 insert + 按 call_id 收尾。
 */
@Mapper
public interface MessageSkillCallMapper extends BaseMapper<MessageSkillCall> {

    @Update("""
        UPDATE message_skill_call
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

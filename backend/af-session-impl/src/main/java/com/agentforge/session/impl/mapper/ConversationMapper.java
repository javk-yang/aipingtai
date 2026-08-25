package com.agentforge.session.impl.mapper;

import com.agentforge.session.impl.entity.Conversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会话 Mapper —— CRUD 走 MyBatis-Plus 基类, 列表/分页在 Service 用 LambdaQueryWrapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /** 消息数 +1 且刷新最后消息时间: 每条消息落库后调用(原子, 不读后写) */
    @Update("UPDATE conversation SET message_count = message_count + 1, last_message_at = NOW(3) WHERE id = #{id}")
    void incrementMessageCount(@Param("id") String id);
}

package com.agentforge.session.impl.mapper;

import com.agentforge.session.impl.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 消息 Mapper
 *
 * 设计决策:
 * nextSeq 用 SELECT MAX(seq) —— 单会话内消息量有限, 且只在插入时调用一次,
 * 比维护独立计数器简单且无并发双写风险(同一会话写入本就串行, 见 ChatService 锁)。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /** 取会话内当前最大 seq(没有则返回 0), 用于生成下一条序号 */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM message WHERE conversation_id = #{convId} AND tenant_id = #{tenantId}")
    Integer selectMaxSeq(@Param("convId") String convId, @Param("tenantId") Long tenantId);

    /** 增量落库: 覆盖式写当前累积内容(铁律5, 节流调用) */
    @Update("UPDATE message SET content = #{content} WHERE id = #{id}")
    void updateContent(@Param("id") Long id, @Param("content") String content);

    /** 完成消息并写入模型/token 元数据 */
    @Update("UPDATE message SET status = 1, model = #{model}, token_input = #{tokenInput}, token_output = #{tokenOutput} WHERE id = #{id}")
    void completeMessage(@Param("id") Long id,
                         @Param("model") String model,
                         @Param("tokenInput") Integer tokenInput,
                         @Param("tokenOutput") Integer tokenOutput);

    /** 更新消息状态: 1完成 2失败 3中断 */
    @Update("UPDATE message SET status = #{status} WHERE id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);
}

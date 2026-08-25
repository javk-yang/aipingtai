package com.agentforge.session.impl.service;

import cn.hutool.core.util.IdUtil;
import com.agentforge.common.api.PageResult;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.agentforge.session.api.dto.ConversationCreateRequest;
import com.agentforge.session.api.dto.ConversationResponse;
import com.agentforge.session.api.dto.ConversationUpdateRequest;
import com.agentforge.session.api.dto.MessageResponse;
import com.agentforge.session.impl.entity.Conversation;
import com.agentforge.session.impl.entity.Message;
import com.agentforge.session.impl.mapper.ConversationMapper;
import com.agentforge.session.impl.mapper.MessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话服务 —— 会话 CRUD + 消息历史(断线重连的恢复点)
 *
 * 设计决策:
 * 1. 归属校验统一在 requireOwned(): 查不到=404, 非本人=403(越权),
 *    所有对外接口都过它, 不存在"漏校验"路径。
 * 2. 软删只置 status=3 + 填 deleted_at: BaseEntity 的 @TableLogic 让后续查询自动过滤,
 *    既保留数据又对用户不可见。
 * 3. listMessages 是"恢复点": 断线重连 / 刷新页面 / 历史回看都走它,
 *    SSE 只是"增量推送", 真正的数据以这里为准(铁律5: 增量落库可恢复)。
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    @Transactional
    public ConversationResponse create(Long userId, Long tenantId, ConversationCreateRequest req) {
        Conversation c = new Conversation();
        c.setId(IdUtil.fastSimpleUUID());           // CHAR(32) UUID, 去横杠, 不可枚举
        c.setUserId(userId);
        c.setTenantId(tenantId);
        c.setTitle(req.getTitle());
        c.setAgentId(req.getAgentId());
        c.setStatus(1);
        c.setMessageCount(0);
        conversationMapper.insert(c);
        return toResponse(c);
    }

    public PageResult<ConversationResponse> list(Long userId, Long tenantId, long page, long size) {
        Page<Conversation> p = new Page<>(page, size);
        LambdaQueryWrapper<Conversation> w = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTenantId, tenantId)
                .ne(Conversation::getStatus, 3)             // 过滤已删除(兼容历史未填 deleted_at 的记录)
                .orderByDesc(Conversation::getUpdatedAt);   // 最近活跃在前
        conversationMapper.selectPage(p, w);
        List<ConversationResponse> records = p.getRecords().stream()
                .map(this::toResponse).collect(Collectors.toList());
        return PageResult.of(p.getCurrent(), p.getSize(), p.getTotal(), records);
    }

    public ConversationResponse get(Long userId, Long tenantId, String convId) {
        return toResponse(requireOwned(convId, userId, tenantId));
    }

    @Transactional
    public ConversationResponse update(Long userId, Long tenantId, String convId, ConversationUpdateRequest req) {
        Conversation c = requireOwned(convId, userId, tenantId);
        if (req.getTitle() != null) c.setTitle(req.getTitle());
        if (req.getStatus() != null) c.setStatus(req.getStatus());
        conversationMapper.updateById(c);
        return toResponse(c);
    }

    @Transactional
    public void delete(Long userId, Long tenantId, String convId) {
        Conversation c = requireOwned(convId, userId, tenantId);
        // 通过 MyBatis-Plus 逻辑删除触发 deleted_at=NOW(3)；
        // @TableLogic 字段在 updateById 中会被框架忽略，因此不能手动 setDeletedAt 后 update。
        conversationMapper.deleteById(c.getPkId());
    }

    public List<MessageResponse> listMessages(Long userId, Long tenantId, String convId) {
        requireOwned(convId, userId, tenantId);      // 先校验归属再读消息
        LambdaQueryWrapper<Message> w = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, convId)
                .eq(Message::getTenantId, tenantId)
                .orderByAsc(Message::getSeq);        // 按序号升序还原对话顺序
        return messageMapper.selectList(w).stream()
                .map(this::toMessage).collect(Collectors.toList());
    }

    // ========== 内部 ==========

    /** 校验会话存在且归属当前用户, 否则抛对应错误码 */
    private Conversation requireOwned(String convId, Long userId, Long tenantId) {
        Conversation c = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, convId)
                .eq(Conversation::getTenantId, tenantId));
        if (c == null) throw new BizException(ErrorCode.CONVERSATION_NOT_FOUND);
        if (!c.getUserId().equals(userId)) throw new BizException(ErrorCode.CONVERSATION_ACCESS_DENIED);
        return c;
    }

    private ConversationResponse toResponse(Conversation c) {
        ConversationResponse r = new ConversationResponse();
        r.setId(c.getId());
        r.setUserId(c.getUserId());
        r.setAgentId(c.getAgentId());
        r.setTitle(c.getTitle());
        r.setStatus(c.getStatus());
        r.setMessageCount(c.getMessageCount());
        r.setLastMessageAt(c.getLastMessageAt());
        r.setCreatedAt(c.getCreatedAt());
        r.setUpdatedAt(c.getUpdatedAt());
        return r;
    }

    private MessageResponse toMessage(Message m) {
        MessageResponse r = new MessageResponse();
        r.setId(m.getId());
        r.setConversationId(m.getConversationId());
        r.setRole(m.getRole());
        r.setSeq(m.getSeq());
        r.setContent(m.getContent());
        r.setContentType(m.getContentType());
        r.setStatus(m.getStatus());
        r.setModel(m.getModel());
        r.setTokenInput(m.getTokenInput());
        r.setTokenOutput(m.getTokenOutput());
        r.setParentId(m.getParentId());
        r.setCreatedAt(m.getCreatedAt());
        return r;
    }
}

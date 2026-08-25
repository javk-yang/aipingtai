package com.agentforge.session.impl.controller;

import com.agentforge.common.api.PageResult;
import com.agentforge.common.api.R;
import com.agentforge.common.security.UserContext;
import com.agentforge.session.api.dto.ConversationCreateRequest;
import com.agentforge.session.api.dto.ConversationResponse;
import com.agentforge.session.api.dto.ConversationUpdateRequest;
import com.agentforge.session.api.dto.MessageResponse;
import com.agentforge.session.impl.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话控制器 —— /api/conversations/**
 *
 * 设计决策:
 * 1. 所有成功响应统一包 R<T>: 和前端 request.ts 的 "body.code===0 即成功" 契约对齐
 *    (PageResult 自带 code, 但会话详情/列表仍走 R 包裹保持格式统一)
 * 2. userId/tenantId 从 UserContext 取, 不接收请求体里的 —— 防越权伪造
 * 3. /{id}/messages 是断线重连恢复点: GET 即可拉回已落库(含 status=0 流式中的)消息
 * 4. 路由需加入 SecurityProperties 白名单(在 application-dev.yml 的 security.whitelist)
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    public R<ConversationResponse> create(@Valid @RequestBody ConversationCreateRequest req) {
        var u = UserContext.getRequired();
        return R.ok(conversationService.create(u.getUserId(), u.getTenantId(), req));
    }

    @GetMapping
    public R<PageResult<ConversationResponse>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        var u = UserContext.getRequired();
        return R.ok(conversationService.list(u.getUserId(), u.getTenantId(), page, size));
    }

    @GetMapping("/{id}")
    public R<ConversationResponse> get(@PathVariable String id) {
        var u = UserContext.getRequired();
        return R.ok(conversationService.get(u.getUserId(), u.getTenantId(), id));
    }

    @PatchMapping("/{id}")
    public R<ConversationResponse> update(@PathVariable String id,
                                          @Valid @RequestBody ConversationUpdateRequest req) {
        var u = UserContext.getRequired();
        return R.ok(conversationService.update(u.getUserId(), u.getTenantId(), id, req));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        var u = UserContext.getRequired();
        conversationService.delete(u.getUserId(), u.getTenantId(), id);
        return R.ok();
    }

    @GetMapping("/{id}/messages")
    public R<List<MessageResponse>> messages(@PathVariable String id) {
        var u = UserContext.getRequired();
        return R.ok(conversationService.listMessages(u.getUserId(), u.getTenantId(), id));
    }
}

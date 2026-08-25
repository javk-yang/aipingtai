package com.agentforge.session.impl.controller;

import com.agentforge.common.security.LoginUser;
import com.agentforge.common.security.UserContext;
import com.agentforge.session.api.dto.ChatRequest;
import com.agentforge.session.impl.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天流控制器 —— /api/chat/stream
 *
 * 设计决策:
 * 1. POST + 返回 SseEmitter: 前端用 fetch 发(body 带 content), 读 response.body 流
 *    (EventSource 不支持 POST body, 所以不用 GET; fetch 读流更灵活)
 * 2. produces = text/event-stream: 声明 SSE 内容类型, 前端/代理据此不缓冲
 * 3. 路由需加入 SecurityProperties 白名单(application-dev.yml 的 security.whitelist)
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest req) {
        LoginUser u = UserContext.getRequired();
        return chatService.stream(req, u);
    }
}

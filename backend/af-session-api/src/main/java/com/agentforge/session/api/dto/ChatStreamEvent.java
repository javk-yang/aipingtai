package com.agentforge.session.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * SSE 流式事件 —— 前后端"流式的语言"(平台级协议)
 *
 * 设计决策:
 * 1. 为什么统一成一个结构而不是每种事件一个类?
 *    SSE 线路上就是一串 event+data, 前端用 switch(type) 分流即可;
 *    多类反而要维护 N 个序列化器, SSE 的"简单"就被破坏了。
 *
 * 2. 事件类型与 data 的语义约定(前端 P11 按此解析):
 *    - message_start : data={ "role":"assistant" }                 流式中消息已创建
 *    - content_delta : data={ "delta":"文本片段" }                  增量文本
 *    - tool_call_start  : data={ "callId":"..", "toolName":"..", "arguments":{..} }
 *    - tool_call_result : data={ "callId":"..", "toolName":"..", "result":{..}, "durationMs":12 }
 *    - tool_call_error  : data={ "callId":"..", "toolName":"..", "errorMessage":".." }
 *    - message_done  : data={ "model":"..", "tokenInput":10, "tokenOutput":200 }
 *    - error         : data={ "code":3303, "message":".." }
 *    - ping          : data=null                                  心跳保活(防代理断流)
 *
 * 3. data 用 Object: 不同事件负载结构不同, 序列化时 Jackson 转成 JSON 对象串,
 *    前端 JSON.parse 后按 type 取字段。牺牲一点类型安全换协议简单——SSE 场景值得。
 *
 * 4. seq: 会话内消息序号, 前端用它给事件排序/去重(乱序到达也不乱)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent implements Serializable {

    /** 事件类型 */
    private String type;

    /** 会话 ID(UUID) */
    private String conversationId;

    /** 流式中消息 ID */
    private Long messageId;

    /** 会话内序号 */
    private Integer seq;

    /** 事件负载(见类注释语义约定) */
    private Object data;

    /** 链路 ID(铁律4: 三语言串联) */
    private String traceId;

    // ========== 事件类型常量 ==========
    public static final String TYPE_MESSAGE_START = "message_start";
    public static final String TYPE_CONTENT_DELTA = "content_delta";
    public static final String TYPE_TOOL_CALL_START = "tool_call_start";
    public static final String TYPE_TOOL_CALL_RESULT = "tool_call_result";
    public static final String TYPE_TOOL_CALL_ERROR = "tool_call_error";
    public static final String TYPE_MESSAGE_DONE = "message_done";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_PING = "ping";

    /** 便捷构造: type + conversationId + data(其余字段后置填充) */
    public static ChatStreamEvent of(String type, String conversationId, Object data) {
        return new ChatStreamEvent(type, conversationId, null, null, data, null);
    }
}

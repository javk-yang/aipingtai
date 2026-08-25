package com.agentforge.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.slf4j.MDC;

/**
 * 统一响应体 R<T> —— 全平台所有接口的唯一返回格式
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么 code=0 表示成功, 而不是 HTTP 200?
 *    业务码和 HTTP 状态码是两套语义: HTTP 200 只表示"请求到达了服务器",
 *    不表示"业务成功"。数据库里查不到用户, HTTP 也能是 200。
 *    用 0=成功 / 非0=失败, 让前端只看 code 一个字段就知道成败, 不用猜 HTTP 状态。
 *
 * 2. 为什么 traceId 放进响应体?
 *    前端拿到响应后, 如果出 bug, 把 traceId 截图给后端,
 *    后端直接 grep traceId 就能拉出这条请求的完整调用链日志。
 *    不放进去的话, 用户只能说"我刚才操作失败了", 后端大海捞针。
 *
 * 3. 为什么用泛型 R<T> 而不是 R<Object>?
 *    编译期类型安全: Controller 返回 R<UserVO>, Swagger 文档自动生成正确 schema,
 *    前端 TS 类型推导也准确。R<Object> 等于没泛型, 全是 Object。
 *
 * 4. 为什么用静态工厂 ok()/fail() 而不是构造器?
 *    工厂方法有语义: R.ok(data) 一眼看出是成功返回, new R(0, "ok", data) 要数参数。
 *    后续还能在工厂方法里加埋点/日志, 构造器做不到。
 *
 * 5. 为什么 JsonInclude.NON_NULL?
 *    data 为 null 时不输出 "data":null, 减少响应体积, 前端也不用判空。
 *    这是 API 响应的标配, 很多团队忘了加导致响应里一堆 null。
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {

    /** 业务码: 0=成功, 其他=失败码 (见 ErrorCode 枚举) */
    private final int code;

    /** 提示信息: 成功时为 "ok", 失败时给用户可读的中文消息 */
    private final String msg;

    /** 业务数据: 泛型 T, 成功时携带, 失败时通常为 null */
    private final T data;

    /** 时间戳: 毫秒级, 用于排查异步/重试导致的时序问题 */
    private final long timestamp;

    /** 链路追踪 ID: 从 MDC 取, 由 TraceIdFilter 在请求入口写入 */
    private final String traceId;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
        // 从 SLF4J MDC 取 traceId: TraceIdFilter 在请求入口 put 进去
        // 如果是非 HTTP 请求(定时任务/MQ消费), MDC 里没有值就给 null, 不报错
        this.traceId = MDC.get("traceId");
    }

    // ============ 成功工厂方法 ============

    public static <T> R<T> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return new R<>(0, msg, data);
    }

    // ============ 失败工厂方法 ============

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> fail(String msg) {
        // 通用失败码 5000, 具体业务码在 ErrorCode 枚举里定义
        return new R<>(5000, msg, null);
    }

    /**
     * 带附加数据的失败: 参数校验失败时传 {field: msg} 给前端精确定位
     */
    public static <T> R<T> fail(int code, String msg, T data) {
        return new R<>(code, msg, data);
    }
}

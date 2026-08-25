package com.agentforge.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页响应体 —— 列表查询统一返回格式
 *
 * 设计决策:
 *
 * 1. 为什么不直接用 MyBatis-Plus 的 Page 对象做返回?
 *    Page 对象里包含了 SQL 执行细节(records/pages/searchCount/optimizeCountSql),
 *    这些是后端内部信息, 不应该暴露给前端。PageResult 只暴露前端需要的 4 个字段。
 *
 * 2. 为什么 total 和 records 分开?
 *    有些前端表格组件(Element/Ant Design Table)需要 total 做分页器,
 *    records 做数据渲染。合成一个字段反而不方便它们消费。
 *
 * 3. 为什么不用泛型继承 R?
 *    R<PageResult<UserVO>> 嵌套泛型在 Swagger 文档里渲染混乱,
 *    而且前端 TypeScript 推导嵌套泛型不友好。分页接口直接返回 PageResult,
 *    不再包一层 R——分页是查询场景, 成功是默认预期, 失败走异常处理器。
 *    但为了统一格式, 这里也加了 code/msg/traceId 字段, 和 R 保持结构一致。
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResult<T> {

    /** 业务码: 0=成功, 与 R 保持一致 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 链路追踪 ID */
    private String traceId;

    /** 当前页码 (从 1 开始) */
    private long page;

    /** 每页条数 */
    private long size;

    /** 总记录数 */
    private long total;

    /** 当前页数据列表 */
    private List<T> records;

    // ========== 工厂方法 ==========

    public static <T> PageResult<T> of(long page, long size, long total, List<T> records) {
        PageResult<T> pr = new PageResult<>(0, "ok", null, page, size, total, records);
        // traceId 从 MDC 取, 和 R 一样
        pr.traceId = org.slf4j.MDC.get("traceId");
        return pr;
    }
}

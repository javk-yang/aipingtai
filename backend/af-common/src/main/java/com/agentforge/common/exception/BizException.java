package com.agentforge.common.exception;

import lombok.Getter;

/**
 * 业务异常 —— 所有"预期内的失败"都抛这个
 *
 * 设计决策:
 *
 * 1. 为什么继承 RuntimeException 而不是 Exception?
 *    如果继承 CheckedException, 方法签名要 throws BizException,
 *    Spring 的事务回滚默认只认 RuntimeException(@Transactional(rollbackFor=Exception.class) 才认 Checked)。
 *    继承 RuntimeException: 不污染方法签名 + 事务自动回滚, 两全。
 *
 * 2. 为什么不像别人那样建一堆 XxxException 子类?
 *    一个枚举 + 一个异常类就够了。建 UserNotFoundException / OrderNotFoundException...
 *    看着"面向对象", 实际上每个子类只是包了个 ErrorCode, 没有任何独立逻辑。
 *    一个 BizException(ErrorCode.USER_NOT_FOUND) 语义完全等价, 且全局处理器只需 catch 一个类型。
 *
 * 3. data 字段干嘛用的?
 *    少数场景下, 异常需要携带额外数据给前端(比如参数校验失败时带字段级错误明细)。
 *    大多数时候 data 为 null, 不影响 R<T> 的 JsonInclude.NON_NULL 行为。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 异常附加数据, 大多数场景为 null */
    private final transient Object data;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String customMsg) {
        super(customMsg);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, Object data) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.data = data;
    }

    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMsg(), cause);
        this.errorCode = errorCode;
        this.data = null;
    }
}

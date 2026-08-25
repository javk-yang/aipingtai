package com.agentforge.common.exception;

import com.agentforge.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 —— 平台所有 Controller 的异常兜底
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么用 @RestControllerAdvice 而不是 @ControllerAdvice?
 *    @ControllerAdvice 需要每个 @ExceptionHandler 配 @ResponseBody, 漏了就返回视图名。
 *    @RestControllerAdvice 默认所有 handler 都是 JSON 响应, 一步到位。
 *
 * 2. 异常处理优先级: 最具体 → 最通用
 *    Spring 按"异常类型匹配最近"原则选 handler。所以把 BizException 放前面,
 *    Exception 兜底放最后。不能反——反了所有异常都走兜底, 就失去分类处理的意义。
 *
 * 3. 为什么 4xx 用 R<T> 但设置 HTTP 状态码?
 *    前端 Axios 拦截器通常分两层: response 拦截器看 HTTP code 做 401 跳登录/5xx 弹提示,
 *    业务拦截器看 R.code 做"余额不足"等业务提示。两层各管各的, 不冲突。
 *    如果 401 也返回 HTTP 200, 前端无法区分"网络层失败"和"业务层未授权"。
 *
 * 4. 异常日志打什么级别?
 *    BizException = WARN (业务异常是预期内的, 不是系统故障, 不该触发告警)
 *    其他 Exception = ERROR (未知异常, 可能需要人工介入, 触发告警)
 *    这个区分很关键: 把业务异常当 ERROR 打, 监控天天误报, 最终没人看告警了。
 *
 * 5. 为什么参数校验异常单独处理?
 *    @Valid 校验失败时, Spring 抛 MethodArgumentNotValidException,
 *    默认消息是一大段, 前端没法用。这里提取成 {field: msg} 的 map, 前端能精确定位到输入框。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 (预期内, WARN 级别) ====================

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Object>> handleBiz(BizException e, HttpServletRequest req) {
        ErrorCode ec = e.getErrorCode();
        // WARN: 业务异常不是系统故障, 不触发 ERROR 级告警, 但要记录便于排查
        log.warn("业务异常 | traceId={} | uri={} | code={} | msg={}",
                req.getHeader("X-Trace-Id"), req.getRequestURI(), ec.getCode(), ec.getMsg());
        R<Object> r = R.fail(ec.getCode(), ec.getMsg());
        return ResponseEntity.status(ec.getHttpStatus()).body(r);
    }

    // ==================== 参数校验异常 (4xx) ====================

    /**
     * @Valid 校验 @RequestBody 失败
     * 提取字段级错误, 组成 {field: errorMsg} map 传给前端精确定位
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            // 同一字段多条规则时, 只取第一条, 避免消息太长
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("参数校验失败 | errors={}", errors);
        // 用 R.fail(code, msg, data) 直接带字段级错误, 前端能精确标红对应输入框
        R<Object> r = R.fail(ErrorCode.PARAM_VALIDATE_FAILED.getCode(),
                ErrorCode.PARAM_VALIDATE_FAILED.getMsg(), errors);
        return ResponseEntity.status(400).body(r);
    }

    /**
     * @Validated 校验路径参数 / 表单参数失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Object>> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("约束校验失败 | {}", msg);
        R<Object> r = R.fail(ErrorCode.PARAM_VALIDATE_FAILED.getCode(), msg);
        return ResponseEntity.status(400).body(r);
    }

    /**
     * 表单绑定异常 (类型转换失败等)
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Object>> handleBind(BindException e) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("参数绑定失败 | errors={}", errors);
        R<Object> r = R.fail(ErrorCode.PARAM_VALIDATE_FAILED.getCode(),
                ErrorCode.PARAM_VALIDATE_FAILED.getMsg(), errors);
        return ResponseEntity.status(400).body(r);
    }

    // ==================== HTTP 层异常 (4xx) ====================

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Object>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数 | param={}", e.getParameterName());
        R<Object> r = R.fail(ErrorCode.PARAM_MISSING.getCode(),
                "缺少必填参数: " + e.getParameterName());
        return ResponseEntity.status(400).body(r);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 | method={}", e.getMethod());
        R<Object> r = R.fail(ErrorCode.REQUEST_METHOD_NOT_SUPPORTED.getCode(),
                ErrorCode.REQUEST_METHOD_NOT_SUPPORTED.getMsg());
        return ResponseEntity.status(405).body(r);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<R<Object>> handleNotFound(NoHandlerFoundException e) {
        R<Object> r = R.fail(ErrorCode.INTERNAL_ERROR.getCode(), "接口不存在");
        return ResponseEntity.status(404).body(r);
    }

    // ==================== 兜底: 未知异常 (5xx, ERROR 级别) ====================

    /**
     * 所有未被上面拦截的异常都落到这里
     * 这是最后一道防线: 把堆栈打日志, 但只给用户返回"系统内部错误"
     * 不能把堆栈返回给前端——泄露技术栈和代码路径是安全漏洞
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Object>> handleUnknown(Exception e, HttpServletRequest req) {
        // ERROR: 未知异常, 触发告警, 需要人工介入
        log.error("未捕获异常 | traceId={} | uri={} | ",
                req.getHeader("X-Trace-Id"), req.getRequestURI(), e);
        R<Object> r = R.fail(ErrorCode.INTERNAL_ERROR.getCode(),
                ErrorCode.INTERNAL_ERROR.getMsg());
        return ResponseEntity.status(500).body(r);
    }
}

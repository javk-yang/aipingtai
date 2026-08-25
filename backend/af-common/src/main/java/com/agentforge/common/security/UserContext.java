package com.agentforge.common.security;

import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;

/**
 * 用户上下文 —— ThreadLocal 存储当前登录用户
 *
 * 为什么住在 af-common?
 * 和 LoginUser 同理: 会话/Agent 模块都要取当前用户,
 * 它必须是公共底座, 不能属于 auth 模块(P3.2 从 af-auth-impl 挪过来)。
 *
 * 设计决策:
 * 1. 为什么用 ThreadLocal 而不是每个方法传参?
 *    ThreadLocal 是"隐式参数": 过滤器解析 token 后放进去,
 *    同一线程里任何层(Controller/Service/Mapper)都能拿, 不需要在方法签名里层层传递。
 *
 * 2. 为什么必须 finally 里 remove?
 *    Tomcat 线程池复用线程, 不清理, 下一个请求复用该线程时
 *    getUser() 会读到上一个用户的 ID —— 用户数据串号事故的经典来源。
 *
 * 3. 为什么 getRequired() 抛异常而不是返回 null?
 *    绝大多数业务接口要求必须登录, 返回 null 意味着调用方每个方法都要判空,
 *    漏判一次就是 NullPointerException 或"匿名用户操作了他人数据"。
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    /** 放入当前用户(由 JwtAuthFilter 调用) */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 获取当前用户, 未登录返回 null */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 获取当前用户, 未登录抛 401 异常(绝大多数业务接口用这个) */
    public static LoginUser getRequired() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /** 获取当前用户 ID(最常用) */
    public static Long getUserId() {
        return getRequired().getUserId();
    }

    /** 清理(由 JwtAuthFilter 的 finally 调用), 必须执行 */
    public static void clear() {
        HOLDER.remove();
    }
}

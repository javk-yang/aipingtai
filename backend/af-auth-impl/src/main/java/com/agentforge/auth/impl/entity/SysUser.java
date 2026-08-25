package com.agentforge.auth.impl.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 系统用户实体 —— 对应 sys_user 表 (P1 第 1 张表)
 *
 * 设计决策:
 *
 * 1. 为什么继承 BaseEntity?
 *    BaseEntity 提供 tenantId/createdAt/updatedAt/deletedAt 四个公共字段,
 *    createdAt/updatedAt 由 MyBatisPlusConfig 的 MetaObjectHandler 自动填充,
 *    deletedAt 是逻辑删除(查自动加 deleted_at IS NULL)。
 *
 * 2. 为什么 id 用 @TableId(type = IdType.AUTO)?
 *    sys_user 是内部表, 主键自增: 8 字节紧凑、插入顺序写、性能最佳。
 *    AUTO = 数据库自增, 不用应用层生成。
 *
 * 3. 为什么 loginFailCount 和 lockedUntil 是实体字段?
 *    它们是登录风控的状态(P1 表设计里有):
 *    - loginFailCount: 连续失败次数, 用于指数退避锁定
 *    - lockedUntil: 锁定到期时间
 *    放实体里是因为风控状态要持久化, 重启不丢。
 *    Redis 限流只管"短时间频次", 这个是"累计失败"的持久状态。
 *
 * 4. 为什么密码字段叫 passwordHash 而不是 password?
 *    语义准确性: 数据库里存的从来不是密码, 是 BCrypt 哈希。
 *    命名上杜绝"把哈希当密码用"的代码(比如直接比较两个 password 字段)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 主键: 内部自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名: 唯一(联合租户) */
    private String username;

    /** 邮箱: 可作为登录凭证 */
    private String email;

    /** 手机号: E.164 格式, 可作为登录凭证 */
    private String phone;

    /** BCrypt 哈希: $2a$10$盐22字符哈希31字符 */
    private String passwordHash;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatarUrl;

    /** 状态: 1正常 2停用 3锁定 */
    private Integer status;

    /** 邮箱是否已验证: 0否 1是 */
    private Integer emailVerified;

    /** 手机是否已验证: 0否 1是 */
    private Integer phoneVerified;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    /** 最后登录 IP (兼容 IPv6) */
    private String lastLoginIp;

    /** 连续登录失败次数: 风控持久状态 */
    private Integer loginFailCount;

    /** 锁定到期时间: 指数退避锁定 */
    private LocalDateTime lockedUntil;
}

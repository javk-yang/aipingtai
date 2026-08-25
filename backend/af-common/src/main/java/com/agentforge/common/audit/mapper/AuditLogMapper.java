package com.agentforge.common.audit.mapper;

import com.agentforge.common.audit.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper —— 只读 + 只追加
 *
 * 刻意不提供 update/delete 相关的自定义方法:
 * 审计数据不可篡改是合规底线, 业务代码想改也改不了。
 * 查询走 BaseMapper 的 select 系方法(分页由 MyBatis-Plus 插件支持)。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}

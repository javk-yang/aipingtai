package com.agentforge.session.impl.usage.mapper;

import com.agentforge.session.impl.usage.entity.ApiQuota;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配额 Mapper —— api_quota 表
 * 查询用 BaseMapper + LambdaQueryWrapper 即可, 无需自定义 SQL。
 */
@Mapper
public interface ApiQuotaMapper extends BaseMapper<ApiQuota> {
}

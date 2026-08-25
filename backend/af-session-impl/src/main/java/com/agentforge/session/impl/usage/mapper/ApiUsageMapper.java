package com.agentforge.session.impl.usage.mapper;

import com.agentforge.session.impl.usage.entity.ApiUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * API 用量 Mapper —— 计费数据只追加, 提供按天聚合查询
 */
@Mapper
public interface ApiUsageMapper extends BaseMapper<ApiUsage> {

    /**
     * 租户最近 N 天按天聚合: 调用次数 / 输入 / 输出 token / 成本。
     * 返回 Map 而非 DTO: DATE_FORMAT 的字符串日期 + SUM 的数值,
     * 结构简单且 MyBatis 对 Map 不做类型映射, 天然免疫日期类型兼容问题。
     * key: stat_date / calls / token_input / token_output / cost
     */
    @Select("""
        SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS stat_date,
               COUNT(*)                            AS calls,
               COALESCE(SUM(token_input), 0)       AS token_input,
               COALESCE(SUM(token_output), 0)      AS token_output,
               COALESCE(SUM(cost), 0)              AS cost
        FROM api_usage
        WHERE tenant_id = #{tenantId}
          AND created_at >= #{since}
        GROUP BY stat_date
        ORDER BY stat_date DESC
        """)
    List<Map<String, Object>> selectDailyAgg(
            @Param("tenantId") Long tenantId,
            @Param("since") java.time.LocalDateTime since);
}

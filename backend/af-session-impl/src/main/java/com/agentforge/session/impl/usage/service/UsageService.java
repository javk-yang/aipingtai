package com.agentforge.session.impl.usage.service;

import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.agentforge.session.impl.usage.dto.UsageStatsResponse;
import com.agentforge.session.impl.usage.entity.ApiQuota;
import com.agentforge.session.impl.usage.entity.ApiUsage;
import com.agentforge.session.impl.usage.mapper.ApiQuotaMapper;
import com.agentforge.session.impl.usage.mapper.ApiUsageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用量与配额服务 —— P13 可观测性与计费的核心
 *
 * 两条链路:
 * 1. 预检(precheck): 聊天请求进来先看配额, 超限直接 403 拒绝。
 *    - DB 读配额配置(token_limit), Redis 读当日已用计数
 *    - 没配配额 = 不限流(默认行为, 兼容旧租户)
 * 2. 记账(record): 消息完成后
 *    - Redis INCRBY 原子累加当日 token(高吞吐计数, 预检读它)
 *    - 落 api_usage 表(计费/看板的持久真相源)
 *
 * 为什么 Redis 计数 + DB 双写?
 * - 预检是热路径, 每次请求都要查: Redis O(1) 扛得住
 * - 看板/计费要精确且可审计: DB 是唯一真相源
 * - Redis 丢了没关系(重启/过期), DB 里能重算补回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private final ApiUsageMapper apiUsageMapper;
    private final ApiQuotaMapper apiQuotaMapper;
    private final StringRedisTemplate redis;

    // ==================== 价格模型(简化常量表, 未来挪数据库) ====================

    /** 输入单价: ¥1 / M token */
    private static final BigDecimal INPUT_PRICE = new BigDecimal("0.000001");
    /** 输出单价: ¥2 / M token */
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("0.000002");

    /** 配额计数 Redis key: af:quota:usage:{tenantId}:{yyyyMMdd} */
    private static final String QUOTA_USAGE_KEY = CommonConst.REDIS_KEY_QUOTA + "usage:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 看板默认展示最近天数 */
    private static final int STAT_DAYS = 7;

    // ==================== 配额预检 ====================

    /**
     * 聊天请求前调用: 当日已用 >= token_limit → 抛 QUOTA_EXCEEDED。
     * 未配置配额直接放行(不限流)。
     */
    public void precheck(Long tenantId) {
        ApiQuota quota = loadDayQuota(tenantId);
        if (quota == null) {
            return;
        }
        long used = currentUsage(tenantId);
        if (used >= quota.getTokenLimit()) {
            log.warn("配额超限拦截 | tenantId={} | used={} | limit={}",
                    tenantId, used, quota.getTokenLimit());
            throw new BizException(ErrorCode.QUOTA_EXCEEDED);
        }
    }

    // ==================== 记账 ====================

    /**
     * 消息完成后调用: Redis 计数 + 落 api_usage 表。
     *
     * @param conversationId 会话 ID(溯源)
     * @param model          生成模型
     * @param latencyMs      本调用耗时
     */
    public void record(Long tenantId, Long userId, String conversationId,
                       String model, int tokenInput, int tokenOutput, int latencyMs) {
        try {
            // 1. Redis 原子累加(预检数据源)。首次写入时设 TTL, 防 key 永久堆积
            String key = quotaUsageKey(tenantId);
            Long used = redis.opsForValue().increment(key, tokenInput + tokenOutput);
            if (used != null && used == (long) tokenInput + tokenOutput) {
                redis.expire(key, 2, TimeUnit.DAYS);   // 2 天 TTL, 覆盖当日 + 次日统计窗口
            }

            // 2. 落 api_usage 表(计费真相源)
            ApiUsage row = new ApiUsage();
            row.setTenantId(tenantId);
            row.setUserId(userId);
            row.setConversationId(conversationId);
            row.setModel(model == null ? "unknown" : model);
            row.setTokenInput(tokenInput);
            row.setTokenOutput(tokenOutput);
            row.setCost(calcCost(tokenInput, tokenOutput));
            row.setLatencyMs(latencyMs);
            apiUsageMapper.insert(row);
        } catch (Exception e) {
            // 计费是旁路逻辑, 失败不阻断主流程(记日志, 看板可看到缺口)
            log.warn("usage record failed | tenantId={} | conv={}", tenantId, conversationId, e);
        }
    }

    // ==================== 统计看板 ====================

    /** 组装看板: 今日累计 + 配额状态 + 最近 7 天趋势 */
    public UsageStatsResponse stats(Long tenantId) {
        LocalDateTime weekAgo = LocalDate.now().minusDays(STAT_DAYS - 1).atStartOfDay();
        List<Map<String, Object>> rows = apiUsageMapper.selectDailyAgg(tenantId, weekAgo);

        // 1. 今日累计(聚合结果第一行 = 今天, 因为倒序)
        UsageStatsResponse.UsageToday today = parseToday(rows);

        // 2. 配额状态
        UsageStatsResponse.QuotaStatus quota = buildQuotaStatus(tenantId);

        // 3. 最近 7 天趋势(补全缺失日期为 0, 前端折线图不用自己填洞)
        List<UsageStatsResponse.UsageDaily> daily = fillDaily(rows, STAT_DAYS);

        return new UsageStatsResponse(today, quota, daily);
    }

    // ==================== 私有工具 ====================

    /** 加载租户当日配额(scope=tenant, period=day); 没有返回 null 表示不限 */
    private ApiQuota loadDayQuota(Long tenantId) {
        return apiQuotaMapper.selectOne(new LambdaQueryWrapper<ApiQuota>()
                .eq(ApiQuota::getTenantId, tenantId)
                .eq(ApiQuota::getScope, "tenant")
                .eq(ApiQuota::getScopeId, tenantId)
                .eq(ApiQuota::getPeriod, "day")
                .last("LIMIT 1"));
    }

    /** 读 Redis 当日已用 token(预检数据源) */
    private long currentUsage(Long tenantId) {
        String v = redis.opsForValue().get(quotaUsageKey(tenantId));
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String quotaUsageKey(Long tenantId) {
        return QUOTA_USAGE_KEY + tenantId + ":" + LocalDate.now().format(DAY_FMT);
    }

    /** 成本 = 输入token×输入单价 + 输出token×输出单价, 保留 6 位小数(表字段 DECIMAL(12,6)) */
    private BigDecimal calcCost(int tokenInput, int tokenOutput) {
        return BigDecimal.valueOf(tokenInput)
                .multiply(INPUT_PRICE)
                .add(BigDecimal.valueOf(tokenOutput).multiply(OUTPUT_PRICE))
                .setScale(6, RoundingMode.HALF_UP);
    }

    /** 从按天聚合结果里取今天(第一行) */
    private UsageStatsResponse.UsageToday parseToday(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new UsageStatsResponse.UsageToday(0, 0, 0, BigDecimal.ZERO.setScale(6));
        }
        Map<String, Object> first = rows.get(0);
        return new UsageStatsResponse.UsageToday(
                toLong(first.get("calls")),
                toLong(first.get("token_input")),
                toLong(first.get("token_output")),
                toBigDecimal(first.get("cost")));
    }

    private UsageStatsResponse.QuotaStatus buildQuotaStatus(Long tenantId) {
        ApiQuota quota = loadDayQuota(tenantId);
        if (quota == null) {
            return null;   // 未配置 = 不限, 前端隐藏配额卡片
        }
        long used = currentUsage(tenantId);
        long limit = quota.getTokenLimit();
        int soft = quota.getSoftThreshold() == null ? 80 : quota.getSoftThreshold();
        double percent = limit <= 0 ? 0 : used * 100.0 / limit;
        return new UsageStatsResponse.QuotaStatus(
                limit,
                Math.min(used, limit),
                Math.max(limit - used, 0),
                Math.min(percent, 100.0),
                soft,
                percent >= soft,
                used >= limit);
    }

    /** 补全最近 N 天: 已有日期用聚合值, 缺失日期填 0 */
    private List<UsageStatsResponse.UsageDaily> fillDaily(
            List<Map<String, Object>> rows, int days) {
        Map<String, Map<String, Object>> byDate = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                byDate.put(String.valueOf(r.get("stat_date")), r);
            }
        }
        List<UsageStatsResponse.UsageDaily> list = new ArrayList<>(days);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            LocalDate d = today.minusDays(i);
            String key = d.toString();
            Map<String, Object> row = byDate.get(key);
            list.add(new UsageStatsResponse.UsageDaily(
                    key,
                    row == null ? 0 : toLong(row.get("calls")),
                    row == null ? 0 : toLong(row.get("token_input")),
                    row == null ? 0 : toLong(row.get("token_output")),
                    row == null ? BigDecimal.ZERO.setScale(6) : toBigDecimal(row.get("cost"))));
        }
        return list;
    }

    private long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private BigDecimal toBigDecimal(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
    }
}

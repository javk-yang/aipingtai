package com.agentforge.session.impl.usage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用量统计响应 —— GET /api/usage/stats
 *
 * 三段结构:
 * - today:   今日累计(调用次数 / token / 成本)
 * - quota:   配额状态(上限 / 已用 / 剩余 / 是否超限)
 * - daily:   最近 7 天趋势(看板折线图数据)
 */
@Data
@AllArgsConstructor
public class UsageStatsResponse {

    /** 今日累计 */
    private UsageToday today;

    /** 配额状态(未配置配额时 quota=null, 前端隐藏进度条) */
    private QuotaStatus quota;

    /** 最近 7 天按天聚合(倒序, 最新在前) */
    private List<UsageDaily> daily;

    @Data
    @AllArgsConstructor
    public static class UsageToday {
        private long calls;
        private long tokenInput;
        private long tokenOutput;
        private BigDecimal cost;
    }

    @Data
    @AllArgsConstructor
    public static class QuotaStatus {
        private long tokenLimit;
        private long tokenUsed;
        private long remaining;
        /** 已用百分比 0-100 */
        private double usedPercent;
        /** 软告警阈值(百分比) */
        private int softThreshold;
        /** 是否达到软阈值(预警不阻断) */
        private boolean softAlert;
        /** 是否超限(阻断) */
        private boolean exceeded;
    }

    @Data
    @AllArgsConstructor
    public static class UsageDaily {
        private String date;
        private long calls;
        private long tokenInput;
        private long tokenOutput;
        private BigDecimal cost;
    }
}

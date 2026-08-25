# AgentForge Redis 设计规范

> 阶段：P1 ｜ 状态：✅ 已完成
> 配套代码：P2 后端基建中的 RedisConfig / 各策略的 Lua 脚本（P3/P6/P7 使用）

---

## 一、Key 命名规范（四级命名空间）

```
af:{env}:{域}:{业务键}
│   │    │      └─ 业务标识（用户ID/手机号/thread_id 等）
│   │    └───────── 业务域（captcha/rl/lock/agent/cache/quota）
│   └─────────────── 环境（dev/test/prod）—— 同一实例多环境共存不串数据
└─────────────────── 固定前缀 af（AgentForge）
```

**铁律：**

1. 冒号分段、全小写、单词用短横线（`agent:cp` 不用 `agentCp`）
2. **每个 key 必须显式 TTL**——Redis 是内存，无 TTL 的 key 就是慢性内存泄漏
3. 按域分桶的意义：故障时能 `SCAN af:prod:captcha:*` 整域清空，不影响其他域
4. 严禁用 `KEYS` 通配全库（阻塞单线程），必须 `SCAN` 游标式迭代

---

## 二、六类缓存策略

### ① 验证码 · GETDEL 单次消费

```bash
# 发送: 同一手机号 60s 冷却 + 验证码本身 5min 有效
SET af:prod:captcha:login:13800138000 482913 NX EX 300
SET af:prod:captcha:send:cooldown:13800138000 1 EX 60

# 校验: GETDEL 原子"取出即删", 杜绝验证码重放攻击
GETDEL af:prod:captcha:login:13800138000

# 防爆破: 5 次错误锁定 30min
INCR af:prod:captcha:fail:count:13800138000
EXPIRE af:prod:captcha:fail:count:13800138000 1800   # 首次 INCR 时才设
```

**设计经验：** 为什么用 `GETDEL` 而不是 `GET` + `DEL` 两步？两个操作之间有空窗，并发校验可能同时通过——验证码就废了。GETDEL 是 Redis 6.2+ 原生原子命令，一步完成"取走 + 删除"。这是"验证码被薅"事故的经典修复。

### ② 限流 · INCR + EXPIRE 固定窗口

```bash
# 登录接口: 每 IP 每分钟 5 次
INCR af:prod:rl:login:192.168.1.1
EXPIRE af:prod:rl:login:192.168.1.1 60   # 返回 1 说明是新窗口, 才设 TTL

# 业务 API: 每 token 每秒 20 次
INCR af:prod:rl:api:u_10086
EXPIRE af:prod:rl:api:u_10086 1
```

**必须用 Lua 保证原子性**（检查阈值 + 自增，两步之间并发会穿透）：

```lua
-- key: 限流键  limit: 阈值  window: 窗口秒数
local c = redis.call('INCR', KEYS[1])
if c == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
end
if c > tonumber(ARGV[1]) then return 0 end
return 1
```

**为什么固定窗口 + 单独令牌桶？** 登录/接口限流用固定窗口足够（1 分钟 5 次），简单可靠。但 **LLM API 调用**要用令牌桶——因为模型调用是高并发长耗时，固定窗口在窗口边界会双倍放行（59s 和 01s 各来一批）。令牌桶平滑速率，配合 Java 侧信号量控制并发数，是 Agent 平台的保命配置。

### ③ 分布式锁 · SET NX PX + Lua 释放

```bash
# 加锁: 同一个 thread_id 的 Agent 任务只允许一个实例执行
SET af:prod:lock:agent:run:thread_abc123 NX PX 30000

# 释放: 必须比对 value 是自己的锁才删 —— 防"锁过期后被别人拿到, 自己误删别人的锁"
# 不用 Lua 的话, GET 和 DEL 之间的空窗是经典误删事故现场
```

```lua
-- 释放锁: KEYS[1]=锁 key  ARGV[1]=持有者标识(uuid)
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

**为什么 value 要带持有者标识？** 场景：线程 A 拿锁跑任务，任务超 30s 锁自动过期；线程 B 拿到锁；A 结束直接 DEL——把 B 的锁删了，B 和 C 同时进入临界区。比对 value 后只删自己的锁，误删不可能发生。

**长任务怎么办？** 30s 不够就续期——**看门狗线程**每 10s 检查一次任务未完成就 `PEXPIRE` 续 30s（Redisson 的 watchDog 就是这个原理）。续期失败说明进程异常，锁自然过期，不会死锁。

### ④ Agent checkpoint · Hash 存 LangGraph 状态

```bash
# LangGraph 每执行到一个节点, 把状态快照写进 Hash
HSET af:prod:agent:cp:thread_abc123:call_model state '{"messages":[...],"tools":[]}'
EXPIRE af:prod:agent:cp:thread_abc123:call_model 86400   # 24h

# 任务结束: 主动删除, 防止 checkpoint 无限膨胀
DEL af:prod:agent:cp:thread_abc123:call_model
```

**为什么用 Hash 而不是 String？** 每个节点的状态是 `node_name → snapshot` 映射，Hash 天然是一张映射表；单条消息的增量更新用 `HSET` 只写一个字段，不用整串读改写。**TTL 24h**：跨天未完成的对话视为废弃（99.9% 场景成立），防内存泄漏。

**checkpoint 是"智能层无状态"铁律的物理载体**——Python 进程被 kill，重启后从 Redis 读回状态继续跑，用户无感知。

### ⑤ 会话缓存 · Cache-Aside（旁路缓存）

```
读路径:  查缓存 → 未命中 → 查 MySQL → 回填缓存(TTL) → 返回
写路径:  写 MySQL → 删缓存 → (延迟 500ms) 再删一次
```

**为什么"双删"而不是"更新缓存"？** 场景：A 读缓存 miss → 查库(旧值) → B 写库(新值) → B 删缓存 → A 回填缓存(旧值)。缓存里永远是新值变旧值。双删 + 延迟删：B 写完先删一次，500ms 后（等 A 的回填完成）再删一次，彻底清掉脏数据。**缓存永远是重建的，绝不主动更新**——这是 Cache-Aside 的核心。

**空值也缓存**（TTL 60s）：防止"查一个不存在的用户"每次都穿透到 MySQL，被恶意遍历打爆数据库——缓存穿透的经典攻击面。

### ⑥ Token 配额 · INCRBY 月度计数器

```bash
# 每次模型调用结束, 按实际用量累加 (String 的 INCRBY 就是原子计数器)
INCRBY af:prod:quota:u_10086:tokens:202608 1523
EXPIRE af:prod:quota:u_10086:tokens:202608 2592000   # 月末过期自动归零

# 发请求前预检:
GET af:prod:quota:u_10086:tokens:202608   # 超限 → 429, 计费冻结
```

**为什么 Redis 计数 + MySQL 对账？** Redis 是内存计数器，实时、便宜，但**不能作为计费依据**（进程崩溃丢数据）。方案：Redis 做实时预检和展示，**每日凌晨定时任务把 Redis 快照落库 MySQL**，月底以 MySQL 为准出账单。计费系统"实时性让给 Redis，准确性兜底在 MySQL"。

---

## 三、三大缓存灾难的应对（所有缓存都适用）

| 灾难 | 现象 | 应对 |
| --- | --- | --- |
| **穿透** | 查不存在的 key，请求全部打到 DB | 空值缓存 60s + 参数校验 + Bloom Filter 前置 |
| **击穿** | 热点 key 过期瞬间，大量并发同时重建 | 互斥锁重建（拿不到锁就等 50ms 重试） |
| **雪崩** | 大量 key 同一时刻过期，DB 被压垮 | 随机 TTL 抖动 ±20%，热点 key 永不过期 + 后台刷新 |

---

## 四、监控基线（P13 落地）

1. `INFO commandstats` 看 O(N) 危险命令频率（KEYS/HGETALL 大 key）
2. 慢查询 `SLOWLOG GET 10`——单命令 > 10ms 必须排查
3. 命中率 `INFO stats` 的 `keyspace_hits/misses`，低于 70% 说明缓存策略有问题
4. 大 key 扫描：`redis-cli --bigkeys` 定期跑，>10KB 的 value 要拆

---

## 五、key 清单速查表

| 域 | key 模式 | 类型 | TTL | 使用阶段 |
| --- | --- | --- | --- | --- |
| captcha | `captcha:login:{phone}` | String | 5min | P3 |
| captcha | `captcha:send:cooldown:{phone}` | String | 60s | P3 |
| captcha | `captcha:fail:count:{phone}` | String | 30min | P3 |
| rl | `rl:login:{ip}` | String | 60s | P3 |
| rl | `rl:api:{token}` | String | 1s | P3 |
| rl | `rl:llm:{token}` (令牌桶) | String | 1s | P7 |
| lock | `lock:agent:run:{thread}` | String | 30s | P7 |
| agent | `agent:cp:{thread}:{node}` | Hash | 24h | P7 |
| cache | `cache:user:{id}` | String(JSON) | 30min | P2/P3 |
| cache | `cache:role:{id}` | String(JSON) | 30min | P3 |
| quota | `quota:{uid}:tokens:{yyyyMM}` | String | 月末 | P7/P13 |

# 系统级优化说明（上线前加固）

本文记录上线前的六项系统级优化：AI 并发调用、主页精选 Redis 缓存、生成站点实时预览、
AI 接口限流限频、Prompt 审查与注入防护、重试护轨与工具调用循环治理。

所有能力都遵循同一条原则：**优化本身不能成为新的故障点**。缓存、限流、预览状态、
护轨在依赖（Redis、模型、磁盘）异常时都必须降级到“业务仍能继续”的行为，并把降级原因
写进日志而不是抛给用户。

- [1. AI 并发调用](#1-ai-并发调用)
- [2. 主页精选列表 Redis 缓存](#2-主页精选列表-redis-缓存)
- [3. 生成站点实时预览](#3-生成站点实时预览)
- [4. AI 对话接口限流限频](#4-ai-对话接口限流限频)
- [5. Prompt 审查与注入防护](#5-prompt-审查与注入防护)
- [6. 重试护轨与工具调用循环治理](#6-重试护轨与工具调用循环治理)
- [7. 配置速查](#7-配置速查)
- [8. 上线检查清单](#8-上线检查清单)

## 1. AI 并发调用

### 1.1 原来慢在哪里

表面上“只能同时处理一个 AI 请求”，实际是三个串行点叠加：

| 串行点 | 位置 | 影响 |
| --- | --- | --- |
| 对话记忆初始化持有全局锁 | `ConversationMemoryCache#get` | 一次 Redis 未命中 + MySQL 回源（毫秒~百毫秒级）期间，**所有应用**的生成请求都在等锁 |
| AI Service 代理创建持有全局锁 | `AiCodeGeneratorServiceFactory#getAiCodeGeneratorService` | 反射构建代理期间其他应用无法取到缓存 |
| 请求线程被长连接占满 | SSE 生成持续数十秒到数分钟 | 平台线程打满后，连查询接口都开始排队 |

### 1.2 优化方案

1. **记忆加载细粒度化**：LRU 映射表只在读写映射的瞬间加锁，锁内不做任何 IO；同一应用使用
   独立的应用级加载锁（`KeyedLock`），保证并发首访只回源一次，不同应用完全并行。
   加载超时（默认 10s）返回“对话上下文加载超时”，不再无限挂起。
2. **代理创建移出锁外**：命中缓存的请求只承受一次短暂的 map 读取；并发首访最多重复创建一次
   无状态代理，后创建的实例被丢弃。
3. **显式并发控制**：`AiConcurrencyLimiter` 同时限制“全局同时生成轮数”和“同一应用互斥”：
   - 全局：公平信号量，默认 8 轮，排队超时（默认 30s）返回 `SERVICE_BUSY`；
   - 同一应用：应用级锁默认只等 5s，连续点击两次时快速返回“该应用正在生成中”。
   两者通过 `Lease` 成对获取/释放，`Flux.doFinally` 保证完成、异常、取消都会归还许可。
4. **虚拟线程**：`spring.threads.virtual.enabled=true`，SSE 连接由虚拟线程承载，
   容量不再受 Tomcat 平台线程数限制。

### 1.3 为什么同一应用必须串行

一个应用共享一份 `ChatMemory` 和同一个工程目录：

- 两轮并发写同一份记忆，会把两轮工具调用消息交叉写入，模型上下文自相矛盾；
- 两轮并发写同一个 `vue_project_{appId}` 目录，后写覆盖先写，产出半成品工程。

因此同一应用互斥不是性能妥协，而是正确性约束。

### 1.4 相关代码

- `ai/concurrency/KeyedLock.java`：按 key 加锁、引用计数归零即清理，避免锁对象随应用数增长。
- `ai/concurrency/AiConcurrencyLimiter.java`：全局许可 + 应用锁 + 幂等租约。
- `ai/memory/ConversationMemoryCache.java`：锁内无 IO 的 LRU + 应用级加载锁。
- `service/impl/AppServiceImpl#chatToGenCode`：在 `Flux.defer` 中申请许可，`doFinally` 归还。

## 2. 主页精选列表 Redis 缓存

### 2.1 缓存对象

`POST /app/good/list/page/vo` 每次都要做分页 count 和创建者批量查询，是首页最高频的读请求。
缓存保存“查询参数 → 脱敏后的 `AppVO` 分页”，命中时直接返回，不再查库。

### 2.2 key 结构与失效

```
{prefix}good-app:{version}:{pageNum}:{pageSize}:{sortField}:{sortOrder}:{appNameHash}
{prefix}good-app:version      # INCR 失效
```

- 搜索词只保存 `SHA-256` 前 16 位，原始关键词不进入 Redis key，也不进入日志；
- 失效只对版本号执行 `INCR`，新请求自然拼出新 key，旧 key 靠 TTL 过期；
  **不使用 `KEYS`/`SCAN`**，失效操作 O(1)，不会阻塞 Redis；
- 准入规则：`pageNum ≤ 50`、`pageSize ≤ 20`、关键词 ≤ 64 字符、排序字段白名单，
  防止深分页和任意列名制造长尾 key；
- 单页序列化超过 512KB 直接跳过缓存并告警。

### 2.3 降级与一致性

- Redis 不可用、JSON 脏数据、版本号读取失败：一律返回缓存未命中，调用方回源数据库；
  脏 key 会被删除，同类告警 30 秒内只打印一次；
- 主动失效点：应用创建、删除、部署（部署时间）、封面回填、用户改名、管理员改精选优先级、
  工作流同步生成类型。即使漏掉某个写路径，TTL（默认 60s）也会兜底。

### 2.4 为什么不用 Spring Cache 注解

`@Cacheable` 需要额外的缓存管理器配置，且失败时默认把异常抛给调用方。这里需要的是
“任何时候都能静默回源”，因此选择显式的 cache-aside 实现，把降级逻辑写在一个类里，
也便于用已有的 `StringRedisTemplate` 复用连接池。

## 3. 生成站点实时预览

### 3.1 原来会看到旧页面

- 静态模式：生成结束文件即落盘，`setPreview()` 立刻可见，基本没问题；
- Vue 工程：源码写完后还需要 `npm install && npm run build`。构建期间磁盘上可能仍是上一版
  `dist`，直接访问会展示**旧版本**，用户以为生成失败或没生效。

### 3.2 版本号 + 构建状态

`PreviewService` 为每个应用维护：

| 字段 | 含义 |
| --- | --- |
| `revision` | 版本号，每次生成 / 构建成功 `INCR`，作为 iframe 的强制刷新参数 |
| `state` | `MISSING` / `BUILDING` / `READY` / `FAILED` |
| `previewPath` | 相对路径，例如 `static/vue_project_123/`，由前端拼接预览根地址 |

状态优先写 Redis（多实例一致），Redis 不可用时降级为单实例内存记录；**文件系统始终是最终真相**：
状态说 READY 但入口文件已被清理时降级为 MISSING，状态丢失但产物存在时按 READY 处理。

### 3.3 端到端时序

```
生成流结束
  ├─ HTML / 多文件：保存文件 → markGenerated(READY, revision+1)
  └─ Vue：提交异步构建 → markBuildStarted(BUILDING)
          构建结束 → markBuildFinished(READY, revision+1 | FAILED)

前端：GET /api/app/preview/{appId}
  ├─ READY  → iframe 立即换成 static/{type}_{id}/?v={revision}
  ├─ BUILDING → 展示构建提示页，每秒轮询，就绪后自动刷新
  └─ FAILED → 提示“最新版本构建失败，当前可能不是最新效果”
```

工作流链路在 `WorkflowServiceImpl` 结束时统一更新（Vue 根据构建节点结果标记 READY/FAILED）。

### 3.4 前端改动

- 新增 `src/utils/preview.ts`：查询状态、轮询构建结果、拼接带版本号的预览地址；
- `AppChatPage.vue`：生成结束后先取一次状态，就绪立即刷新，否则后台轮询；
  页面回显也改为“先问状态”，构建期间不再把旧 dist 当成最新效果；
- 轮询带 `AbortController`，切换页面或再次生成会中止旧轮询，避免旧响应覆盖新状态。

## 4. AI 对话接口限流限频

### 4.1 限流维度

`@RateLimit` 可重复注解，支持同时声明多条规则（例如 10 次/分钟 + 120 次/小时），
每条规则可独立选择按用户、按 IP 或两者同时限流：

| 接口 | 规则 |
| --- | --- |
| `GET /app/chat/gen/code` | 10 次/分钟、120 次/小时 |
| `POST /app/add` | 5 次/分钟、30 次/小时 |
| `POST /workflow/execute` | 3 次/分钟、20 次/小时 |
| `GET /workflow/execute/stream` | 5 次/分钟、40 次/小时 |

### 4.2 实现要点

- **Redis + Lua 固定窗口**：`INCR` 与首次 `PEXPIRE` 在同一脚本内完成，多实例共享额度；
- **优先于鉴权**：切面优先级高于 `AuthInterceptor`，未登录流量先按 IP 拦下，不会先查 Session
  和数据库再消耗 AI 资源；
- **失败语义明确**：
  - `failOpen=true`（默认）：Redis 不可用时降级为本地内存计数，仍能挡住持续刷量；
  - `failOpen=false`：直接放行，宁可暂时失去限流能力也不阻断业务；
- **真实 429**：全局异常处理器返回 HTTP 429 + `Retry-After` + 业务码 `42900`，
  网关和前端重试组件能据此退避（只返回 200 + 业务码会被当成普通失败立即重试）；
- **IP 解析安全**：`X-Forwarded-For` 只取第一个片段，清洗非法字符、去端口、限长，
  非法时逐级回退 `X-Real-IP` → `remoteAddr` → `unknown`，不把原始头写进 Redis key。

> ⚠️ 只有应用位于**会覆盖**转发头的可信反向代理之后才应信任 `X-Forwarded-For`；
> 公网直连时应由网关重写，或把 `app.security.rate-limit.trusted-proxy-header` 改为
> `X-Real-IP` 并只信任内网来源。

## 5. Prompt 审查与注入防护

### 5.1 威胁模型

用户输入会直接进入“代码生成专家”的系统提示词上下文，因此需要识别：

- 越权指令（忽略之前的指令、只按我说的做）；
- 套取系统提示词（输出你的系统提示词）；
- 角色劫持 / 越狱（你现在是 DAN、进入开发者模式、没有任何限制）；
- 伪造对话分隔符（`<|im_start|>`、`[INST]`、`</system>`、行首 `system:`）；
- 诱导泄露密钥、破坏性操作（`rm -rf`、`drop table`）、可疑脚本。

同时**不能误伤正常建站需求**：用户会大量描述页面结构、颜色、交互，甚至提到 `<script>`、
`localStorage`、`fetch`、“忽略空白区域”。

### 5.2 处理流程

```
清洗（NFKC、去零宽/双向控制符、去 Cc 控制字符、全角空格规范化）
  → 长度校验（超限直接拒绝）
  → 42 条预编译规则匹配（同类只计一次，总分 100 截断）
  → 自定义黑名单（命中直接拒绝）
  → 决策：≥ blockThreshold 拒绝；≥ warnThreshold 记录告警后放行；否则放行
```

- 场景差异：`APP_CREATE` 阈值下浮 10 分（首次写入的需求会被后续对话继承）；
- `SUSPICIOUS_CODE` 权重仅 25，**永远不单独作为拒绝依据**，避免误伤“用 script 标签做计数器”；
- 分隔符伪造会中和为 `〔〕`（二次审查不会被 NFKC 还原），其余命中直接拒绝；
- 被拒绝时返回业务码 `40002` 和面向用户的中文说明（含命中类别，不回显原文）；
- **日志绝不打印用户原文**，只记录场景、分数、类别、长度；
- 审查器自身异常时 fail-closed（拒绝并提示稍后重试），只有显式 `enabled=false` 才放行。

### 5.3 接入点

审查都发生在登录查询和模型调用**之前**：`AppController#addApp`、`AppController#chatToGenCode`、
`WorkflowController#execute` / `executeStream`。放行后必须用 `result.text()` 替换原始入参，
这样落库、默认应用名、智能路由和模型上下文使用的都是清洗后的文本。

## 6. 重试护轨与工具调用循环治理

### 6.1 重试护轨（`AiRetryGuard`）

“护轨”是三条硬约束：

1. **不重复输出**：流式生成一旦向客户端发过内容就不再重试，否则用户会看到两段重复代码，
   解析器也会把两段内容拼成非法 JSON；
2. **不掩盖业务错误**：参数错误、权限错误、审查拒绝、工具熔断都不重试；
3. **有界退避**：指数退避 + 抖动 + 最大退避，避免多实例同时重试把供应商打崩。

可重试白名单：HTTP 408/429/5xx、连接超时、`IOException`、LangChain4j 的
`RateLimitException` / `InternalServerErrorException` / `ServiceUnavailableException`。

Vue 工程模式**不参与自动重试**：该模式由模型调用文件工具写盘，重试意味着重复执行有副作用的
写操作，收益远小于风险。

### 6.2 输出护轨（`CodeOutputGuard`）

保存前校验模型输出：HTML 必须是完整页面（含 `html`/`DOCTYPE` 且长度达到下限）、
不能包含服务器存储目录。截断的输出宁可返回“生成不完整，请重试”，也不写进预览目录。

### 6.3 工具调用无限循环

LangChain4j 的 `maxSequentialToolsInvocations` 只限制连续次数，无法识别“反复写同一个文件”
这类语义死循环。治理分三层：

| 层级 | 机制 | 行为 |
| --- | --- | --- |
| 1 | 工具调用保护器（总预算，默认 60 次/轮） | 超限抛 `ToolCallLimitException`，终止本轮 |
| 2 | 同一工具 + 同一文件重复检测（默认 2 次） | 先返回可读提示，**不执行**写操作，引导模型继续下一个文件 |
| 3 | 模型无视提示继续重复（默认再容忍 5 次） | 升级为终止，避免“提示—重复”无限循环 |

另外：

- `writeFile` 内容与磁盘一致时直接返回“已跳过重复写入”，同一份内容不会反复落盘；
- 流式处理器保留 40 次的硬上限（防御任何形式的暴走），并保底把工具层熔断异常转换为
  用户可读的 SSE `error` 事件；
- 每轮生成开始/结束时登记与清理预算，状态按 `turn-ttl` 和 `max-tracked-turns` 兜底清理。

## 7. 配置速查

```yaml
spring:
  threads:
    virtual:
      enabled: true          # 虚拟线程承载 SSE，突破平台线程上限
  mvc:
    async:
      request-timeout: 30m   # SSE 生成可能持续数分钟

app:
  chat-memory:
    load-timeout: 10s        # 同一应用并发首访等待记忆初始化的上限
  ai:
    concurrency:
      enabled: true
      max-concurrent-requests: 8   # 按模型并发配额设置
      acquire-timeout: 30s
      per-app-acquire-timeout: 5s
    retry:
      enabled: true
      max-attempts: 2
      initial-backoff: 500ms
      max-backoff: 4s
      jitter: 0.3
    tools:
      enabled: true
      max-calls-per-turn: 60
      max-repeats-per-subject: 2
      max-repeated-denials: 5
  cache:
    enabled: true
    default-ttl: 60s
    good-app-list:
      enabled: true
      ttl: 60s
      max-page-num: 50
      max-page-size: 20
      max-app-name-length: 64
  security:
    rate-limit:
      enabled: true
      fail-open: true
      max-tracked-keys: 100000
      trusted-proxy-header: X-Forwarded-For
    prompt-review:
      enabled: true
      block-threshold: 70
      warn-threshold: 40
      max-length: 10000
      sanitize-enabled: true
      log-matches: true
      extra-blocked-phrases: []
  preview:
    enabled: true
    ttl: 7d
    max-local-entries: 5000
```

所有 key 前缀（`zgq:ai-code-mother:*`）在代码中有默认值；需要按环境隔离时，在
`application-local.yml` 或配置中心显式覆盖，避免测试数据污染生产缓存。

## 8. 上线检查清单

部署前逐项确认：

1. **Redis**：与应用共用同一个实例时确认 `maxmemory-policy`（建议 `allkeys-lru` 或
   `volatile-lru`），并核对 `zgq:ai-code-mother:*` 前缀不与其它应用冲突；
2. **并发额度**：`max-concurrent-requests` 不超过模型供应商的并发配额，留出重试余量；
3. **限流阈值**：按真实用户行为调整，先观察 `请求被限流` 日志的量再收紧；
4. **反向代理**：确认网关会覆盖 `X-Forwarded-For`，否则客户端可伪造 IP 绕过 IP 维度；
5. **提示词审查**：上线后观察 `Prompt 审查告警放行` 日志，按场景/类别调阈值；
   如有业务确实需要“越狱教程”这类站点，优先走运营白名单而不是整体降阈值；
6. **虚拟线程**：确认监控里 Tomcat 平台线程不再持续高位；如遇第三方库 pinning
   报警（`jdk.tracePinnedThreads`），定位 `synchronized` 块；
7. **预览**：Vue 工程首次构建较慢（npm install），确认 `waitForPreview` 的超时（6 分钟）
   大于实际构建时间，或在 CI 中预热依赖缓存；
8. **降级演练**：手动停掉 Redis，确认首页列表、限流、预览状态、对话记忆都按文档降级，
   业务不中断；恢复后确认缓存版本号正常递增。

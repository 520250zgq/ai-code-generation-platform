# Redis 分布式 Session 与对话记忆

本文对应两个需求：

1. AI 对话需要记住上下文，使用 Redis 持久化每个应用的对话记忆；
2. 登录态使用 Redis 分布式 Session，服务重启或多实例部署后不需要重新登录。

两套数据共用同一个 Redis 连接，但使用不同 key 前缀，互不影响。

## 1. 依赖与配置

`pom.xml` 只新增一个 starter：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-session-data-redis</artifactId>
</dependency>
```

它会带入 Spring Data Redis、Lettuce、Spring Session 以及 Boot 4 的 Session 自动配置，不再需要额外的 Jedis 客户端。

`application.yml` 的关键配置：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}
      timeout: 3s
  session:
    timeout: 7d
    data:
      redis:
        namespace: zgq:ai-code-mother:session
        flush-mode: on-save
        save-mode: on-set-attribute
        configure-action: none
server:
  servlet:
    session:
      cookie:
        name: ZGQ_SESSION
        http-only: true
        same-site: lax
        max-age: 7d
app:
  chat-memory:
    ttl: 7d
    max-messages: 100
    max-cached-conversations: 100
    key-prefix: "zgq:ai-code-mother:chat-memory:"
```

| 配置 | 作用 |
| --- | --- |
| `spring.data.redis.*` | Session 与对话记忆共用的 Redis 连接 |
| `spring.session.timeout` | Session 空闲过期时间，同时决定 Redis TTL |
| `spring.session.data.redis.namespace` | Session key 前缀 |
| `server.servlet.session.cookie.*` | 登录 Cookie 名称、HttpOnly、SameSite、有效期 |
| `app.chat-memory.ttl` | 对话记忆在 Redis 中的过期时间，每次写入刷新 |
| `app.chat-memory.max-messages` | 每个应用保留的最近消息条数，默认 100；Vue 工具调用会产生较多消息 |
| `app.chat-memory.max-cached-conversations` | 本地热点会话缓存上限，LRU 淘汰 |
| `app.chat-memory.key-prefix` | 对话记忆 key 前缀，实际 key 为前缀 + appId |

生产环境可通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE` 覆盖连接信息。若前后端跨站部署（不同主域名），需要把 Cookie 改为 `same-site: none` 并启用 HTTPS，例如：

```yaml
server:
  servlet:
    session:
      cookie:
        same-site: none
        secure: true
```

## 2. 分布式 Session

### 登录

`UserServiceImpl.userLogin` 校验账号密码后：

1. 若请求已有旧 Session，先 `invalidate()`，防止会话固定攻击；
2. 创建新 Session，只写入 `user_login -> userId`；
3. Spring Session 在请求结束时把 Session 写入 Redis，并返回 `ZGQ_SESSION` Cookie。

Redis 中不保存完整 `User` 对象，因此不会把密码哈希写入 Session。角色、账号状态以数据库为准，管理员改角色或删除账号后立即生效。

### 读取与注销

`UserServiceImpl.getLoginUser`：

1. 使用 `request.getSession(false)`，未登录请求不会创建空 Session；
2. 从 Session 读取 userId（兼容旧版本保存 `User` 对象的 Session）；
3. 回查数据库，用户不存在则失效 Session 并抛出 `NOT_LOGIN_ERROR`。

`userLogout` 直接 `session.invalidate()`，Spring Session 会同步删除 Redis 中的 Session key。

### 服务重启

Session 不再保存在 Tomcat 内存中。重启后浏览器继续携带原 `ZGQ_SESSION` Cookie，新实例从 Redis 读取同一个 Session，因此无需重新登录。多实例部署时，所有实例连接同一个 Redis 即可共享登录态。

从旧版本升级时，原来的 Tomcat 内存 Session（`JSESSIONID`）不会迁移到 Redis，因此首次部署后需要重新登录一次；之后重启服务不再需要。

## 3. 对话记忆

### 调用链

```
AppController.chatToGenCode
  -> AppServiceImpl.chatToGenCode
       -> ConversationMemoryCache.get(appId)        // 预热：保证先恢复历史再落库本轮消息
       -> ChatHistoryService.saveMessage(user)       // 本轮用户消息写入 MySQL
       -> AiCodeGeneratorFacade
            -> AiCodeGeneratorService.generateXxxStream(@MemoryId appId, @UserMessage message)
                 -> AiServices.chatMemoryProvider -> ConversationMemoryCache.get(appId)
                      -> MessageWindowChatMemory(默认最多 100 条)
                           -> SpringRedisChatMemoryStore
                                -> Redis key: zgq:ai-code-mother:chat-memory:{appId}
```

`AiCodeGeneratorService` 的所有对话方法都以 `appId` 作为 `@MemoryId`，不同应用的上下文完全隔离；同一个应用的不同生成模式通过独立缓存键创建各自的服务代理，但共享同一份 appId 对话记忆。

### Vue 工具调用与记忆容量

Vue 工程模式让模型通过 `writeFile` 工具写文件，每一轮工具调用都会在记忆里产生“AI 工具请求 + 工具结果”两条消息。如果记忆窗口仍是 20 条，模型写几个文件后就会忘记已经生成过哪些文件，进而重复调用工具。因此：

- `app.chat-memory.max-messages` 默认提升到 100，足够容纳一轮完整的工程生成；
- 工具调用请求保留在记忆中，下一轮模型可以据此判断哪些文件已经存在；
- 流式工具循环另有硬性次数限制（40 次），避免模型幻觉导致无限写入。

### 首次访问与历史回源

`ConversationMemoryCache` 为每个 appId 创建一个 `MessageWindowChatMemory`：

1. 先从 Redis 读取消息；
2. Redis 为空（首次对话、TTL 过期、实例刚重启）时，调用
   `ChatHistoryService.listRecentMessages(appId, limit)` 读取 MySQL 最近的历史；
3. 历史按时间正序加入记忆，内容以 `AI 回复失败：` 开头的占位消息会被跳过，避免把失败文案发送给模型；
4. 之后 AiServices 自动把本轮用户消息和 AI 回复写入记忆，`SpringRedisChatMemoryStore` 覆盖写入 Redis 并刷新 TTL。

**顺序很重要**：`AppServiceImpl` 在保存本轮用户消息之前先调用 `conversationMemoryCache.get(appId)`。否则 Redis 为空时回源数据库会把本轮消息重复加载一次，导致模型看到两条相同的用户消息。

### 本地缓存

`ConversationMemoryCache` 使用 access-order 的 `LinkedHashMap` 实现 LRU：

- 命中时直接复用内存中的 `ChatMemory`，避免每轮都反序列化 Redis；
- 超过 `max-cached-conversations` 时淘汰最久未使用的会话；
- 淘汰只移除本地引用，不删除 Redis；下次访问会重新从 Redis 恢复；
- 应用删除时调用 `evict(appId)`，同时清理本地缓存和 Redis key；
- AI 生成失败时也会清理记忆，下一轮从数据库历史重建，避免失败轮次污染上下文。

## 4. Redis key 一览

| Key | 类型 | TTL | 说明 |
| --- | --- | --- | --- |
| `zgq:ai-code-mother:session:sessions:{sessionId}` | Hash | 7 天 | Spring Session 登录态，包含 `sessionAttr:user_login`（仅 userId） |
| `zgq:ai-code-mother:chat-memory:{appId}` | String | 7 天 | LangChain4j 消息窗口 JSON，默认最多 100 条 |

Session key 由 Spring Session 自动维护；对话记忆 key 在每次 AI 回复后刷新 TTL。

## 5. 验证方式

启动 MySQL 和 Redis 后运行应用：

```powershell
mvn spring-boot:run
```

登录并检查 Cookie 与 Redis：

```powershell
curl.exe -i -c tmp/session-cookies.txt -X POST 'http://127.0.0.1:8123/api/user/login' `
  -H 'Content-Type: application/json' `
  --data-binary '{"userAccount":"yourAccount","userPassword":"yourPassword"}'

redis-cli keys 'zgq:ai-code-mother:session:sessions:*'
```

响应头应包含：

```text
Set-Cookie: ZGQ_SESSION=...; Max-Age=604800; Path=/api; HttpOnly; SameSite=Lax
```

重启应用后，用同一个 Cookie 调用 `GET /api/user/get/login`，仍应返回 `code: 0`。

对话两轮后检查记忆：

```powershell
redis-cli exists 'zgq:ai-code-mother:chat-memory:{appId}'
redis-cli ttl 'zgq:ai-code-mother:chat-memory:{appId}'
```

删除应用后该 key 应被自动删除。

## 6. 常见问题：登录接口返回 500

如果登录返回：

```json
{"code":50000,"data":null,"message":"Redis 连接失败，请确认 Redis 已启动（默认 localhost:6379）"}
```

说明登录代码本身没有问题，而是 Spring Session 在登录时创建 Session 并写入 Redis 失败。注册接口只访问 MySQL，所以通常仍然正常，这就是判断“登录代码问题”还是“Redis 问题”的关键区别。

处理方式：

```powershell
redis-cli ping
# 期望返回 PONG

# 使用项目自带的本地启动脚本（默认读取 ..\tools\redis）
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-redis.ps1
```

启动 Redis 后不需要重启应用，Lettuce 会自动重连，再次登录即可。若使用远程 Redis，检查 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` 和网络/安全组。

## 7. 常见问题：AI 流式输出中途 network error

症状：带对话记忆的第二次追问（例如“在好看一点”）输出到 CSS 中途，前端显示 `network error`，不能继续生成。

原因：

1. 第二轮请求会把上一轮完整的 HTML/CSS/JS 放入上下文，模型倾向于重新输出全部三个文件；
2. 原 `max-tokens` 为 8192，完整重写会在 CSS 中途达到输出上限，JSON 字符串没有闭合；
3. `CodeJsonParser` 解析失败时，SSE 响应已经提交，无法再返回普通 JSON 错误体，连接被关闭，浏览器只能显示 `network error`。

修复：

- 流式模型 `max-tokens` 从 8192 提升到 16384；
- 多文件系统提示词增加长度控制，要求优先保证 JSON 完整闭合和四个字段齐全；
- `AppController` 在流式异常时发送 SSE `event:error`，前端展示可读错误，不再直接断连；
- `CodeJsonParser` 的错误提示明确说明“可能达到输出长度上限”。

验证：首次生成后追问“在好看一点”可以正常收到 `event:done`；把 `max-tokens` 强制调低到 200 时，客户端收到 `event:error` 而不是 network error。

如果页面非常复杂、仍然达到 16K 上限，可以要求 AI 精简页面，或拆分成多次生成。相关配置：

```yaml
langchain4j:
  open-ai:
    streaming-chat-model:
      max-tokens: 16384
```

## 8. 多实例注意事项

- Spring Session 天然支持多实例，所有实例共享 Redis 即可。
- 对话记忆的 Redis 数据是持久化事实来源，本地 `ChatMemory` 只是热点缓存。若同一应用的高并发请求会落到不同实例，可能出现两个实例各自覆盖消息窗口的情况。此时建议按 `appId` 做粘性路由，或关闭本地缓存让每次请求都从 Redis 重建记忆窗口。
- 应用删除和 AI 失败清理都是尽力而为：Redis 清理失败只记录日志，不阻断数据库删除，残留 key 会按 TTL 自然过期。

## 9. 相关测试

- `SpringRedisChatMemoryStoreTest`：key 前缀、TTL、序列化往返、脏数据降级、空窗口删除。
- `ConversationMemoryCacheTest`：Redis 优先、MySQL 历史回源与失败消息过滤、LRU 淘汰、删除清理。
- `UserServiceImplTest`：登录轮换 Session、只保存 userId、注销失效 Session、旧格式兼容。
- `ChatHistoryServiceImplTest`：最近消息回源的正序返回与参数校验。
- `GlobalExceptionHandlerTest`：Redis 连接失败时返回可操作的提示信息。
- `AppControllerChatStreamTest`：成功流以 `done` 结束；失败流返回 `error` 事件而不是直接断开连接。

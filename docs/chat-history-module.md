# ChatHistory 模块

对话历史通过 `chat_history` 表保存，`messageType` 使用 `user` 和 `ai` 两种值。AI 生成异常会作为 AI 消息保存，内容以“AI 回复失败：”开头并附带异常信息。

## 接口

接口遵循项目统一的响应格式 `BaseResponse`，使用现有 Session 登录态。

| 方法 | 路径 | 权限 | 请求与用途 |
| --- | --- | --- | --- |
| POST | `/api/chatHistory/list/page` | 应用创建者或管理员 | `appId` 必填，`pageNum` 从 1 开始；每页固定 10 条，按聊天展示顺序返回 |
| POST | `/api/chatHistory/list/cursor` | 应用创建者或管理员 | 游标分页，`appId` 必填，`pageSize` 取值 1 到 50；首次查询不传游标 |
| POST | `/api/chatHistory/admin/list/page` | 管理员 | 全局分页，按 `createTime`、`id` 降序；可选按 `appId`、`userId`、`messageType` 筛选 |

打开应用页面时先调用游标接口读取最新一页。第一页为空时前端才需要发送应用初始化提示词；存在记录时直接展示已保存消息。消息列表按 `createTime`、`id` 升序展示，超过 10 条时通过“加载更多历史消息”继续向前加载。

### 游标分页

游标由 `createTime` 和雪花 `id` 组成，用于稳定读取更早的消息：

```json
{
  "appId": "1234567890123456789",
  "cursorCreateTime": "2026-11-08T14:30:00",
  "cursorId": "1234567890123456790",
  "pageSize": 10
}
```

响应中的 `records` 按聊天展示顺序（旧到新）排列。`hasMore` 为 true 时，把 `nextCursorCreateTime` 和 `nextCursorId` 原样传回即可继续读取更早消息：

```json
{
  "records": [],
  "hasMore": true,
  "nextCursorCreateTime": "2026-11-08T14:30:00",
  "nextCursorId": "1234567890123456790"
}
```

`nextCursorId` 使用字符串承载雪花 id，避免 JavaScript `number` 精度丢失。后端先按 `createTime DESC, id DESC` 多取一条判断 `hasMore`，再翻转当前页返回，因此并发插入新消息不会让旧页重复或漏读。

### 管理员查询与页面

管理员全局查询支持组合筛选应用、用户和消息类型。前端入口为 `/admin/chatHistoryManage`，顶部菜单名称为“对话管理”；应用管理页的“对话”按钮会带上 `?appId=...` 直接定位到对应应用的消息。管理页提供消息内容预览、完整内容弹窗和打开所属应用的入口。

应用对话页只在自己的历史为空时自动发送初始化提示词，不再依赖 URL 中的 `view` 或 `generate` 参数；已有至少 2 条历史记录时会探测并展示已生成的网站。

普通用户只能读取自己创建的应用历史；管理员可读取指定应用历史，也可通过全局接口监管全部应用。通用未鉴权的历史新增、修改、删除和详情接口已移除。

## 持久化和删除

代码生成流实际订阅时先记录用户消息。流正常完成后保存完整 AI 输出；流发生错误时保存包含错误详情的 AI 消息。应用删除会在同一事务内逻辑删除应用及其全部历史，常规历史查询因此不会返回已删除应用的数据。

## 与对话记忆的关系

数据库中的 `chat_history` 是完整历史，Redis 中的对话记忆只保存最近一个消息窗口。Redis 为空时，`ConversationMemoryCache` 调用 `ChatHistoryService.listRecentMessages` 回源最近 `max-messages` 条消息（默认 100，最多 200 条），跳过 `AI 回复失败：` 开头的占位消息后恢复上下文。应用删除时同时清理 Redis 记忆，具体见 [redis-session-and-memory.md](./redis-session-and-memory.md)。

## 数据表

新环境需执行 `sql/create_table.sql` 中的 `chat_history` 建表语句；已有环境需先检查字段及索引。表的主键由实体配置的雪花算法生成，不使用自增 id。`(appId, createTime, id)` 复合索引同时服务固定分页和游标分页。

## 测试

- `ChatHistoryServiceImplTest` 覆盖游标页翻转、`hasMore` 与下一游标、非法游标及管理员筛选参数校验。
- `ChatHistoryControllerTest` 覆盖应用归属校验、游标参数校验、管理员查询委托和 `@AuthCheck` 管理员权限标记。
- 前端 `npm run type-check` 校验游标请求、管理员查询页面和路由类型。

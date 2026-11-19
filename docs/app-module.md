# App 模块接口

接口前缀为 `/api/app`，响应沿用 User 模块的 `BaseResponse`。用户接口需要登录，使用已有的 Session 登录态；管理员接口复用 `@AuthCheck`。

| 方法 | 路径 | 权限 | 请求与用途 |
| --- | --- | --- | --- |
| POST | `/add` | 登录用户 | `initPrompt` 必填，返回新应用 id |
| POST | `/update` | 应用创建者 | `id`、`appName` 必填，只修改名称 |
| POST | `/delete` | 应用创建者或管理员 | `id` 必填，管理员可删除任意应用 |
| GET | `/get/vo?id=...` | 登录用户 | 返回应用详情及创建者的 `UserVO` |
| POST | `/my/list/page/vo` | 登录用户 | 分页查询自己的应用，可按 `appName` 模糊查询 |
| POST | `/good/list/page/vo` | 登录用户 | 分页查询精选应用，可按 `appName` 模糊查询 |
| POST | `/admin/update` | 管理员 | `id` 必填，可选更新 `appName`、`cover`、`priority`，至少提供一项 |
| POST | `/list/page` | 管理员 | 按所有非时间字段分页查询，返回实体列表 |
| GET | `/get?id=...` | 管理员 | 返回应用实体详情 |
| GET | `/download/{appId}` | 应用创建者 | 下载过滤后的源代码 ZIP，仅创建者可下载 |

## 创建与更新

创建请求示例：

```json
{
  "initPrompt": "创建一个用于展示个人作品的网页"
}
```

应用名默认取去除首尾空白后的提示词前 12 个字符，代码生成类型由 AI 智能路由自动选择，默认优先级为 `0`。创建者由服务端登录态确定。创建接口只保存应用信息。

`initPrompt` 最多 10000 个字符，应用名称不能为空且最多 256 个字符，封面最多 1024 个字符。管理员更新封面时传入空字符串可清空封面，未提供的字段保留原值。用户和管理员修改时均更新 `editTime`。删除使用 MyBatis-Flex 逻辑删除，并在同一事务内逻辑删除该应用的全部对话历史，避免留下无法归属的历史数据。

精选应用的优先级为 `99`，管理员通过 `/admin/update` 设置 `priority: 99` 加入精选，改为其他值取消精选。相关默认值集中在 `AppConstant`。

## 智能路由与生成类型

创建应用时，`AppServiceImpl` 调用 `AiCodeGenTypeRoutingService`，把用户提示词交给独立的路由模型分类，返回 `CodeGenTypeEnum` 并写入 `app.codeGenType`。路由使用 LangChain4j 结构化输出，方法直接返回枚举，不需要手写 JSON 解析：

- 系统提示词：`resources/prompt/codegen-routing-system-prompt.txt`
- 路由服务：`AiCodeGenTypeRoutingService`
- AI Service 装配：`AiCodeGenTypeRoutingServiceConfig`
- 路由模型：`langchain4j.open-ai.routing-chat-model`

路由模型与代码生成模型分离，配置了较低的输出上限、`temperature=0` 和较短超时，生产环境可通过 `ROUTING_AI_MODEL_NAME`、`ROUTING_AI_BASE_URL`、`ROUTING_AI_API_KEY` 换成更便宜、更快的分类模型。该模型**不能**设置 `response-format: json_object/json_schema`，否则 LangChain4j 会改用 JSON 结构化输出，与枚举的纯文本格式指令冲突。

路由失败（模型超时、限流、返回空值或无法解析）不会阻塞应用创建，服务层会记录告警并回退到 `html` 模式。`AppVO` 同时返回 `codeGenType` 和可直接展示的 `codeGenTypeText`，前端据此在应用详情页标题右侧、应用详情弹窗及编辑页展示生成类型标签。

## 分页与查询

分页使用 `pageNum`（默认 1）、`pageSize`（默认 10）、`sortField`、`sortOrder`（`ascend` 或 `descend`）。普通用户的两个列表接口要求 `1 <= pageSize <= 20`，筛选范围由服务端固定，只接受名称查询和分页排序参数。管理员每页数量无业务上限，但页码、每页数量必须为正整数。

管理员支持 `id`、`appName`、`cover`、`initPrompt`、`codeGenType`、`deployKey`、`priority`、`userId`、`isDelete`。`appName`、`initPrompt` 为模糊查询，其他字段为精确查询；不提供 `isDelete` 时默认排除已删除应用，显式传 `isDelete: 1` 可查询已删除记录，此能力仅用于管理员列表。

默认按 `createTime`、`id` 降序排列。排序字段限定为 App 实体的实际字段。列表批量加载创建者并转为 `UserVO`，避免逐条查询及暴露密码。

## 数据表

`sql/create_table.sql` 已补充与现有 App 实体一致的建表语句。新环境需在配置的 MySQL 数据库中执行 App 建表部分；已有 App 表的环境请先核对字段。本次代码生成没有执行数据库脚本。

## 部署地址与封面

部署成功后返回的站点地址由后端环境变量 `APP_DEPLOY_HOST` 指定，值为部署静态站点的根域名（例如 `https://apps.example.com`），默认值为 `http://localhost`。应用部署目录仍由后端运行环境提供。

部署目录按生成类型选择：HTML/多文件模式直接复制生成目录；Vue 工程只复制构建产物 `dist`，因为源码入口 `index.html` 引用 `/src/main.js`，浏览器无法直接执行 `.vue` 文件和裸模块导入。`dist` 尚未生成时部署接口返回“尚未构建完成”，不会把开发入口发布出去。

部署完成会异步访问部署地址生成网页截图，上传腾讯云 COS 后把图片 URL 写入应用封面；截图失败不影响部署结果，也不阻塞部署接口。截图驱动、COS 配置、封面尺寸和测试方式见 [screenshot-module.md](./screenshot-module.md)。

## 代码下载

`GET /api/app/download/{appId}` 读取 `tmp/code_output/{codeGenType}_{appId}` 生成目录，排除依赖、构建产物和敏感文件后，以 ZIP 附件直接写入 HTTP 响应。只有应用创建者可以下载，管理员也不能越权。目录过滤规则、响应头和前端下载流程见 [download-module.md](./download-module.md)。

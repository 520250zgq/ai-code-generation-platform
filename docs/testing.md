# 后端测试

常规测试无需启动数据库或配置真实模型：

```powershell
mvn test
```

`AiCodeGeneratorFacadeTest`、`AiCodeGeneratorServiceTest` 和
`GenerateAndSaveCodeStreamTest` 会调用真实模型，默认跳过。配置好模型后显式启用：

```powershell
mvn '-Dai.integration.enabled=true' '-Dtest=AiCodeGeneratorFacadeTest,AiCodeGeneratorServiceTest,GenerateAndSaveCodeStreamTest' test
```

`AiServiceContextTest` 只启动 AI 配置上下文，验证带 `@MemoryId` 的服务代理能装配
`chatMemoryProvider`，并验证服务缓存键包含 `codeGenType`，不调用真实模型。

## 智能路由测试

```powershell
mvn '-Dtest=AiCodeGenTypeRoutingServiceTest,AiModelConfigTest,AppServiceImplTest,AppControllerTest,AiServiceContextTest' test
```

- `AiCodeGenTypeRoutingServiceTest` 用 Mockito 模型验证路由提示词、枚举格式约束，以及纯文本/JSON 两种枚举响应的解析。
- `AiModelConfigTest` 锁定路由模型不声明 JSON Schema 能力，避免后续误加 `response-format` 导致枚举解析失败。
- `AppServiceImplTest` 覆盖路由成功落库、路由异常回退 `html`、路由返回空值回退，以及 `codeGenTypeText` 转换。
- `AppControllerTest` 覆盖创建接口把已认证用户委托给服务层，以及空提示词在进入服务前被拒绝。
- `AiServiceContextTest` 额外验证 `AiCodeGenTypeRoutingService` 能从 Spring 容器装配。

前端生成类型标签改动后执行 `npm run type-check` 和 `npm run build-only`。

## Vue 工程生成单元测试

```powershell
mvn '-Dtest=FileWriteToolTest,FileReadToolTest,FileDirReadToolTest,FileModifyToolTest,FileDeleteToolTest,ToolManagerTest,CodeGenPromptContractTest,AiCodeGeneratorServiceFactoryTest,JsonMessageStreamHandlerTest,VueProjectBuilderTest,AiCodeGeneratorFacadeVueStreamTest,AiCodeGeneratorServiceContractTest' test
```

- `FileWriteToolTest` 验证按 appId 写入 `vue_project_{appId}`、创建父目录、覆盖写入、UTF-8 内容，以及拒绝绝对路径、盘符和 `..` 穿越；工具返回值只能包含相对路径。
- `FileReadToolTest` 验证读取相对路径和内容、空文件、工程目录前缀兼容、超长内容截断，以及拒绝穿越路径和非法 appId。
- `FileDirReadToolTest` 验证递归目录树、文件/目录数量、子目录读取、`node_modules`/`dist` 过滤，以及拒绝穿越路径。
- `FileModifyToolTest` 验证唯一替换、空新内容删除片段、找不到旧内容/多义旧内容时拒绝且不修改文件、新旧内容相同的拒绝、长度与路径安全。
- `FileDeleteToolTest` 验证删除单个文件、缺失文件幂等、目录保护，以及拒绝穿越路径。
- `ToolManagerTest` 验证全部工具自动注册、名称去重、按名称分发、未知工具兜底、绝对路径脱敏和只展示首行摘要。
- `CodeGenPromptContractTest` 锁定三套提示词中的元素信息块格式、原生模式全量重写和 Vue 模式的工具清单/先读后改约束。
- `AiCodeGeneratorServiceFactoryTest` 验证缓存键包含 appId 与 codeGenType、HTML 使用通用流式模型、Vue 使用推理流式模型并携带全部 5 个工具、`@ToolMemoryId` 能把 appId 注入工具。
- `JsonMessageStreamHandlerTest` 验证各工具按自身策略输出进度、模型正文一起流式返回、工具调用超限时终止、上游异常正确传递、取消订阅不触发构建，以及流完成后提交异步构建任务。
- `VueProjectBuilderTest` 验证 Windows/Unix 命令构造、命令退出码判断、安装/打包步骤编排、`dist` 产物校验、异常降级为 `false`、Java 21 虚拟线程异步构建，以及构建期间的新请求排队并在首轮结束后自动重建。
- `AiCodeGeneratorFacadeVueStreamTest` 验证 Vue 分支把 `TokenStream` 委托给 `JsonMessageStreamHandler`。
- `AiCodeGeneratorServiceContractTest` 通过反射锁定 `@MemoryId`、`@SystemMessage`、`@Tool`、`@P`、`@ToolMemoryId` 注解、工具名与模型可见参数集合。
- `StaticResourceControllerTest` 额外覆盖 Vue 工程：dist 存在时优先提供构建产物，dist 未生成时返回自动刷新的构建提示页，且不会直接暴露源码入口。

`AiCodeGeneratorServiceTest#generateVueProjectCodeStream` 是真实模型集成测试，开启
`ai.integration.enabled` 后才会运行，会验证 Vue 工程文件和流式响应中均不泄露绝对路径。

生成并保存代码的测试使用独立的应用 ID，避免覆盖现有应用文件。
流式集成测试会验证响应解析结果及 HTML、CSS、JavaScript 文件内容，并设置三分钟等待上限。
服务集成测试的响应保存在 `target/ai-results`，Facade 集成测试的生成文件保存在
`tmp/code_output/{type}_{appId}`。

## 可视化编辑测试

前端可视化编辑逻辑集中在 `src/utils/visualEditor.ts`，使用模拟数据验证，不依赖浏览器和后端：

```powershell
cd zgq-ai-code-mother-frontend
npm run test:visual-editor
npm run type-check
npm run build
```

- `scripts/test-visual-editor.mjs` 覆盖元素信息块格式、提示词拼接、无选中元素时的原样返回、文本/属性截断、Alert 紧凑描述、`postMessage` 数据结构校验，以及注入脚本自包含（不引用父页面模块变量）和语法有效。
- 同源、注入和交互的端到端行为需要在浏览器中验证：启动后端和前端后进入应用对话页，确认按钮位置、悬浮边框、点击固定边框、Alert 展示/移除、发送后退出编辑模式。详细排查见 [visual-editing.md](./visual-editing.md)。
- 修改原生 HTML / 多文件 / Vue 工程的完整规则见 [incremental-modification.md](./incremental-modification.md)。

## 对话历史测试

`ChatHistoryServiceImplTest` 覆盖游标分页的页内顺序、`hasMore` 与下一游标、非法游标，以及管理员筛选参数校验。
`ChatHistoryControllerTest` 覆盖应用归属校验、游标参数校验、管理员查询委托和管理员权限标记。
`AppServiceImplTest` 覆盖应用删除时调用历史关联删除，以及生成成功或失败时保存用户消息和 AI 消息。

## 应用代码下载测试

```powershell
mvn '-Dtest=CodeDownloadUtilsTest,AppServiceImplDownloadTest,AppControllerTest' test
```

- `CodeDownloadUtilsTest` 覆盖路径段过滤（不误伤 `src/builder` 等正常源码）、`node_modules`/`dist`/`.env`/日志/密钥排除、下载文件名清理、ZIP 相对路径和内容。
- `AppServiceImplDownloadTest` 覆盖创建者权限、管理员不可越权、应用或生成目录缺失、过滤后无源码、中文文件名响应头以及 ZIP 内容。
- `AppControllerTest` 覆盖下载接口的参数校验、未登录拒绝和登录用户委托。

前端下载入口改动后同样执行 `npm run type-check` 和 `npm run build`。详细规则见 [download-module.md](./download-module.md)。

## Redis Session 与对话记忆测试

```powershell
mvn '-Dtest=SpringRedisChatMemoryStoreTest,ConversationMemoryCacheTest,UserServiceImplTest' test
```

- `SpringRedisChatMemoryStoreTest` 使用 Mockito 模拟 `StringRedisTemplate`，验证 key 前缀、TTL、序列化往返、脏数据降级和删除。
- `ConversationMemoryCacheTest` 使用内存版 `ChatMemoryStore`，验证 Redis 优先、MySQL 历史回源、失败消息过滤、LRU 淘汰和删除清理。
- `UserServiceImplTest` 验证登录轮换 Session、Session 只保存 userId、注销失效和旧格式兼容。
- `AppControllerChatStreamTest` 验证 SSE 成功流以 `done` 结束，失败流发送 `error` 事件而不是直接断开。

上述测试都不需要启动 Redis 或 MySQL。完整的“重启后 Session 仍有效、对话记忆可恢复”验证步骤见
[redis-session-and-memory.md](./redis-session-and-memory.md)。

前端在对话历史相关改动后执行：

```powershell
cd zgq-ai-code-mother-frontend
npm run type-check
npm run build
```

## 网页截图与对象存储测试

```powershell
mvn '-Dtest=WebScreenshotUtilsTest,CosManagerTest,ScreenshotServiceImplTest,AppServiceImplTest' test
```

- `WebScreenshotUtilsTest` 覆盖截图保存、JPEG 压缩、`document.readyState` 等待、真实无头 Chrome 截图，以及页面引用永远不会完成的子资源时仍能完成截图；没有可用浏览器驱动时，真实截图用例自动跳过。
- `CosManagerTest` 模拟 COS SDK，验证对象 key、图片元数据、公共读 ACL、自定义域名、上传失败和客户端关闭，不会访问真实对象存储。
- `ScreenshotServiceImplTest` 覆盖网址协议校验、截图上传编排、上传失败时本地文件清理，以及上传结果为空时的错误处理。
- `AppServiceImplTest` 新增部署后异步触发截图、封面更新、空截图地址和截图失败降级用例；异步断言使用 Mockito `timeout` 等待虚拟线程完成。

真实截图测试要求本机安装 Chrome。Selenium Manager 默认自动下载匹配的 ChromeDriver；如果网络无法访问 Google 的驱动地址，可以提前安装驱动并通过 `-Dwebdriver.chrome.driver=...` 指定，或把驱动放入 Selenium 缓存目录。详细说明见 [screenshot-module.md](./screenshot-module.md)。

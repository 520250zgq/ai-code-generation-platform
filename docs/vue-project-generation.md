# Vue 工程生成与流式工具调用

本文对应需求：用 LangChain4j 工具调用生成 Vue 工程、按 appId 隔离文件、按生成类型选择模型、按 appId + 类型缓存 AI Service，并解决“文件已写入但前端长时间没有输出”的流式体验问题。

## 1. 整体链路

```
AppController.chatToGenCode (SSE)
  -> AppServiceImpl.chatToGenCode
       -> ConversationMemoryCache.get(appId)                 // 预热对话记忆
       -> AiCodeGeneratorFacade.generateAndSaveCodeStream
            -> AiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, VUE_PROJECT)
                 -> AiServices + reasoningStreamingChatModel
                      + tools(ToolManager.getTools())          // 5 个文件工具
                      + chatMemoryProvider(memoryId -> memoryCache.get(appId))
            -> AiCodeGeneratorService.generateVueProjectCodeStream(@MemoryId appId, message)
                 -> 模型返回 tool_calls：创建时 writeFile，修改时 readDir / readFile / modifyFile / deleteFile
                      -> 工具写入 tmp/code_output/vue_project_{appId}
                      -> onToolExecuted 交给 ToolManager 生成一行进度并立即推送前端
                 -> 模型继续下一轮，直至输出完成
            -> JsonMessageStreamHandler 在 onCompleteResponse 中提交异步构建
                 -> VueProjectBuilder.buildProjectAsync (Java 21 虚拟线程)
                      -> npm install
                      -> npm run build
                      -> 校验 dist 目录
```

Vue 模式不使用 `CodeParserExecutor` / `CodeFileSaverExecutor`：文件由工具直接落盘，没有“解析整段文本”的步骤。HTML 和多文件模式仍然保持原有链路。

## 2. 文件工具与策略管理

`ai/tools/` 下提供五个工具，统一实现策略接口 `BaseTool`：

| 工具 | `@Tool` 方法 | 作用 |
| --- | --- | --- |
| `FileWriteTool` | `writeFile` | 创建文件或整文件覆盖 |
| `FileReadTool` | `readFile` | 读取单个文件内容 |
| `FileDirReadTool` | `readDir` | 递归读取目录结构 |
| `FileModifyTool` | `modifyFile` | 用新内容替换文件中唯一出现的旧内容 |
| `FileDeleteTool` | `deleteFile` | 删除单个文件 |

公共约束集中在 `AbstractFileTool`：

- `@ToolMemoryId Long appId` 由 LangChain4j 在工具执行时注入，不会出现在模型可见的 schema 中；
- 路径基于 `CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId` 构造，和保存器的目录约定一致；
- 只接受工程内相对路径，拒绝绝对路径、盘符、`..`、根路径和目录本身；
- 成功/失败结果都只包含相对路径，服务器绝对路径只写日志；
- 单文件限制 1,000,000 字符；读取最多返回 60,000 字符；目录最多展示 500 个条目。

`ToolManager` 在启动时收集全部 `BaseTool` Bean，按工具名建立索引，负责：

1. 向 `AiCodeGeneratorServiceFactory` 提供工具数组；
2. 按工具名把 `ToolExecution` 分发给对应策略生成流式进度；
3. 校验工具名非空、不重复，并剔除进度中的服务器存储根目录。

新增工具只需实现接口并标注 `@Component`，工厂和流式处理器无需修改。

工具 schema 经过 `ToolSpecifications.toolSpecificationFrom` 后只包含业务参数，`appId` 不会暴露给模型。

## 3. AI Service 与模型选择

`AiCodeGeneratorService` 新增：

```java
@SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
TokenStream generateVueProjectCodeStream(@MemoryId Long appId, @UserMessage String userMessage);
```

返回 `TokenStream` 而不是 `Flux<String>`，是为了拿到 `onToolExecuted` 回调；`Flux<String>` 只能转发模型正文，工具调用轮次可能完全没有正文。

`AiCodeGeneratorServiceFactory` 根据类型选择模型：

| 生成类型 | 同步模型 | 流式模型 | 工具 |
| --- | --- | --- | --- |
| HTML | `chatModel` | `openAiStreamingChatModel`（JSON response-format） | 无 |
| MULTI_FILE | `chatModel` | `openAiStreamingChatModel`（JSON response-format） | 无 |
| VUE_PROJECT | `chatModel` | `reasoningStreamingChatModel`（无 JSON 格式、输出上限独立配置） | 全部 5 个文件工具 |

`reasoningStreamingChatModel` 的配置位于 `application.yml` 的
`langchain4j.open-ai.reasoning-streaming-chat-model`，默认复用 `streaming-chat-model`
的 base-url 和 api-key；需要推理更强的模型时覆盖 `model-name` 即可。

所有服务统一使用 `chatMemoryProvider`，按 `@MemoryId` 为每个 appId 绑定会话记忆。
即使缓存中的服务实例被淘汰，记忆仍在 `ConversationMemoryCache` / Redis 中。

## 4. 服务缓存键

```java
String cacheKey = appId + ":" + codeGenType.getValue();
```

同一 appId 的 HTML、MULTI_FILE、VUE_PROJECT 使用不同代理，避免模型和工具配置串用；
缓存采用 access-order `LinkedHashMap` 实现 LRU，最多 500 个代理，淘汰不删除对话记忆。

## 5. 流式工具输出

`JsonMessageStreamHandler#handle` 把 `TokenStream` 转成 `Flux<String>`：

- `onPartialResponse`：实时转发模型正文；
- `onToolExecuted`：把 `ToolExecution` 交给 `ToolManager`，由对应工具生成首行摘要，例如
  `> 📄 文件写入成功：src/App.vue`、`> ✏️ 文件修改成功：src/App.vue（替换 1 处）`；
- `onCompleteResponse`：完成 SSE；
- `onError`：把模型异常传给订阅者，由 Controller 转成 SSE `event:error`。

这样即使模型在工具调用轮次没有正文，前端也会在每个工具执行后立刻看到进度，而不是等全部文件写完才收到第一条消息。读取工具只展示文件路径和字符数，不会把完整文件内容推到聊天区。

## 6. 构建 Vue 项目

Vue 工程生成完成后还需要安装依赖和打包，构建逻辑集中在
`core/builder/VueProjectBuilder.java`：

| 方法 | 作用 |
| --- | --- |
| `buildProjectAsync(String)` | 使用 Java 21 `Thread.ofVirtual()` 在虚拟线程中启动构建，立即返回；同一工程已有构建任务时不并发执行，而是把请求登记到 `pendingBuilds`，当前构建结束后自动重建 |
| `buildProject(String)` | 校验工程目录和 `package.json`，依次执行安装、打包，并确认 `dist` 目录生成；任何失败返回 `false` |
| `executeNpmInstall(File)` | 执行 `npm install`，超时 5 分钟 |
| `executeNpmBuild(File)` | 执行 `npm run build`，超时 3 分钟 |
| `isWindows()` | 通过 `os.name` 判断操作系统 |
| `buildCommand(String, String...)` | Windows 下将 `npm` 转换为 `npm.cmd`，Unix 下保持 `npm` |
| `executeCommand(File, List<String>, int)` | 通用命令执行：合并 stdout/stderr、后台读取输出避免管道阻塞、支持超时强杀、异常统一返回 `false` |

构建失败只记录日志，不向 SSE 主流程抛异常。Windows 下 `.cmd` 命令会通过
`cmd.exe /c` 执行，避免 `CreateProcess error=193`。

`JsonMessageStreamHandler#onCompleteResponse` 在流正常完成且订阅未取消时调用：

```java
vueProjectBuilder.buildProjectAsync(FileWriteTool.projectRoot(appId).toString());
```

因此前端可以立即收到生成完成，构建在后台继续执行；构建完成后工程目录下会生成 `dist`。

构建期间如果用户又完成一轮增量修改，新的构建请求会排队而不是被丢弃：当前构建结束后会基于最新源码再构建一次，确保预览不停留在旧版本。构建循环的“检查排队 + 释放占用”在同一把调度锁内完成，不会并发构建同一工程。

## 7. 防止无限工具循环

1. **幻觉工具名**：工厂配置 `hallucinatedToolNameStrategy`，非流式路径会返回“不存在该工具”的可读错误，让模型自行纠正，而不是抛异常。
2. **工具次数上限**：工厂配置 `maxSequentialToolsInvocations(20)`；由于 LangChain4j 1.1.0 的流式工具循环不执行该限制，`JsonMessageStreamHandler` 在 `onToolExecuted` 中独立计数，超过 40 次立即 `sink.error` 并抛出内部异常中止循环。
3. **扩大记忆容量**：`app.chat-memory.max-messages` 从 20 提升到 100，避免模型忘记已写文件而反复重写。
4. **更换模型配置**：Vue 使用独立的 `reasoningStreamingChatModel`，可通过配置切换更强的模型。
5. **优化提示词**：明确“只能使用 readDir / readFile / modifyFile / writeFile / deleteFile”“修改前必须先读取文件”“每个文件只写一次”“写完立即停止调用工具”“禁止绝对路径”等约束。

## 8. 测试

常规单元测试不调用真实模型：

```powershell
mvn '-Dtest=FileWriteToolTest,FileReadToolTest,FileDirReadToolTest,FileModifyToolTest,FileDeleteToolTest,ToolManagerTest,CodeGenPromptContractTest,AiCodeGeneratorServiceFactoryTest,JsonMessageStreamHandlerTest,VueProjectBuilderTest,AiCodeGeneratorFacadeVueStreamTest,AiCodeGeneratorServiceContractTest' test
```

完整回归：

```powershell
mvn test
```

真实模型集成测试需要显式开启：

```powershell
mvn '-Dai.integration.enabled=true' '-Dtest=AiCodeGeneratorServiceTest' test
```

## 9. 已知边界

- LangChain4j 1.1.0 的流式工具循环没有应用 `maxSequentialToolsInvocations` 和
  `hallucinatedToolNameStrategy`，因此 `JsonMessageStreamHandler` 增加了独立的次数兜底；
  升级 LangChain4j 后可以删除该兜底并复用框架能力。
- 构建产物位于 `vue_project_{appId}/dist`。`StaticResourceController` 在 dist 生成后自动切换为
  从 dist 提供预览；构建完成前返回会自动刷新的“构建中”页面。
- 部署接口根据生成类型选择部署目录：HTML/多文件模式复制生成目录，Vue 工程只复制 `dist`。
  直接部署 Vue 源码会把 Vite 开发入口 `index.html`（`/src/main.js`）暴露给浏览器，页面会因
  脚本无法加载而空白；dist 尚未生成时部署接口会返回“尚未构建完成”，避免发布半成品。
- 增量修改依赖模型正确调用文件工具；如果模型只输出说明而不调用工具，源码不会变化。提示词中的
  “先读后改”“使用工具修改”和工具次数上限用于降低该风险，完整规则见
  [incremental-modification.md](./incremental-modification.md)。
- 构建会在服务器上执行 AI 生成项目的 npm 命令。安装依赖已使用 `--ignore-scripts` 禁止
  依赖包生命周期脚本，但 `vite.config.js` 和 `npm run build` 仍会执行生成项目的代码；
  生产环境应把构建放到隔离容器或受限账号中运行，避免不受信任代码影响宿主机。

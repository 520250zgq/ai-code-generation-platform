# 网站修改：原生应用全量修改与 Vue 工程增量修改

本文对应需求：用户选中页面元素后提出修改要求，后端调用 AI 完成修改。修改分两条路径：

| 生成类型 | 修改方式 | 原因 |
| --- | --- | --- |
| HTML | 重新输出完整 HTML | 单文件、代码量小，全量返回最稳定 |
| MULTI_FILE | 重新输出完整 HTML + CSS + JavaScript | 三文件总量可控，避免只返回片段导致文件不完整 |
| VUE_PROJECT | 通过工具读取、修改、创建、删除单个文件 | 工程文件多、代码量大，全量重写既慢又容易超出输出上限 |

## 1. 通用链路

```text
AppChatPage.sendMessage()
  -> composePromptWithElement()             // 选中元素信息追加到提示词
  -> GET /api/app/chat/gen/code (SSE)
       -> AppServiceImpl.chatToGenCode
            -> AiCodeGeneratorFacade.generateAndSaveCodeStream
                 -> HTML / MULTI_FILE：模型返回完整代码文本 -> 解析 -> 保存
                 -> VUE_PROJECT：模型调用文件工具 -> 工具落盘 -> 流结束后异步构建
  -> 前端收到 done 后刷新 iframe 预览
```

后端不解析“元素信息块”的业务含义，只把完整用户消息交给模型；元素信息由前端格式化，三套系统提示词负责解释其含义和修改约束。

## 2. 原生应用全量修改

### 2.1 提示词优化

`codegen-html-system-prompt.txt` 和 `codegen-multi-file-system-prompt.txt` 增加“修改模式要求”：

1. 只修改与用户要求相关的元素、样式或交互；
2. 根据选择器、标签、文本、位置综合定位，不能臆造页面中不存在的内容；
3. 必须输出完整文件，而不是只输出被修改的片段；
4. HTML 模式最多 1 个代码块；多文件模式最多 HTML、CSS、JavaScript 各 1 个代码块；
5. 删除元素时同时清理对应样式和脚本，不能留下无效引用。

### 2.2 解析与保存

修改请求复用原有生成链路：

- `AiCodeGeneratorService.generateHtmlCodeStream` / `generateMultiFileCodeStream`；
- `AiCodeGeneratorFacade.processCodeStream` 累计完整响应；
- `CodeParserExecutor` 提取代码块；
- `CodeFileSaverExecutor` 覆盖保存到 `html_{appId}`、`multi_file_{appId}`。

因此全量修改不需要额外工具，也不需要改动保存器。前端收到 `done` 后重新加载预览。

### 2.3 注意事项

- 全量返回要求模型输出完整代码，提示词必须明确“不能只输出修改片段”。
- 只输出一个完整代码块可以避免解析器误取片段；`CodeParserExecutor` 会拒绝不符合格式的响应。
- 修改不相关的元素会覆盖用户已有内容，提示词中必须强调“只改相关部分”。
- 生成失败或用户停止时不会触发预览刷新，避免展示半成品。

## 3. Vue 工程增量修改

### 3.1 工具清单

| 工具 | 方法 | 模型可见参数 | 作用 | 返回给模型的结果 |
| --- | --- | --- | --- | --- |
| `FileDirReadTool` | `readDir` | `relativeDirPath` | 递归查看工程结构 | 目录路径 + 文件树 + 文件/目录数量 |
| `FileReadTool` | `readFile` | `relativeFilePath` | 读取单个文件 | 文件路径 + 文件内容（过长截断） |
| `FileModifyTool` | `modifyFile` | `relativeFilePath`、`oldContent`、`newContent` | 用新内容替换唯一旧内容 | 路径 + 新旧内容短摘要 |
| `FileWriteTool` | `writeFile` | `relativeFilePath`、`content` | 创建新文件或整文件覆盖 | 文件相对路径 |
| `FileDeleteTool` | `deleteFile` | `relativeFilePath` | 删除单个文件 | 文件相对路径 |

所有方法的 `appId` 都使用 `@ToolMemoryId` 注入，不会出现在模型可见的 schema 中；工具只能操作 `tmp/code_output/vue_project_{appId}` 内的文件。

### 3.2 策略结构

```text
BaseTool（策略接口）
  getToolName()
  generateToolExecutedResult(ToolExecution)
        ▲
AbstractFileTool（抽象基类：路径校验、工程根目录、进度格式化）
        ▲
FileWriteTool / FileReadTool / FileDirReadTool / FileModifyTool / FileDeleteTool
        ▲
ToolManager（策略工厂：按 Spring Bean 自动注册，按名称分发）
        ▲
AiCodeGeneratorServiceFactory  ── tools(toolManager.getTools())
JsonMessageStreamHandler       ── toolManager.generateToolExecutedResult(...)
```

新增工具只需要：

1. 继承 `AbstractFileTool`，实现 `@Tool` 方法和 `getToolName()`；
2. 标注 `@Component`；
3. 在 `TestAiMemoryConfig` / 测试上下文中注册；运行期由 Spring 自动收集。

工厂和流式处理器都不需要修改。

### 3.3 路径安全

公共校验集中在 `AbstractFileTool`：

- 只接受工程内相对路径，拒绝绝对路径、盘符、以 `/` 开头和包含 `..` 的路径；
- 路径归一化后必须仍以工程根目录开头；
- 文件工具不允许指向工程根目录本身，目录工具允许空字符串和 `.`；
- 兼容模型传入的 `./`、Windows 反斜杠和 `vue_project_{appId}/` 前缀；
- 返回给模型的内容只包含相对路径，服务器绝对路径只写日志；
- 单文件内容上限 1,000,000 字符，读取最多返回 60,000 字符，目录最多展示 500 个条目、递归 8 层。

### 3.4 工具管理器与流式输出

`ToolManager` 在构造时校验工具名非空且不重复，并按名称排序，保证每次绑定给模型的工具顺序稳定。

`JsonMessageStreamHandler.onToolExecuted` 不再硬编码“文件写入成功”格式，而是：

```java
String progress = toolManager.generateToolExecutedResult(toolExecution);
```

各工具只输出首行摘要：

| 工具 | 示例进度 |
| --- | --- |
| readDir | `> 📂 目录读取成功：src，共 3 个文件，2 个目录` |
| readFile | `> 📖 文件读取成功：src/App.vue（1200 字符）` |
| modifyFile | `> ✏️ 文件修改成功：src/App.vue（替换 1 处）` |
| writeFile | `> 📄 文件写入成功：src/App.vue` |
| deleteFile | `> 🗑️ 文件删除成功：src/Old.vue` |
| 未注册工具 | `> 🔧 工具名：结果首行` |

读取和修改工具不会把完整文件内容或新旧内容全文推到聊天区，避免 SSE 帧过大和对话记录膨胀。`ToolManager` 还会再次剔除服务器存储根目录，作为兜底保护。

### 3.5 AI 绑定工具

`AiCodeGeneratorServiceFactory` 只为 `VUE_PROJECT` 绑定工具：

```java
builder.tools((Object[]) toolManager.getTools())
       .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(
               request, "工具调用错误：不存在名为 ... 的工具。只能使用以下工具：" + toolManager.getToolNames()))
       .maxSequentialToolsInvocations(20);
```

- HTML / MULTI_FILE 不绑定工具，保持 JSON/文本输出；
- Vue 模式使用推理流式模型，允许连续工具调用；
- 流式路径由 `JsonMessageStreamHandler` 独立限制单轮最多 40 次工具执行，超过后终止并返回可读错误。

### 3.6 Vue 修改流与构建

1. 模型先调用 `readDir` 了解结构，再调用 `readFile` 读取目标文件；
2. 使用 `modifyFile` 做局部替换，必要时用 `writeFile` 整文件重写；
3. 不再使用的文件用 `deleteFile` 清理；
4. 流正常完成后，`JsonMessageStreamHandler` 触发 `VueProjectBuilder.buildProjectAsync`；
5. 构建完成后 `StaticResourceController` 自动从 `dist` 提供最新预览。

**构建排队**：如果上一轮构建还没结束又完成了一轮修改，`buildProjectAsync` 不会直接丢弃新请求，而是登记到 `pendingBuilds`。当前构建结束后会用最新源码再构建一次，避免预览停留在旧版本。构建循环在同一把调度锁内完成“检查排队 + 释放占用”，不会并发构建同一工程。

### 3.7 提示词约束

`codegen-vue-project-system-prompt.txt` 明确：

- 只允许使用 5 个工具，禁止编造工具名；
- 修改前必须 `readFile`，不能凭记忆猜测旧内容；
- `oldContent` 必须从文件内容中完整复制且唯一出现；
- 优先局部修改，禁止重构无关文件、格式化和大段输出代码；
- 新增页面必须同步路由和导航；
- 新增依赖必须同步 `package.json`；
- 完成后停止工具调用，只输出简短完成提示。

## 4. 测试

```powershell
mvn '-Dtest=FileWriteToolTest,FileReadToolTest,FileDirReadToolTest,FileModifyToolTest,FileDeleteToolTest,ToolManagerTest,CodeGenPromptContractTest,AiCodeGeneratorServiceContractTest,AiCodeGeneratorServiceFactoryTest,JsonMessageStreamHandlerTest,VueProjectBuilderTest,AiServiceContextTest' test
```

覆盖点：

- 五个工具的写入、读取、目录树、唯一替换、删除、路径穿越、绝对路径、非法 appId、长度上限和结果不泄露绝对路径；
- 三套提示词的元素信息格式、原生全量重写和 Vue 先读后改约束；
- `ToolManager` 注册、去重、按名称分发、未知工具兜底、流式进度脱敏；
- 工具注解契约：`@Tool`、`@P`、`@ToolMemoryId`、模型可见参数集合；
- 工厂为 Vue 模式绑定全部工具，HTML 模式不绑定；
- 流式处理器使用工具策略输出进度、限制工具次数、完成时触发构建；
- 构建器在同一工程构建期间排队，结束后自动重建。

前端改动后执行：

```powershell
cd zgq-ai-code-mother-frontend
npm run test:visual-editor
npm run type-check
npm run build
```

## 5. 已知边界

1. 工具只支持文本文件，按 UTF-8 读写；二进制资源不适合通过工具修改。
2. `modifyFile` 要求旧内容唯一；模型找不到唯一片段时需要改用 `writeFile` 整文件重写。
3. 局部修改期间用户停止生成，可能出现部分文件已修改、构建未触发的情况；下一次成功生成会重新构建。
4. 工具输出仍会写入 SSE 文本流并保存在 AI 回复中，只是已压缩为首行摘要；如需完全分离“工具进度”和“对话正文”，需要新增独立 SSE 事件类型。
5. 构建会在服务器执行 AI 生成项目的 npm 命令，生产环境应放入隔离容器或受限账号。

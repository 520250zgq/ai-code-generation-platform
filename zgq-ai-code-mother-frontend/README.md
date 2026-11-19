# zgq-ai-code-mother-frontend

前端使用 Vue 3、TypeScript、Vite、Pinia 和 Ant Design Vue。通过 `@umijs/openapi` 读取运行中的后端 OpenAPI 文档，生成 `src/api` 下的请求函数和 TypeScript 类型。

目录结构、模块职责、页面路由和主要业务调用关系见 [项目结构说明](docs/项目结构说明.md)。

## 开发环境

- Node.js 20.19+ 或 22.12+，以及 npm。
- 后端使用 Java 21，MySQL 已启动且数据库配置正常，能够启动 `ZgqAiCodeMotherApplication`。
- 推荐编辑器：VS Code + Vue - Official（Volar）。项目使用 `vue-tsc` 检查 `.vue` 文件的 TypeScript 类型。

## 启动及生成请求代码

按以下顺序操作。在 Windows PowerShell 中执行命令。

1. **先启动后端。** 在 IDE 中运行 `ZgqAiCodeMotherApplication`；也可以在单独的终端执行：

   ```powershell
   cd D:\codeZgq-langChain4j\zgq-ai-code-mother
   .\mvnw.cmd spring-boot:run
   ```

   等待后端启动完成，保持该进程运行。访问 [OpenAPI JSON 文档](http://localhost:8123/api/v3/api-docs)，确认 `paths` 包含 `/user/login`、`/user/get/login` 等用户接口。也可以访问 [接口调试页面](http://localhost:8123/api/doc.html)。

2. **安装前端依赖。** 在另一个终端进入前端目录。首次开发、重新下载项目或 `package-lock.json` 发生变化时执行 `npm ci`：

   ```powershell
   cd D:\codeZgq-langChain4j\zgq-ai-code-mother\zgq-ai-code-mother-frontend
   npm ci
   ```

3. **生成最新请求代码。**

   ```powershell
   npm run openapi2ts
   ```

   此命令会先检查后端文档是否可访问、是否包含用户接口，再执行生成。成功后应存在 `src/api/userController.ts`，其中包含 `userLogin`、`userRegister`、`getLoginUser` 等函数；`src/api/typings.d.ts` 包含对应请求和响应类型。

4. **启动前端。**

   ```powershell
   npm run dev
   ```

   打开终端输出的前端地址。前端请求通过 `src/request.ts` 中的 Axios 实例发送到同源 `/api`，再由 `vite.config.ts` 的代理转发到 `http://localhost:8123`。

后端接口或 DTO 发生变化后，先重启后端并确认文档已更新，再执行 `npm run openapi2ts`。启动 `npm run dev` 不会自动生成请求代码。`src/api` 是生成目录，重新生成会覆盖其中的代码；业务逻辑应写在页面、store 或其他非生成文件中。

## 在页面或 store 中调用

生成函数复用 `src/request.ts`，返回 Axios 的响应对象；通过 `res.data` 获取后端的 `{ code, data, message }`，通过 `res.data.data` 获取业务数据。

```ts
import { getLoginUser, userLogin } from '@/api/userController'

// 在登录表单提交事件中调用，账号密码来自表单。
async function login(userAccount: string, userPassword: string) {
  const res = await userLogin({ userAccount, userPassword })
  if (res.data.code === 0) {
    return res.data.data
  }
  throw new Error(res.data.message || '登录失败')
}

// 在需要读取当前登录用户时调用。
async function loadLoginUser() {
  const res = await getLoginUser()
  if (res.data.code === 0) {
    return res.data.data
  }
  return undefined
}
```

项目已实现主页、应用生成对话页、应用信息编辑页、应用管理页、对话管理页，以及登录、注册和登录状态管理。

## 应用页面与业务流程

| 路径                       | 功能与权限                                                                                                                           |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `/`                        | 提示词创建应用、我的应用与精选应用的独立搜索分页、详情、本人应用编辑与删除。后端的两个列表接口均要求登录，游客显示登录引导。         |
| `/app/chat/:id`            | 登录用户查看详情和已有预览；创建者发送消息生成网站、终止生成、重试与部署。管理员对他人应用也只有查看和管理权限，生成和部署仍须本人。 |
| `/app/edit/:id`            | 本人修改应用名称；管理员修改任意应用的名称、封面和优先级。                                                                           |
| `/admin/appManage`         | 管理员查看、筛选与分页查询任意应用，新窗口编辑、删除、设置精选（优先级 99），并可跳转查看该应用对话。                                |
| `/admin/chatHistoryManage` | 管理员跨应用筛选、分页查询用户与 AI 消息，查看完整内容并打开所属应用。                                                               |
| `/admin/userManage`        | 现有用户管理页面，管理员菜单与路由权限校验。                                                                                         |

首页创建成功后进入对话页；对话页加载历史后，只有“当前用户是应用创建者且没有历史消息”时才自动把初始提示词作为第一条消息发送。SSE 使用携带 Cookie 的 fetch 读取 JSON `{ "d": "内容" }`，收到后端 `event: done` 后才展示新生成的网站；异常断流与手动停止不会误判为成功。预览地址为 `{预览根地址}/static/{codeGenType}_{appId}/`。部署完成后展示后端返回的 URL，可复制或在新窗口打开。

对话页发送按钮左侧还有“编辑元素”按钮。进入编辑模式后，父页面向同源 iframe 注入元素选择脚本：鼠标悬浮显示虚线边框，点击后显示固定边框，并通过 `postMessage` 把标签、选择器、文本、位置等信息回传给主页面。选中信息会显示在输入框上方的 `a-alert` 中，可以随时移除；发送消息时自动追加到提示词末尾，发送后清空选中元素并退出编辑模式。相关逻辑集中在 `src/utils/visualEditor.ts`。

对话页先解包 SSE 的 `{ "d": "片段" }` 数据，再将模型返回的 `htmlCode`、`cssCode`、`jsCode`、`description` JSON 字段增量解码为面向用户的生成说明和 `index.html`、`style.css`、`script.js` 独立代码块；生成过程中不展示原始 JSON 字符串。代码块通过 `highlight.js` 高亮 HTML、CSS 和 JavaScript；普通 Markdown 回复仍由 `markdown-it` 渲染。源码和原始 HTML 仅作为转义后的文本显示，不会在对话区域执行。

对话历史由后端 `chat_history` 表保存。应用页通过 `/chatHistory/list/cursor` 以 `createTime + id` 复合游标加载最新 10 条，消息按创建时间升序展示，并可通过“加载更多历史消息”继续读取；已有至少 2 条历史时会探测并展示已生成的网站。管理员可在 `/admin/chatHistoryManage` 跨应用筛选和审阅全部消息。删除应用会在同一事务内逻辑删除其全部历史。雪花算法 ID 按后端返回的字符串保留，避免转换为 JavaScript Number 造成精度丢失。

前端地址配置在 `src/config/index.ts`。`.env.development` 默认把 `VITE_API_BASE_URL` 设为相对目录 `/api`，Vite 会把 `/api` 代理到 `http://localhost:8123`，因此 API 请求和生成站点预览都与前端同源；可视化编辑依赖这一同源条件，跨域时无法向 iframe 注入脚本。可以通过 `VITE_PROXY_TARGET` 修改本地代理目标。`.env.production` 同样使用 `/api`，生产环境需要由 Nginx 把 `/api` 转发到后端。修改环境变量后需重启 Vite。

## 常见问题

| 现象                                                         | 处理方法                                                                                             |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| 连接 `8123` 失败或文档无法访问                               | 先启动后端，检查后端启动日志、端口和 `/api` 上下文路径，再重新运行生成命令。                         |
| 文档只有 `/health/`，没有 `/user/...`                        | 当前运行的后端进程未加载用户接口。重启最新后端代码，确认文档包含用户接口后再生成。                   |
| 安装了 `@umijs/openapi`，但 `src/api` 仍只有健康检查         | 安装依赖不会自动生成请求。启动后端后执行 `npm run openapi2ts`，检查命令是否成功结束。                |
| 后端接口已改，前端请求或类型仍是旧的                         | 重启后端并重新生成，检查 `src/api/userController.ts` 和 `src/api/typings.d.ts`。                     |
| 获取当前用户返回业务码 `40100`                               | 表示未登录；先使用有效账号登录。Axios 已配置 `withCredentials: true` 以携带会话 Cookie。             |
| 打开应用时预览 `HEAD /api/static/.../` 返回 403，并提示 CORS | 后端 `CorsConfig.allowedMethods` 必须包含 `HEAD`，前端使用它检查已有预览。修改后重启后端并刷新页面。 |
| 请求函数已经生成，但页面没有变化                             | 在页面或 store 中导入并调用函数；生成器不会实现登录表单或其他界面。                                  |
| 点击“编辑元素”提示预览与页面不同源                           | 确认 `.env.development` 的 `VITE_API_BASE_URL=/api`、Vite 代理已启动；线上检查 Nginx 是否把 `/api` 转发到后端。 |

如果修改了后端地址，需要设置 `VITE_API_BASE_URL`；重新生成 API 时还需同步更新 `openapi2ts.config.ts` 中的文档地址。

## 其他命令

```powershell
npm run type-check # TypeScript 类型检查
npm run test:app-preview # 预览独立域名与 API 地址回退测试（无需后端）
npm run test:stream # SSE 协议、断流、取消和拆包回归测试（无需后端）
npm run test:markdown # Markdown、高亮、安全转义与流式代码块回归测试（无需后端）
npm run test:generated-response # 模型 JSON 增量解码、分文件高亮与安全回归测试（无需后端）
npm run test:visual-editor # 可视化编辑元素信息、提示词拼接和 postMessage 校验测试（无需后端）
npm run build      # 类型检查并构建生产产物
npm run preview    # 预览构建产物
npm run lint       # ESLint 检查并自动修复
npm run format     # 格式化 src 目录
```

Vite 配置参考：[Vite 文档](https://vite.dev/config/)。

cd D:\codeZgq-langChain4j\zgq-ai-code-mother\zgq-ai-code-mother-frontend
npm run openapi2ts
npm run dev

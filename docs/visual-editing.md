# 可视化编辑与 iframe 通信

本文对应需求：在应用对话页进入可视化编辑模式，鼠标悬浮预览元素时显示边框，点击后固定边框并把元素信息回传给主页面；发送消息时把元素信息合并进提示词。

## 1. 整体流程

```text
用户点击“编辑元素”
  -> AppChatPage.startVisualEditing()
       -> mountVisualEditor(iframe, onSelected)          // src/utils/visualEditor.ts
            -> 校验 iframe 同源，取得 contentDocument
            -> 注入 <style>：悬浮虚线边框、选中实线边框、十字光标
            -> 注入 <script>：元素采集与点击拦截
            -> 父页面监听 message，校验 event.source / origin / 数据结构
  -> 用户在 iframe 中悬浮元素（虚线边框）
  -> 用户点击元素
       -> 注入脚本 preventDefault / stopPropagation，阻止跳转和提交
       -> 选中元素显示实线边框
       -> window.parent.postMessage({ type, element }, location.origin)
  -> 父页面保存 selectedElement，在输入框上方展示 a-alert
  -> 用户点击发送
       -> composePromptWithElement() 把元素信息追加到提示词
       -> 清空 selectedElement，退出编辑模式，再走原有 SSE 生成流程
```

## 2. 同源前提

父页面只有访问同源 iframe 的 `contentDocument` 才能注入脚本；跨域时 `contentDocument` 返回 `null` 或抛出 `SecurityError`，功能不可用。因此预览站点必须和前端同源。

本地开发：

```ts
// vite.config.ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8123',
      changeOrigin: true,
    },
  },
}
```

```dotenv
# .env.development
VITE_API_BASE_URL=/api
VITE_APP_PREVIEW_BASE_URL=/api
```

前端请求 `/api/...`，Vite 代理到后端 `http://localhost:8123/api/...`；预览地址变成 `/api/static/{codeGenType}_{appId}/`，与前端同源。

线上 Nginx 参考：

```nginx
location / {
    root /var/www/ai-code-mother;
    try_files $uri $uri/ /index.html;
}

location /api/ {
    proxy_pass http://127.0.0.1:8123/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    proxy_buffering off;   # SSE 需要关闭缓冲
}
```

构建前端时设置 `VITE_API_BASE_URL=/api`。不要为预览单独配置跨域域名，否则可视化编辑无法注入。

## 3. 通信协议

消息类型：

```ts
const VISUAL_EDITOR_MESSAGE_TYPE = 'AI_CODE_MOTHER_ELEMENT_SELECTED'
```

消息结构：

```ts
type VisualEditorMessage = {
  type: 'AI_CODE_MOTHER_ELEMENT_SELECTED'
  element: {
    tagName: string                 // 小写标签名
    id: string
    className: string
    selector: string                // 例如 #submit、main > section:nth-of-type(2) > button
    text: string                    // 最多 120 字
    attributes: Record<string, string> // 最多 12 个属性，值最多 80 字
    rect: { x: number; y: number; width: number; height: number }
  }
}
```

父页面校验：

1. `event.source === iframe.contentWindow`；
2. `event.origin === window.location.origin`；
3. 数据结构完整，`rect` 的四个字段都是有限数字。

任一条不满足都直接丢弃，避免其他窗口伪造消息并注入提示词。

## 4. 注入脚本的生命周期

- **进入编辑模式**：`mountVisualEditor` 先清理同一 iframe 的旧监听，再注入样式和脚本。脚本通过 `toString()` 序列化后写入 `<script>`，在 iframe 作用域内执行，因此不依赖父页面模块作用域。
- **iframe 刷新或重建**：`@load` 触发 `handlePreviewLoad`；如果仍在编辑模式，重新注入。
- **退出编辑模式**：调用 iframe window 上的清理函数，移除事件监听、悬浮/选中属性、编辑标记以及注入的 `<style>`、`<script>`；同时移除父页面的 `message` 监听。
- **页面卸载/路由切换**：`onBeforeUnmount` 和 `loadApp` 都会调用 `stopVisualEditing`。
- **生成新网站**：发送消息时先退出编辑模式，预览 iframe 会被清空，避免脚本作用于旧页面。

脚本只使用 `window`、`document`、`Math`、`Array` 等浏览器全局对象，不引用父页面的常量或工具函数。`scripts/test-visual-editor.mjs` 会检查注入脚本语法有效，并锁定“运行时不能引用模块变量”的约束。

## 5. 对话页交互

| 交互 | 实现 |
| --- | --- |
| 编辑按钮 | 位于发送按钮左侧，`a-button` + 光标图标；生成中、没有预览或没有权限时禁用 |
| 进入/退出 | 同一个按钮切换，激活时显示“退出编辑”，预览工具栏显示“编辑模式”标签 |
| 悬浮效果 | 注入样式 `[data-ai-code-mother-hover='true'] { outline: 2px dashed ... }` |
| 选中效果 | 注入样式 `[data-ai-code-mother-selected='true'] { outline: 2px solid ... }` |
| 元素信息 | 输入框上方 `a-alert`，展示紧凑描述、文本和 CSS 选择器，`close-text="移除"` |
| 提示词拼接 | `composePromptWithElement(raw, element)`，格式见下一节 |
| 发送后 | 清空 `selectedElement`、退出编辑模式，再发送 SSE 请求 |
| 主动移除 | 点击 Alert 的“移除”，清除选中信息和 iframe 中的选中边框，仍保持编辑模式以便重新选择 |

元素信息块：

```text
【可视化编辑 - 当前选中元素】
- 标签：button
- id：submit
- class：btn primary
- CSS 选择器：#submit
- 文本内容：提交订单
- 位置：x=120，y=430，宽=96，高=36（单位：像素）
- 属性：type="submit"，data-role="primary"

请根据上面的要求修改该选中元素，并保持其他元素、样式和交互不变。
```

后端三套系统提示词都约定了这个信息块，改动格式时需同步更新 `codegen-html-system-prompt.txt`、`codegen-multi-file-system-prompt.txt`、`codegen-vue-project-system-prompt.txt`。

## 6. 安全边界

1. iframe 的 `sandbox` 增加 `allow-same-origin`，否则父页面无法访问 `contentDocument`，悬浮和点击采集都无法实现。代价是同源生成站点可以访问父页面环境；本项目生成的是用户自己的网站，仍应把预览视为不完全可信内容。
2. 编辑脚本拦截点击的默认行为，避免生成站点的链接、表单和按钮在选中元素时触发页面跳转。
3. 父页面只接受当前 iframe、同源、结构合法的消息，且所有展示文本都作为文本节点渲染，不使用 `v-html`。
4. 元素文本和属性在子页面采集时截断，父页面拼提示词时再次截断，避免超长内容撑大提示词。
5. 预览 iframe 仍然使用 `sandbox`、`referrerpolicy="no-referrer"`，不额外的 `allow-top-navigation` 权限。

## 7. 测试与排查

```powershell
cd zgq-ai-code-mother-frontend
npm run test:visual-editor
npm run type-check
npm run build
```

| 现象 | 排查方向 |
| --- | --- |
| 提示“预览站点与当前页面不同源” | 检查 `.env.development` 是否使用 `/api`，Vite 代理是否生效；线上检查 Nginx |
| 悬浮没有边框 | iframe 是否加载完成；浏览器控制台是否报 `contentDocument` 访问错误 |
| 点击后页面跳转 | 生成站点是否在 iframe 内；检查注入脚本是否被页面 CSP 阻止 |
| 点击没有信息回传 | 检查 `message` 监听是否因预览刷新被移除；刷新后应重新注入 |
| 发送后仍带元素信息 | 检查 `sendMessage` 是否在拼接后调用 `stopVisualEditing` 和清空 `selectedElement` |

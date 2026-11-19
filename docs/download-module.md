# 应用代码下载

下载接口把应用生成目录中的源代码过滤后打包为 ZIP，通过 HTTP 响应直接返回，不经过对象存储，也不在服务器上保留临时压缩包。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/app/download/{appId}` | 应用创建者 | 返回 `application/zip` 附件 |

## 校验顺序

1. `appId` 必须是正整数，用户必须已登录。
2. 应用必须存在且未被逻辑删除。
3. 当前用户 id 必须等于应用的 `userId`。管理员也不能下载其他用户的应用代码。
4. `codeGenType` 必须能转换为 `CodeGenTypeEnum`，再使用枚举值拼接目录名，不能直接信任数据库中的字符串。
5. 生成目录必须存在，并且过滤后至少还有一个源码文件。

任何一项失败都会沿用项目现有的全局异常处理约定返回 JSON `BaseResponse`，HTTP 状态码仍为 `200`。因此前端不能只判断 `response.ok`，必须同时检查 `Content-Type` 是否为 `application/zip`。

## 生成目录而不是部署目录

下载读取 `AppConstant.CODE_OUTPUT_ROOT_DIR` 下的：

```text
tmp/code_output/{codeGenType}_{appId}
```

目录命名与 `CodeFileSaverTemplate` 保持一致，例如 `multi_file_123`、`vue_project_123`。部署目录 `tmp/code_deploy/{deployKey}` 只保存复制后的构建产物，不是用户需要的源代码。

## 文件过滤

过滤以相对路径的**完整路径段**为单位，而不是字符串包含，避免把 `src/builder/`、`src/distance.js` 这类正常源码误删。

不下载的目录：

- 依赖目录：`node_modules`
- 构建/缓存目录：`dist`、`build`、`target`、`out`、`coverage`、`.next`、`.nuxt`、`.cache`、`.turbo` 等
- 本地环境和版本控制目录：`.git`、`.svn`、`.hg`、`.idea`、`.vscode`

不下载的文件：

- 系统文件：`.DS_Store`、`Thumbs.db`、`desktop.ini`
- 敏感配置：`.env`、`.env.local`、`.env.production`、`app.env` 等全部环境变量文件，以及 `.npmrc`、`.git-credentials`
- 日志和密钥：`*.log`、`*.pem`、`*.key`、`*.p12`、`*.pfx`

遍历时不跟随符号链接；ZIP 条目只使用相对于生成目录的路径并统一为正斜杠，不会泄露服务器绝对路径。

## 响应头

成功时设置：

```http
Content-Type: application/zip
Content-Disposition: attachment; filename="..."; filename*=UTF-8''...
Cache-Control: no-store
X-Content-Type-Options: nosniff
```

`Content-Disposition` 使用 Spring `ContentDisposition` 生成 RFC 5987 的 `filename*`，中文应用名可被现代浏览器正确还原。文件名格式为 `{应用名}_{appId}.zip`，应用名中的引号、换行、路径分隔符等会先被替换，空白名称回退为 `app`，避免响应头注入和非法文件名。

## 前端入口

`zgq-ai-code-mother-frontend/src/utils/appDownload.ts` 使用带 `credentials: 'include'` 的 `fetch` 获取 ZIP：

1. 非 ZIP 响应按 JSON 解析错误并提示。
2. 成功时从 `Content-Disposition` 解析文件名，用临时 Blob URL 触发浏览器保存。
3. 创建者进入应用工作台且已有生成结果后，顶栏显示「下载代码」按钮。

## 测试

```powershell
mvn '-Dtest=CodeDownloadUtilsTest,AppServiceImplDownloadTest,AppControllerTest' test
```

- `CodeDownloadUtilsTest` 覆盖路径段过滤、环境变量/日志/密钥排除、关键字目录不误伤、文件名清理和 ZIP 相对路径。
- `AppServiceImplDownloadTest` 覆盖创建者权限、管理员不可越权、应用/目录缺失、过滤后为空、中文文件名响应头和 ZIP 内容。
- `AppControllerTest` 覆盖参数校验、未登录拒绝和创建者下载委托。

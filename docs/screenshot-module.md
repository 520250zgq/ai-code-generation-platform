# 网页截图与封面存储

部署应用后，后端会异步访问部署地址、生成 1280x720 的网页截图，上传腾讯云 COS，并把返回的图片 URL
写入 `app.cover`。截图失败不会影响部署结果，部署接口仍然返回可访问地址。

## 模块划分

| 组件 | 职责 |
| --- | --- |
| `utils/WebScreenshotUtils` | 无头 Chrome 驱动生命周期、页面加载等待、截图、保存 PNG、压缩 JPEG、返回本地路径 |
| `service/ScreenshotService` | 通用截图服务接口，只接收网址，不包含 appId 等业务参数 |
| `service/impl/ScreenshotServiceImpl` | 组合本地截图和对象存储上传，返回图片 URL，并清理本地临时文件 |
| `manager/CosManager` | 腾讯云 COS 客户端、对象 key、上传元数据和公共 URL |
| `config/CosProperties` / `CosConfig` | 绑定 `app.cos` 配置并创建 COS 管理器 |
| `service/impl/AppServiceImpl` | 部署成功后异步触发截图，并把封面 URL 写入应用记录 |

截图文件的本地临时目录默认为 `tmp/screenshot/yyyyMMdd/`，上传成功后立即删除，不需要定期清理。

## 本地截图

`WebScreenshotUtils` 的静态代码块在类首次加载时创建唯一的 `ChromeDriver`，后续截图默认复用该实例。
Selenium Manager 会自动匹配本机 Chrome 并下载对应驱动；如果服务器无法访问 Google 的驱动下载地址，
可以提前安装 ChromeDriver 并通过 JVM 参数指定：

```powershell
mvn spring-boot:run '-Dspring-boot.run.jvmArguments=-Dwebdriver.chrome.driver=C:\tools\chromedriver.exe'
```

也可以用 `-Dapp.screenshot.chrome-binary=...` 指定 Chrome 可执行文件。

ChromeDriver 不是线程安全的，所有截图通过公平锁串行执行。截图流程为：

1. `driver.get(url)` 访问页面，页面加载策略为 `EAGER`，只等待 `DOMContentLoaded`；
2. 等待 `document.readyState` 进入 `interactive` 或 `complete`，确认 `document.body` 已创建；
3. 再有界等待资源完成（默认 3 秒），即使第三方图片、字体一直挂起也继续截图；
4. 额外等待一段渲染时间，截取视口并保存为 PNG；
5. 用 JPEG 质量参数压缩为 `.jpg`；
6. 删除 PNG 中间文件并返回 JPEG 绝对路径。

> 不要使用默认的 `NORMAL` 页面加载策略：它会等待所有图片和 iframe 的 `load` 事件。部署页面引用外链图片
> （例如 `picsum.photos`）时，只要图片请求挂起，`driver.get()` 就会在 30 秒后抛出
> `TimeoutException: Timed out receiving message from renderer`，导致封面生成失败。

Chrome 的 `--window-size` 设置的是外层窗口尺寸，无头模式会为窗口边框预留空间。工具初始化时会测量
外层窗口与视口的差值并自动放大外层窗口，确保最终截图为配置的 1280x720。

可通过系统属性调整：

| 系统属性 | 默认值 | 说明 |
| --- | --- | --- |
| `app.screenshot.width` | `1280` | 截图宽度 |
| `app.screenshot.height` | `720` | 截图高度 |
| `app.screenshot.page-load-timeout-seconds` | `30` | 等待 DOM 就绪的上限 |
| `app.screenshot.resource-load-timeout-seconds` | `3` | DOM 就绪后等待图片等资源的上限，超时继续截图 |
| `app.screenshot.render-delay-millis` | `1000` | DOM 就绪后的额外渲染等待 |
| `app.screenshot.jpeg-quality` | `0.7` | JPEG 压缩质量，0-1 |
| `app.screenshot.output-dir` | `tmp/screenshot` | 本地截图临时目录 |
| `app.screenshot.chrome-binary` | 自动检测 | Chrome 可执行文件路径 |
| `webdriver.chrome.driver` | Selenium Manager | 手动指定的 ChromeDriver 路径 |

应用停止时，如果本次运行使用过截图，`ScreenshotServiceImpl` 的 `@PreDestroy` 会调用
`WebScreenshotUtils.quit()`；同时工具类注册了 JVM 关闭钩子兜底，两处都保证幂等，不会重复关闭或遗留
Chrome 进程。

## 腾讯云 COS

配置项位于 `app.cos`：

```yaml
app:
  cos:
    secret-id: ${COS_SECRET_ID:}
    secret-key: ${COS_SECRET_KEY:}
    region: ${COS_REGION:ap-guangzhou}
    bucket: ${COS_BUCKET:zgq-ai-code-father-1528961224}
    key-prefix: ${COS_KEY_PREFIX:screenshot/}
    domain: ${COS_DOMAIN:}
    public-read: ${COS_PUBLIC_READ:true}
```

生产环境建议通过 `COS_SECRET_ID`、`COS_SECRET_KEY` 环境变量注入密钥。本地开发可以把密钥写在
`application-local.yml`，该文件已被 `.gitignore` 忽略，不会提交到版本库。

对象 key 格式为 `{key-prefix}yyyy/MM/dd/{uuid}.jpg`，默认上传为公共读对象并设置一年缓存，返回：

```text
https://{bucket}.cos.{region}.myqcloud.com/{key}
```

如果配置了 `domain`，则返回 `{domain}/{key}`。密钥或桶未配置时应用仍可启动，只是部署后的封面生成
会记录错误，不影响部署接口。

## 部署触发

`AppServiceImpl#deployApp` 完成以下步骤后触发封面生成：

1. 校验应用归属和代码目录；
2. 复制代码到部署目录；
3. 更新 `deployKey` 和 `deployedTime`；
4. 拼出部署 URL；
5. 在 Java 21 虚拟线程中执行 `ScreenshotService#generateAndUploadScreenshot`；
6. 截图上传成功后，用新的 `cover` 更新应用记录。

截图任务中的任何异常都只记录日志，不会回滚部署。应用在截图完成前被删除时，封面更新会失败并记录
警告，不影响已完成的部署。

## 测试

```powershell
mvn '-Dtest=WebScreenshotUtilsTest,CosManagerTest,ScreenshotServiceImplTest,AppServiceImplTest' test
```

- `WebScreenshotUtilsTest` 覆盖保存图片、压缩、页面加载等待和真实无头浏览器截图。
- `CosManagerTest` 使用模拟 COS 客户端验证对象 key、元数据、公共 URL 和失败降级，不会访问真实对象存储。
- `ScreenshotServiceImplTest` 覆盖 URL 校验、截图上传编排、上传失败和本地文件清理。
- `AppServiceImplTest` 覆盖部署后异步触发截图、封面更新以及截图失败不影响部署。

真实截图测试需要本机安装 Chrome。没有可用驱动时测试会自动跳过，其余测试仍可正常运行。若 Selenium
Manager 无法联网下载驱动，可把匹配版本的 ChromeDriver 放入 Selenium 缓存目录
（`~/.cache/selenium/chromedriver/<platform>/<version>/`），或通过 `-Dwebdriver.chrome.driver=...` 指定。

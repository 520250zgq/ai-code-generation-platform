import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createJiti } from 'jiti'
import { generateService } from '@umijs/openapi'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
let temporaryDirectory

try {
  const jiti = createJiti(import.meta.url)
  const config = await jiti.import(resolve(projectRoot, 'openapi2ts.config.ts'), {
    default: true,
  })

  console.log(`[OpenAPI] 读取文档：${config.schemaPath}`)
  const response = await fetch(config.schemaPath, {
    signal: AbortSignal.timeout(15000),
    headers: config.authorization ? { authorization: config.authorization } : {},
  })
  if (!response.ok) {
    throw new Error(`文档请求返回 HTTP ${response.status}`)
  }

  const schema = await response.json()
  if (!schema.openapi?.startsWith('3.') || !schema.info || !schema.paths) {
    throw new Error('响应不是有效的 OpenAPI 3 文档，请使用 /api/v3/api-docs JSON 地址')
  }

  const requiredOperations = [
    ['/user/login', 'post'],
    ['/user/register', 'post'],
    ['/user/get/login', 'get'],
  ]
  const missingOperations = requiredOperations.filter(
    ([path, method]) => !schema.paths[path]?.[method],
  )
  if (missingOperations.length) {
    throw new Error(
      `文档缺少用户接口：${missingOperations.map(([path, method]) => `${method.toUpperCase()} ${path}`).join('、')}。请先重启后端并确认文档已更新`,
    )
  }

  // Spring 的 {*resourcePath} 是通配路径语法，生成器会误生成非法的 ${*resourcePath}。
  // OpenAPI 快照仅去掉路径模板中的星号，参数名与后端请求地址保持一致。
  schema.paths = Object.fromEntries(
    Object.entries(schema.paths).map(([path, operation]) => [
      path.replace(/\{\*([^}]+)\}/g, '{$1}'),
      operation,
    ]),
  )

  // 使用已验证的文档快照，避免生成器再次请求时吞掉网络错误。
  // 验证失败时不会调用生成器，因此保留已有 src/api。
  temporaryDirectory = await mkdtemp(join(tmpdir(), 'zgq-openapi-'))
  const schemaFile = join(temporaryDirectory, 'schema.json')
  await writeFile(schemaFile, JSON.stringify(schema), 'utf8')
  await generateService({
    ...config,
    schemaPath: schemaFile,
    serversPath: resolve(projectRoot, config.serversPath ?? './src'),
  })
  console.log(`[OpenAPI] 已从 ${Object.keys(schema.paths).length} 个接口路径生成请求代码`)
} catch (error) {
  console.error(`[OpenAPI] 生成失败：${error.message}`)
  console.error(
    '请先启动后端，确认 openapi2ts.config.ts 中的文档地址可访问，再执行 npm run openapi2ts。',
  )
  process.exitCode = 1
} finally {
  if (temporaryDirectory) {
    await rm(temporaryDirectory, { recursive: true, force: true })
  }
}

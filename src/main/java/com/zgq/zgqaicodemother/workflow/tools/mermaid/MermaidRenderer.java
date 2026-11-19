package com.zgq.zgqaicodemother.workflow.tools.mermaid;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Mermaid 文本转图片渲染器。
 *
 * <p>抽象为接口后，工作流测试可以注入假渲染器，不需要在 CI 环境安装 Node.js 和 Mermaid CLI。</p>
 */
public interface MermaidRenderer {

    /**
     * 渲染 Mermaid 代码为图片。
     *
     * <p>返回的临时目录由调用方负责清理，避免长时间运行时在系统临时目录堆积文件。</p>
     *
     * @param mermaidCode 已通过安全校验的 Mermaid 代码
     * @return 渲染结果，包含临时文件和扩展名
     * @throws IOException 渲染器不可用或渲染失败
     */
    RenderedDiagram render(String mermaidCode) throws IOException;

    /**
     * 渲染器当前是否可用。实现可以缓存检测结果。
     *
     * @return 可用返回 true
     */
    boolean available();

    /**
     * 渲染结果。
     *
     * @param file      临时图片文件
     * @param extension 扩展名，png 或 svg
     * @param tempDir   临时目录，调用方清理时使用
     */
    record RenderedDiagram(Path file, String extension, Path tempDir) {
    }
}

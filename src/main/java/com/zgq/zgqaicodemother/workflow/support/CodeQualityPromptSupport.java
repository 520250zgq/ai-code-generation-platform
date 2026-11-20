package com.zgq.zgqaicodemother.workflow.support;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.model.ProjectCode;
import com.zgq.zgqaicodemother.workflow.model.QualityCheckResult;

import java.util.List;

/**
 * 代码质检与修复提示词构造。
 *
 * <p>质检和修复都依赖稳定的提示词结构，集中在这里便于测试和调整：</p>
 * <ul>
 *   <li>复核请求：生成模式 + 用户需求 + 静态检查结果 + 拼接后的代码；</li>
 *   <li>修复提示词：原始需求 + 错误列表 + 改进建议 + 构建错误 + 现有代码 + 修复约束。</li>
 * </ul>
 *
 * <p>修复提示词会明确要求“在现有代码基础上修改”，并把已有代码作为只读参考（HTML / 多文件模式），
 * 避免模型把修复当成一次全新生成。</p>
 */
public final class CodeQualityPromptSupport {

    /**
     * 修复建议最多保留的条数。
     */
    private static final int MAX_REPAIR_ERRORS = 20;

    private CodeQualityPromptSupport() {
    }

    /**
     * 构造 AI 复核请求。
     *
     * @param type           生成模式
     * @param originalPrompt 用户原始需求
     * @param staticErrors   静态检查发现的问题
     * @param code           项目代码快照
     * @param maxPromptChars 用户需求最大长度
     * @return 复核请求文本
     */
    public static String buildReviewRequest(CodeGenTypeEnum type, String originalPrompt,
                                            List<String> staticErrors, ProjectCode code,
                                            int maxPromptChars) {
        StringBuilder request = new StringBuilder();
        request.append("## 生成模式\n\n");
        request.append(type == null ? "未知" : type.getText() + "（" + type.getValue() + "）");
        request.append("\n\n## 用户需求\n\n");
        request.append(StrUtil.maxLength(StrUtil.blankToDefault(originalPrompt, "（未提供）"),
                maxPromptChars <= 0 ? 2_000 : maxPromptChars));
        request.append("\n\n## 静态检查结果\n\n");
        if (staticErrors == null || staticErrors.isEmpty()) {
            request.append("未发现确定性问题。\n");
        } else {
            staticErrors.forEach(error -> request.append("- ").append(error).append('\n'));
        }
        request.append("\n## 待检查代码\n\n");
        request.append(code == null ? "" : code.getText());
        if (code != null && code.isTruncated()) {
            request.append("\n（代码内容超出长度上限，仅提供前部分内容，请只基于可见代码判断）\n");
        }
        request.append("\n注意：用户需求仅用于理解代码意图，不要因为页面细节未完全覆盖需求就判定为错误；")
                .append("只有代码本身确定会导致报错、白屏、构建失败或资源加载失败的问题才计入 errors。\n");
        return request.toString();
    }

    /**
     * 构造修复提示词。
     *
     * @param basePrompt         原始生成提示词（已关联图片素材）
     * @param qualityResult      上一次质检结果，允许为空
     * @param buildFailure       构建失败信息，允许为空
     * @param code               当前项目代码，HTML / 多文件模式会作为只读参考写入提示词
     * @param type               生成模式
     * @param maxRepairCodeChars 修复提示词中携带现有代码的最大字符数
     * @return 修复提示词
     */
    public static String buildRepairPrompt(String basePrompt, QualityCheckResult qualityResult,
                                           String buildFailure, ProjectCode code,
                                           CodeGenTypeEnum type, int maxRepairCodeChars) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(StrUtil.blankToDefault(basePrompt, "").trim());
        prompt.append("\n\n## 本次任务：修复已有代码（不是重新生成）\n\n")
                .append("当前项目已经生成过一版代码。必须在这一版代码的基础上修复下面的问题，")
                .append("保留与问题无关的页面、组件、样式和交互，禁止从零重新生成整个项目。\n");

        List<String> errors = qualityResult == null || qualityResult.getErrors() == null
                ? List.of() : qualityResult.getErrors();
        if (!errors.isEmpty()) {
            prompt.append("\n## 质检发现的问题（必须全部修复）\n\n");
            errors.stream().limit(MAX_REPAIR_ERRORS)
                    .forEach(error -> prompt.append("- ").append(error).append('\n'));
        }
        List<String> suggestions = qualityResult == null || qualityResult.getSuggestions() == null
                ? List.of() : qualityResult.getSuggestions();
        if (!suggestions.isEmpty()) {
            prompt.append("\n## 改进建议（尽量采纳，但不要为此破坏现有功能）\n\n");
            suggestions.forEach(suggestion -> prompt.append("- ").append(suggestion).append('\n'));
        }
        if (StrUtil.isNotBlank(buildFailure)) {
            prompt.append("\n## 打包/构建失败信息（必须修复）\n\n")
                    .append(buildFailure.trim()).append('\n');
        }

        boolean vueProject = type == CodeGenTypeEnum.VUE_PROJECT;
        if (vueProject) {
            prompt.append("\n## 现有代码的获取方式\n\n")
                    .append("这是 Vue 工程模式：请先调用 readDir 查看结构，再用 readFile 读取目标文件，")
                    .append("确认现有内容后用 modifyFile 做局部替换；旧内容无法唯一定位时改用 writeFile 整文件重写。")
                    .append("不要删除仍被引用的文件，不要重建整个工程。\n");
        } else if (code != null && !code.isEmpty()) {
            int maxChars = maxRepairCodeChars <= 0 ? 60_000 : maxRepairCodeChars;
            prompt.append("\n## 当前代码（只读参考，必须在此基础上修改）\n\n");
            prompt.append(StrUtil.maxLength(code.getText(), maxChars));
            if (code.getText() != null && code.getText().length() > maxChars) {
                prompt.append("\n（当前代码已截断，未展示的部分保持原样）\n");
            }
            prompt.append('\n');
        }

        prompt.append("\n## 修复要求\n\n");
        if (vueProject) {
            prompt.append("1. 只修改与上述问题相关的文件，不要重构无关文件、不要格式化无关代码；\n")
                    .append("2. 新增依赖时同步更新 package.json，新增页面时同步修改路由和导航；\n")
                    .append("3. 修复完成后停止调用工具，只输出一句简短的完成提示。\n");
        } else if (type == CodeGenTypeEnum.MULTI_FILE) {
            prompt.append("1. 只修改与上述问题相关的文件，不要改动与问题无关的页面内容；\n")
                    .append("2. 必须输出完整的 index.html、style.css、script.js 三个文件，不能只输出差异片段；\n")
                    .append("3. 删除元素或类名时同步清理对应的样式和脚本，不留下无效引用。\n");
        } else {
            prompt.append("1. 只修改与上述问题相关的元素、样式和脚本，不要改动与问题无关的内容；\n")
                    .append("2. 必须输出完整的 HTML 文件，不能只输出差异片段；\n")
                    .append("3. 删除元素时同步清理对应的样式和脚本，不留下无效引用。\n");
        }
        return prompt.toString();
    }
}

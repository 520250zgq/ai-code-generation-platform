package com.zgq.zgqaicodemother.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 代码复核的结构化输出。
 *
 * <p>只保留模型需要判断的三个字段：是否通过、错误列表、改进建议。文件数量、耗时、
 * 是否截断等运行信息由后端补充到 {@link QualityCheckResult}，不交给模型生成。</p>
 *
 * <p>使用普通 POJO 而不是 record：LangChain4j 的结构化输出需要无参构造和 setter，
 * 与 {@link ImageCollectionPlan} 保持一致的写法。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityCheckReview implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否通过复核。模型返回 null 时按未通过处理，避免漏判。
     */
    private Boolean valid;

    /**
     * 会导致页面报错、白屏或构建失败的问题清单，每条包含文件名和具体原因。
     */
    private List<String> errors;

    /**
     * 不阻断运行、但值得改进的建议，例如可访问性、响应式细节。
     */
    private List<String> suggestions;
}

package com.zgq.zgqaicodemother.workflow.ai;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图片收集计划的规则兜底。
 *
 * <p>AI 规划依赖模型可用性和结构化输出稳定性。模型超时、返回非法 JSON 或返回空计划时，
 * 用关键词规则生成一个最小可用计划，保证工作流仍能收集内容图片和插画，而不是直接跳过
 * 整个图片收集节点。</p>
 */
public final class ImageCollectionPlanFallback {

    /**
     * 常见行业关键词到英文搜索词的映射。英文关键词在 Pexels / Pixabay / unDraw 上通常
     * 召回质量更好，中文需求因此需要这一层转换。
     */
    private static final Map<String, String> KEYWORD_MAPPING = new LinkedHashMap<>();

    private static final List<String> TECHNICAL_HINTS = List.of(
            "架构", "系统", "流程", "技术", "平台", "微服务", "前后端", "架构图", "流程图", "api");

    private static final List<String> LOGO_HINTS = List.of(
            "logo", "标志", "品牌", "公司", "官网", "产品", "企业", "平台", "工作室");

    static {
        KEYWORD_MAPPING.put("电商", "ecommerce shopping");
        KEYWORD_MAPPING.put("商城", "online store");
        KEYWORD_MAPPING.put("教育", "education learning");
        KEYWORD_MAPPING.put("课程", "online course");
        KEYWORD_MAPPING.put("医疗", "medical healthcare");
        KEYWORD_MAPPING.put("健康", "health wellness");
        KEYWORD_MAPPING.put("金融", "finance banking");
        KEYWORD_MAPPING.put("理财", "investment finance");
        KEYWORD_MAPPING.put("旅游", "travel landscape");
        KEYWORD_MAPPING.put("酒店", "hotel travel");
        KEYWORD_MAPPING.put("美食", "food restaurant");
        KEYWORD_MAPPING.put("餐饮", "restaurant food");
        KEYWORD_MAPPING.put("音乐", "music concert");
        KEYWORD_MAPPING.put("运动", "sports fitness");
        KEYWORD_MAPPING.put("健身", "fitness gym");
        KEYWORD_MAPPING.put("科技", "technology office");
        KEYWORD_MAPPING.put("人工智能", "artificial intelligence");
        KEYWORD_MAPPING.put("ai", "artificial intelligence");
        KEYWORD_MAPPING.put("办公", "modern office");
        KEYWORD_MAPPING.put("游戏", "gaming");
        KEYWORD_MAPPING.put("社交", "social community");
        KEYWORD_MAPPING.put("房产", "real estate house");
        KEYWORD_MAPPING.put("汽车", "car automotive");
        KEYWORD_MAPPING.put("时尚", "fashion style");
        KEYWORD_MAPPING.put("摄影", "photography");
        KEYWORD_MAPPING.put("新闻", "news newspaper");
        KEYWORD_MAPPING.put("博客", "blog writing");
        KEYWORD_MAPPING.put("个人", "personal portfolio");
        KEYWORD_MAPPING.put("作品集", "portfolio creative");
        KEYWORD_MAPPING.put("宠物", "pet animal");
        KEYWORD_MAPPING.put("儿童", "children kids");
        KEYWORD_MAPPING.put("农业", "agriculture farm");
        KEYWORD_MAPPING.put("环保", "environment nature");
        KEYWORD_MAPPING.put("公益", "charity volunteer");
    }

    private ImageCollectionPlanFallback() {
    }

    /**
     * 根据原始需求生成兜底计划。
     *
     * @param prompt 用户需求，允许为空
     * @return 至少包含一个内容图片任务和一个插画任务的计划
     */
    public static ImageCollectionPlan from(String prompt) {
        String safePrompt = StrUtil.blankToDefault(prompt, "").toLowerCase(Locale.ROOT);
        String contentQuery = "modern website background";
        String illustrationQuery = "teamwork";
        for (Map.Entry<String, String> entry : KEYWORD_MAPPING.entrySet()) {
            if (safePrompt.contains(entry.getKey())) {
                contentQuery = entry.getValue();
                illustrationQuery = entry.getValue();
                break;
            }
        }
        ImageCollectionPlan.ImageCollectionPlanBuilder builder = ImageCollectionPlan.builder()
                .contentImageTasks(List.of(new ImageCollectionPlan.ContentImageTask(contentQuery)))
                .illustrationTasks(List.of(new ImageCollectionPlan.IllustrationTask(illustrationQuery)))
                .diagramTasks(List.of())
                .logoTasks(List.of());
        if (containsAny(safePrompt, TECHNICAL_HINTS)) {
            builder.diagramTasks(List.of(new ImageCollectionPlan.DiagramTask(
                    """
                            flowchart LR
                              U[用户] --> F[前端页面]
                              F --> S[业务服务]
                              S --> D[(数据存储)]
                            """,
                    "展示网站的前后端分层结构")));
        }
        if (containsAny(safePrompt, LOGO_HINTS)) {
            String description = "为以下网站需求设计简洁现代的 Logo，禁止包含文字："
                    + StrUtil.maxLength(StrUtil.blankToDefault(prompt, contentQuery), 60);
            builder.logoTasks(List.of(new ImageCollectionPlan.LogoTask(description)));
        }
        return builder.build();
    }

    private static boolean containsAny(String prompt, List<String> hints) {
        return hints.stream().anyMatch(prompt::contains);
    }
}

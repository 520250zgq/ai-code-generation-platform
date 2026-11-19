package com.zgq.zgqaicodemother.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.workflow.ai.ImageCollectionPlanFallback;
import com.zgq.zgqaicodemother.workflow.ai.ImageCollectionPlanService;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.support.WorkflowImageSupport;
import com.zgq.zgqaicodemother.workflow.tools.ContentImageSearchTool;
import com.zgq.zgqaicodemother.workflow.tools.LogoGeneratorTool;
import com.zgq.zgqaicodemother.workflow.tools.MermaidDiagramTool;
import com.zgq.zgqaicodemother.workflow.tools.UndrawIllustrationTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 图片收集 AI 服务。
 *
 * <p>职责是“先规划、再并发执行、最后聚合”：</p>
 * <ol>
 *   <li>用 AI 把用户需求拆成内容图片、插画、架构图、Logo 四类任务；AI 不可用时用规则兜底。</li>
 *   <li>每类任务调用对应的图片工具，任务之间用虚拟线程并发执行，单个任务失败不影响其他任务。</li>
 *   <li>按固定类别顺序去重、截断并返回，同时收集非致命告警供前端展示。</li>
 * </ol>
 *
 * <p>服务本身不依赖工作流框架，既可以由工作流节点调用，也可以被其他入口复用。</p>
 */
@Slf4j
@Service
public class ImageCollectionService {

    private final ImageCollectionPlanService planService;

    private final ContentImageSearchTool contentImageSearchTool;

    private final UndrawIllustrationTool undrawIllustrationTool;

    private final MermaidDiagramTool mermaidDiagramTool;

    private final LogoGeneratorTool logoGeneratorTool;

    private final WorkflowProperties properties;

    public ImageCollectionService(ImageCollectionPlanService planService,
                                  ContentImageSearchTool contentImageSearchTool,
                                  UndrawIllustrationTool undrawIllustrationTool,
                                  MermaidDiagramTool mermaidDiagramTool,
                                  LogoGeneratorTool logoGeneratorTool,
                                  WorkflowProperties properties) {
        this.planService = planService;
        this.contentImageSearchTool = contentImageSearchTool;
        this.undrawIllustrationTool = undrawIllustrationTool;
        this.mermaidDiagramTool = mermaidDiagramTool;
        this.logoGeneratorTool = logoGeneratorTool;
        this.properties = properties;
    }

    /**
     * 收集图片素材。
     *
     * @param prompt 用户原始需求
     * @return 计划、图片列表和告警
     */
    public ImageCollectionOutcome collect(String prompt) {
        List<String> warnings = new ArrayList<>();
        if (StrUtil.isBlank(prompt)) {
            warnings.add("用户需求为空，跳过图片收集");
            return new ImageCollectionOutcome(ImageCollectionPlan.builder().build(), List.of(), warnings);
        }
        ImageCollectionPlan plan = planWithFallback(prompt, warnings);
        plan = sanitizePlan(plan, warnings);

        List<ImageResource> images = executeTasks(plan, warnings);
        images = orderAndCap(images);
        if (images.isEmpty()) {
            warnings.add("本次没有收集到可用的图片素材");
        }
        log.info("图片收集完成，计划任务数={}，素材数={}，告警数={}",
                plan.totalTasks(), images.size(), warnings.size());
        return new ImageCollectionOutcome(plan, images, warnings);
    }

    /**
     * 调用 AI 规划，失败或计划为空时使用规则兜底。
     */
    private ImageCollectionPlan planWithFallback(String prompt, List<String> warnings) {
        try {
            ImageCollectionPlan plan = planService.planImageCollection(prompt);
            if (plan != null && !plan.isEmpty()) {
                return plan;
            }
            warnings.add("AI 图片收集计划为空，已使用关键词规则兜底");
        } catch (Exception e) {
            // 模型超时、限额或 JSON 解析失败都可能进入这里，提示词长度不写日志正文。
            log.warn("AI 图片收集计划生成失败，使用关键词规则兜底：{}", e.getMessage());
            log.debug("AI 图片收集计划失败详情", e);
            warnings.add("AI 图片收集计划生成失败，已使用关键词规则兜底");
        }
        return ImageCollectionPlanFallback.from(prompt);
    }

    /**
     * 清洗和截断模型返回的计划，防止模型返回超量任务或非法参数。
     */
    private ImageCollectionPlan sanitizePlan(ImageCollectionPlan plan, List<String> warnings) {
        if (plan == null) {
            return ImageCollectionPlanFallback.from("");
        }
        WorkflowProperties.ContentImage contentConfig = properties.getContentImage();
        int contentLimit = Math.max(0, contentConfig.getMaxTasks());
        int illustrationLimit = Math.max(0, properties.getUndraw().getMaxTasks());
        int diagramLimit = Math.max(0, properties.getMermaid().getMaxTasks());
        int logoLimit = Math.max(0, properties.getLogo().getMaxTasks());

        List<ImageCollectionPlan.ContentImageTask> contentTasks = new ArrayList<>();
        Set<String> contentQueries = new LinkedHashSet<>();
        List<ImageCollectionPlan.ContentImageTask> rawContent = plan.getContentImageTasks();
        if (rawContent != null) {
            for (ImageCollectionPlan.ContentImageTask task : rawContent) {
                if (contentTasks.size() >= contentLimit || task == null) {
                    continue;
                }
                String query = WorkflowImageSupport.sanitizeQuery(task.getQuery());
                if (query != null && contentQueries.add(query)) {
                    contentTasks.add(new ImageCollectionPlan.ContentImageTask(query));
                }
            }
        }

        List<ImageCollectionPlan.IllustrationTask> illustrationTasks = new ArrayList<>();
        Set<String> illustrationQueries = new LinkedHashSet<>();
        List<ImageCollectionPlan.IllustrationTask> rawIllustrations = plan.getIllustrationTasks();
        if (rawIllustrations != null) {
            for (ImageCollectionPlan.IllustrationTask task : rawIllustrations) {
                if (illustrationTasks.size() >= illustrationLimit || task == null) {
                    continue;
                }
                String query = WorkflowImageSupport.sanitizeQuery(task.getQuery());
                if (query != null && illustrationQueries.add(query)) {
                    illustrationTasks.add(new ImageCollectionPlan.IllustrationTask(query));
                }
            }
        }

        List<ImageCollectionPlan.DiagramTask> diagramTasks = new ArrayList<>();
        List<ImageCollectionPlan.DiagramTask> rawDiagrams = plan.getDiagramTasks();
        if (rawDiagrams != null) {
            for (ImageCollectionPlan.DiagramTask task : rawDiagrams) {
                if (diagramTasks.size() >= diagramLimit || task == null) {
                    continue;
                }
                if (StrUtil.isBlank(task.getMermaidCode())) {
                    continue;
                }
                String description = WorkflowImageSupport.sanitizeText(task.getDescription(),
                        WorkflowImageSupport.MAX_DESCRIPTION_LENGTH);
                diagramTasks.add(new ImageCollectionPlan.DiagramTask(task.getMermaidCode(), description));
            }
        }

        List<ImageCollectionPlan.LogoTask> logoTasks = new ArrayList<>();
        List<ImageCollectionPlan.LogoTask> rawLogos = plan.getLogoTasks();
        if (rawLogos != null) {
            for (ImageCollectionPlan.LogoTask task : rawLogos) {
                if (logoTasks.size() >= logoLimit || task == null) {
                    continue;
                }
                String description = WorkflowImageSupport.sanitizeText(task.getDescription(),
                        WorkflowImageSupport.MAX_DESCRIPTION_LENGTH);
                if (StrUtil.isNotBlank(description)) {
                    logoTasks.add(new ImageCollectionPlan.LogoTask(description));
                }
            }
        }

        int requested = plan.totalTasks();
        ImageCollectionPlan cleaned = ImageCollectionPlan.builder()
                .contentImageTasks(contentTasks)
                .illustrationTasks(illustrationTasks)
                .diagramTasks(diagramTasks)
                .logoTasks(logoTasks)
                .build();
        if (requested > cleaned.totalTasks()) {
            // 模型通常会多规划几个任务，按配置裁剪属于正常行为，不作为用户告警。
            log.info("图片收集计划已按配置裁剪，requested={}，kept={}",
                    requested, cleaned.totalTasks());
        }
        if (cleaned.isEmpty()) {
            // 所有渠道都被显式关闭（maxTasks=0）时尊重配置，不再自动补任务。
            if (contentLimit == 0 && illustrationLimit == 0 && diagramLimit == 0 && logoLimit == 0) {
                return cleaned;
            }
            return ImageCollectionPlanFallback.from("");
        }
        return cleaned;
    }

    /**
     * 并发执行所有图片任务。
     */
    private List<ImageResource> executeTasks(ImageCollectionPlan plan, List<String> warnings) {
        List<Callable<TaskOutcome>> tasks = new ArrayList<>();
        addAvailabilityWarnings(plan, warnings);

        if (plan.getContentImageTasks() != null) {
            for (ImageCollectionPlan.ContentImageTask task : plan.getContentImageTasks()) {
                String query = task.getQuery();
                tasks.add(() -> runTask("内容图片", () -> contentImageSearchTool.searchContentImages(query)));
            }
        }
        if (plan.getIllustrationTasks() != null) {
            for (ImageCollectionPlan.IllustrationTask task : plan.getIllustrationTasks()) {
                String query = task.getQuery();
                tasks.add(() -> runTask("插画", () -> undrawIllustrationTool.searchIllustrations(query)));
            }
        }
        if (plan.getDiagramTasks() != null) {
            for (ImageCollectionPlan.DiagramTask task : plan.getDiagramTasks()) {
                String code = task.getMermaidCode();
                String description = task.getDescription();
                tasks.add(() -> runTask("架构图",
                        () -> mermaidDiagramTool.generateMermaidDiagram(code, description)));
            }
        }
        if (plan.getLogoTasks() != null) {
            for (ImageCollectionPlan.LogoTask task : plan.getLogoTasks()) {
                String description = task.getDescription();
                tasks.add(() -> runTask("Logo", () -> logoGeneratorTool.generateLogos(description)));
            }
        }
        if (tasks.isEmpty()) {
            return List.of();
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<TaskOutcome>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<TaskOutcome> task : tasks) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return new TaskOutcome(List.of(), "图片任务执行异常：" + e.getMessage());
                    }
                }, executor));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
            Duration timeout = properties.getImageCollectionTimeout();
            long timeoutMillis = timeout == null ? 240_000L : Math.max(10_000L, timeout.toMillis());
            try {
                all.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                warnings.add("图片收集超时，已使用已完成的结果");
                futures.forEach(future -> future.cancel(true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                warnings.add("图片收集被中断，已使用已完成的结果");
                futures.forEach(future -> future.cancel(true));
            } catch (Exception e) {
                warnings.add("图片收集部分失败：" + e.getMessage());
            }

            List<ImageResource> images = new ArrayList<>();
            for (CompletableFuture<TaskOutcome> future : futures) {
                TaskOutcome outcome;
                try {
                    outcome = future.getNow(null);
                } catch (Exception e) {
                    warnings.add("图片任务结果读取失败：" + e.getMessage());
                    continue;
                }
                if (outcome == null) {
                    continue;
                }
                if (StrUtil.isNotBlank(outcome.warning())) {
                    warnings.add(outcome.warning());
                }
                if (outcome.images() != null) {
                    images.addAll(outcome.images());
                }
            }
            return images;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 提前把“渠道未配置”作为告警返回，避免用户误以为图片缺失是模型的问题。
     */
    private void addAvailabilityWarnings(ImageCollectionPlan plan, List<String> warnings) {
        if (plan.getContentImageTasks() != null && !plan.getContentImageTasks().isEmpty()) {
            if (contentImageSearchTool.describeAvailability().values().stream().noneMatch(Boolean::booleanValue)) {
                warnings.add("没有可用的内容图片渠道（Pexels / Pixabay / Unsplash / 占位图均已关闭）");
            }
        }
        if (plan.getIllustrationTasks() != null && !plan.getIllustrationTasks().isEmpty()
                && !undrawIllustrationTool.available()) {
            warnings.add("unDraw 插画渠道未启用或不可用");
        }
        if (plan.getDiagramTasks() != null && !plan.getDiagramTasks().isEmpty()
                && !mermaidDiagramTool.available()) {
            warnings.add("架构图渠道不可用：请检查 Mermaid CLI、COS 配置或功能开关");
        }
        if (plan.getLogoTasks() != null && !plan.getLogoTasks().isEmpty()
                && !logoGeneratorTool.available()) {
            warnings.add("Logo 渠道未配置 DashScope API Key，已跳过 Logo 生成");
        }
    }

    /**
     * 按类别固定顺序输出，并对 URL 去重和总量截断。
     */
    private List<ImageResource> orderAndCap(List<ImageResource> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        Map<ImageCategoryEnum, Integer> order = new LinkedHashMap<>();
        order.put(ImageCategoryEnum.CONTENT, 0);
        order.put(ImageCategoryEnum.ILLUSTRATION, 1);
        order.put(ImageCategoryEnum.ARCHITECTURE, 2);
        order.put(ImageCategoryEnum.LOGO, 3);
        List<ImageResource> sorted = new ArrayList<>(images);
        sorted.sort(Comparator.comparingInt(image -> order.getOrDefault(image.getCategory(), 99)));
        return WorkflowImageSupport.dedupeAndCap(sorted, properties.getMaxTotalImages());
    }

    private static TaskOutcome runTask(String label, Supplier<List<ImageResource>> supplier) {
        try {
            List<ImageResource> images = supplier.get();
            if (images == null || images.isEmpty()) {
                return new TaskOutcome(List.of(), label + "任务未返回素材");
            }
            return new TaskOutcome(images, null);
        } catch (Exception e) {
            return new TaskOutcome(List.of(), label + "任务失败：" + e.getMessage());
        }
    }

    /**
     * 图片收集结果。
     *
     * @param plan     实际执行的计划
     * @param images   聚合后的图片
     * @param warnings 非致命告警
     */
    public record ImageCollectionOutcome(ImageCollectionPlan plan,
                                         List<ImageResource> images,
                                         List<String> warnings) {
    }

    private record TaskOutcome(List<ImageResource> images, String warning) {
    }
}

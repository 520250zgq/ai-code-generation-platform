package com.zgq.zgqaicodemother.workflow.state;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import org.bsc.langgraph4j.serializer.Serializer;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作流上下文的图状态序列化器。
 *
 * <p>LangGraph4j 在每次节点执行前会克隆状态，默认使用 Java 原生序列化。Java 序列化依赖
 * 类加载器解析类名，在 Spring Boot DevTools 的重启类加载器、插件化容器等环境中可能把
 * 同一个类解析成不同 Class 实例，导致状态里的上下文无法通过 {@code instanceof} 判断。</p>
 *
 * <p>这里改成显式字段序列化：顶层字段按基本类型写入，图片、计划、告警等复杂结构使用 JSON，
 * 反序列化时由当前类加载器重新构造对象。这样不依赖 Java 序列化对业务类的类名解析，
 * 也避免把 Spring Bean、文件句柄等不可序列化对象带进状态。</p>
 */
public class WorkflowContextSerializer implements Serializer<WorkflowContext> {

    @Override
    public void write(WorkflowContext context, ObjectOutput out) throws IOException {
        if (context == null) {
            // 正常情况下不会写入 null；保留兜底，避免序列化器抛 NPE 污染图执行。
            writeNullableString(null, out);
            return;
        }
        writeNullableString(context.getWorkflowId(), out);
        out.writeLong(context.getAppId() == null ? -1L : context.getAppId());
        out.writeLong(context.getUserId() == null ? -1L : context.getUserId());
        writeNullableString(context.getOriginalPrompt(), out);
        writeNullableString(context.getCurrentStep(), out);
        writeNullableString(context.getEnhancedPrompt(), out);
        writeNullableString(context.getGenerationType() == null
                ? null : context.getGenerationType().name(), out);
        writeNullableString(context.getGeneratedCodeDir(), out);
        writeNullableString(context.getBuildResultDir(), out);
        out.writeBoolean(context.isBuildSuccess());
        writeNullableString(context.getErrorMessage(), out);
        writeNullableString(context.getStartedAt() == null ? null : context.getStartedAt().toString(), out);
        writeNullableString(context.getFinishedAt() == null ? null : context.getFinishedAt().toString(), out);
        writeString(JSONUtil.toJsonStr(context.getImages() == null ? List.of() : context.getImages()), out);
        writeString(context.getImageCollectionPlan() == null
                ? ""
                : JSONUtil.toJsonStr(context.getImageCollectionPlan()), out);
        writeString(JSONUtil.toJsonStr(context.getWarnings() == null ? List.of() : context.getWarnings()), out);
    }

    @Override
    public WorkflowContext read(ObjectInput in) throws IOException, ClassNotFoundException {
        String workflowId = readNullableString(in);
        if (workflowId == null) {
            return null;
        }
        long appId = in.readLong();
        long userId = in.readLong();
        String originalPrompt = readNullableString(in);
        String currentStep = readNullableString(in);
        String enhancedPrompt = readNullableString(in);
        String generationType = readNullableString(in);
        String generatedCodeDir = readNullableString(in);
        String buildResultDir = readNullableString(in);
        boolean buildSuccess = in.readBoolean();
        String errorMessage = readNullableString(in);
        String startedAt = readNullableString(in);
        String finishedAt = readNullableString(in);
        String imagesJson = readString(in);
        String planJson = readString(in);
        String warningsJson = readString(in);

        List<ImageResource> images = StrUtil.isBlank(imagesJson)
                ? new ArrayList<>()
                : new ArrayList<>(JSONUtil.parseArray(imagesJson).toList(ImageResource.class));
        List<String> warnings = StrUtil.isBlank(warningsJson)
                ? new ArrayList<>()
                : new ArrayList<>(JSONUtil.parseArray(warningsJson).toList(String.class));
        ImageCollectionPlan plan = StrUtil.isBlank(planJson) || "null".equals(planJson)
                ? null : JSONUtil.toBean(planJson, ImageCollectionPlan.class);
        return WorkflowContext.builder()
                .workflowId(workflowId)
                .appId(appId < 0 ? null : appId)
                .userId(userId < 0 ? null : userId)
                .originalPrompt(originalPrompt)
                .currentStep(currentStep)
                .enhancedPrompt(enhancedPrompt)
                .generationType(parseType(generationType))
                .generatedCodeDir(generatedCodeDir)
                .buildResultDir(buildResultDir)
                .buildSuccess(buildSuccess)
                .errorMessage(errorMessage)
                .startedAt(parseDateTime(startedAt))
                .finishedAt(parseDateTime(finishedAt))
                .images(images)
                .imageCollectionPlan(plan)
                .warnings(warnings)
                .build();
    }

    private static void writeNullableString(String value, ObjectOutput out) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(value, out);
        }
    }

    private static String readNullableString(ObjectInput in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
    }

    /**
     * 使用长度前缀写入 UTF-8 字符串，避免 ObjectOutputStream#writeUTF 的 65535 字节限制。
     */
    private static void writeString(String value, ObjectOutput out) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(ObjectInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("非法的字符串长度：" + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static CodeGenTypeEnum parseType(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return CodeGenTypeEnum.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

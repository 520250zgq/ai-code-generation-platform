package com.zgq.zgqaicodemother.workflow.tools.provider;

import com.zgq.zgqaicodemother.workflow.model.ImageResource;

import java.io.IOException;
import java.util.List;

/**
 * 内容图片搜索渠道。
 *
 * <p>不同图片站点的鉴权方式和响应结构不同，但工作流只关心“关键词 -> 图片列表”。
 * 每个渠道独立实现，搜索工具负责按配置选择渠道、容错和降级。</p>
 */
public interface ContentImageProvider {

    /**
     * 渠道名称，用于日志和来源标记。
     *
     * @return 渠道名称
     */
    String name();

    /**
     * 是否已经配置好可以直接调用。
     *
     * @return 已配置返回 true
     */
    boolean available();

    /**
     * 搜索图片。
     *
     * @param query 搜索关键词
     * @param limit 期望返回数量
     * @return 图片列表，不能返回 null
     * @throws IOException 网络或响应解析失败
     */
    List<ImageResource> search(String query, int limit) throws IOException;
}

package com.zgq.zgqaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建应用请求，只接收初始化提示词。
 *
 * <p>单独定义创建请求的字段范围，避免客户端自行指定应用 id、所属用户、
 * 优先级等服务端管理的属性。应用名称等初始信息由创建逻辑生成；
 * 创建完成后的重命名使用 AppUpdateRequest，不重新提交初始化提示词。
 */
@Data
public class AppAddRequest implements Serializable {

    /**
     * 应用初始化的 prompt，创建时必填，用于描述希望生成的应用内容。
     */
    private String initPrompt;

    private static final long serialVersionUID = 1L;
}

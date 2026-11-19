package com.zgq.zgqaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户修改自己应用的请求，目前只开放名称修改。
 *
 * <p>与创建请求、管理员更新请求分开，明确普通用户可修改的字段范围，
 * 防止属性复制时一并修改初始化提示词、封面、优先级或创建者。
 * DTO 只定义可接收的字段，是否属于当前用户仍由控制层校验。
 */
@Data
public class AppUpdateRequest implements Serializable {

    /**
     * 待修改的应用 id，使用包装类型以区分未传值与有效 id。
     */
    private Long id;

    /**
     * 新应用名称，用户重命名时必填，不能为 null、空字符串或纯空白。
     */
    private String appName;

    private static final long serialVersionUID = 1L;
}

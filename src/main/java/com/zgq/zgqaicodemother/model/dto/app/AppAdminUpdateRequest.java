package com.zgq.zgqaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理员更新应用的请求，只开放名称、封面、优先级三个字段。
 *
 * <p>权限由接口的管理员鉴权注解保证，DTO 本身不承担鉴权。
 * 未传入的更新字段保持 null，配合当前 ORM 忽略 null 的更新方式保留原值；
 * 至少需要提交一个更新字段，避免只刷新编辑时间却未修改任何业务信息。
 */
@Data
public class AppAdminUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 新应用名称；null 表示保留原名称，传入时必须是非空白名称。
     */
    private String appName;

    /**
     * 新封面地址；null 表示保留原值，空字符串表示主动清空封面。
     */
    private String cover;

    /**
     * 新优先级；Integer 可区分未传入的 null 和有效值 0，0 可用于取消精选。
     */
    private Integer priority;

    private static final long serialVersionUID = 1L;
}

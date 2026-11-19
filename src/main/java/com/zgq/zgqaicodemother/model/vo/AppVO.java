package com.zgq.zgqaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 面向普通用户返回的应用视图，用于详情和分页列表。
 *
 * <p>与数据库实体分开定义响应字段，不返回内部逻辑删除标记 isDelete。
 * 创建者通过 UserVO 展示，避免把包含密码字段的 User 实体直接序列化给客户端；
 * 服务层负责复制应用信息并补充创建者，列表场景会批量查询创建者，减少重复访问数据库。
 */
@Data
public class AppVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    /**
     * 生成类型的中文展示文案，例如“Vue 工程模式”，便于前端直接展示。
     */
    private String codeGenTypeText;

    /**
     * 部署标识
     */
    private String deployKey;

    /**
     * 部署时间，应用尚未部署时可为 null。
     */
    private LocalDateTime deployedTime;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 业务编辑时间，由修改应用的接口主动刷新，用于表示最近一次业务编辑。
     */
    private LocalDateTime editTime;

    /**
     * 创建时间，数据库插入时默认生成，后续编辑不改变该值。
     */
    private LocalDateTime createTime;

    /**
     * 数据库行更新时间，由数据库的 ON UPDATE CURRENT_TIMESTAMP 维护，与业务编辑时间分开。
     */
    private LocalDateTime updateTime;

    /**
     * 创建用户的 UserVO，复用 User 模块的脱敏字段，不包含密码；创建者不存在时可为 null。
     */
    private UserVO user;

    private static final long serialVersionUID = 1L;
}

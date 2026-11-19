package com.zgq.zgqaicodemother.model.dto.app;

import com.zgq.zgqaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 应用分页查询请求，集中声明管理员可筛选的所有非时间字段。
 *
 * <p>管理员接口可使用这些筛选项；普通用户接口只复制名称、分页和排序参数，
 * 再由服务端设置当前用户或精选优先级，不能直接信任客户端传入的 userId、isDelete 等条件。
 * 字段为 null 通常表示不添加该筛选条件，具体模糊匹配与精确匹配规则由服务层实现。
 * 分页和排序参数继承自 PageRequest，排序字段仍需服务层白名单校验。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AppQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称，非空白时进行模糊查询；普通用户也可使用。
     */
    private String appName;

    /**
     * 应用封面，管理员精确查询；null 不筛选，空字符串可查询已清空封面的记录。
     */
    private String cover;

    /**
     * 应用初始化的 prompt，管理员使用非空白内容进行模糊查询。
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举值），管理员精确查询；null 不筛选，空字符串参与匹配。
     */
    private String codeGenType;

    /**
     * 部署标识，管理员精确查询；null 不筛选，空字符串参与匹配。
     */
    private String deployKey;

    /**
     * 优先级，管理员可精确筛选；精选列表的该值由服务端固定设置。
     */
    private Integer priority;

    /**
     * 创建用户 id，管理员可精确筛选；“我的应用”列表必须使用登录用户 id。
     */
    private Long userId;

    /**
     * 删除标记，仅管理员列表接受：null 使用 ORM 默认过滤，0 查未删除，1 查已删除。
     * 显式筛选时由服务层临时关闭默认逻辑删除条件，以免 isDelete = 1 与默认条件冲突。
     */
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}

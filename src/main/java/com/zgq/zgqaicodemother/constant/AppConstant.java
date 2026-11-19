package com.zgq.zgqaicodemother.constant;

/**
 * 应用模块共用的查询和校验约定。
 *
 * <p>将精选标记和用户分页限制集中定义，避免“我的应用”和“精选应用”等入口
 * 使用不同的数字约定。数据库中的默认值、字段容量也应与相应业务约定保持一致。
 */
public interface AppConstant {

    /**
     * 默认优先级，0 表示普通应用，也是管理员可设置的有效值。
     */
    int DEFAULT_PRIORITY = 0;

    /**
     * 精选应用的标记值；精选查询精确匹配 99，管理员改为其他值即可取消精选。
     */
    int GOOD_APP_PRIORITY = 99;

    /**
     * 普通用户两个列表接口共用的每页上限，避免放大单次查询；管理员不使用此上限。
     */
    int MAX_PAGE_SIZE = 20;

    /**
     * 应用名称校验上限，与 app.appName 的 varchar(256) 容量对应。
     */
    int MAX_APP_NAME_LENGTH = 256;

    /**
     * 应用封面地址校验上限，与 app.cover 的 varchar(1024) 容量对应。
     */
    int MAX_COVER_LENGTH = 1024;

    /**
     * 服务层初始化 prompt 的业务校验上限，独立于数据库 TEXT 类型的存储容量。
     */
    int MAX_INIT_PROMPT_LENGTH = 10000;

    /**
     * 服务层创建应用时从初始化提示词截取名称的长度，便于统一调整默认命名规则。
     */
    int DEFAULT_APP_NAME_LENGTH = 12;

    /**
     * 应用生成目录
     */
    String CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    /**
     * 应用部署目录
     */
    String CODE_DEPLOY_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_deploy";

    /**
     * 网页截图临时目录。
     *
     * <p>截图先落在本地，再由截图服务上传到对象存储；可通过
     * {@code -Dapp.screenshot.output-dir=...} 覆盖，便于测试和容器挂载临时卷。</p>
     */
    String SCREENSHOT_ROOT_DIR = System.getProperty("app.screenshot.output-dir",
            System.getProperty("user.dir") + "/tmp/screenshot");

}

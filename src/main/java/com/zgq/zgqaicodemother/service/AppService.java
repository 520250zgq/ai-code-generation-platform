package com.zgq.zgqaicodemother.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.zgq.zgqaicodemother.model.dto.app.AppAddRequest;
import com.zgq.zgqaicodemother.model.dto.app.AppQueryRequest;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用服务层：封装可复用的参数校验、数据库查询和视图转换能力。
 * 方法本身不读取 HTTP 登录态，调用方负责校验用户身份、操作权限和查询范围。
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
public interface AppService extends IService<App> {

    /**
     * 创建应用，应用名称和创建人由服务端设置。
     * 将创建规则集中到服务层，便于其他入口复用；调用前应先取得当前登录用户。
     *
     * @param appAddRequest 仅含初始化提示词的创建请求
     * @param loginUser     调用方从登录态获取的当前用户，不能来自客户端请求体
     * @return 新应用 id
     */
    long addApp(AppAddRequest appAddRequest, User loginUser);

    /**
     * 校验应用，新增时必须提供初始化提示词。
     * 修改采用局部更新，只校验实际提供的字段，避免要求调用方重复提交原有数据。
     *
     * @param app 待新增的实体或仅包含变更字段的更新实体
     * @param add true 表示创建并强制校验提示词，false 表示校验局部更新
     */
    void validApp(App app, boolean add);

    /**
     * 构造查询条件，并校验排序字段。
     * 名称和提示词支持模糊查询，其他业务字段使用精确匹配，时间字段只可用于排序。
     * 该方法不执行鉴权：普通用户入口必须先限定创建者或精选条件，并清除管理专用筛选字段。
     *
     * @param appQueryRequest 已由调用方限制访问范围的筛选及排序条件
     * @return 使用排序白名单构造的查询条件
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 获取应用详情，补充脱敏后的创建人信息。
     *
     * @param app 待转换的应用，允许为 null
     * @return 应用视图；应用为空时返回 null，创建者不存在时其 user 字段为 null
     */
    AppVO getAppVO(App app);

    /**
     * 批量转换应用，批量查询创建人信息。
     * 同一创建者只需查询一次，避免应用列表每条记录都额外访问用户表。
     *
     * @param appList 按目标顺序排列的应用列表，允许为 null 或空列表
     * @return 与原记录顺序一致的视图列表；空输入返回空列表
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 转换应用分页，并保留分页信息。
     * 保留页码、每页数量、总记录数和总页数，前端才能正确显示分页导航。
     *
     * @param appPage 已完成数据库查询的实体分页
     * @return 批量补充创建者信息的视图分页
     */
    Page<AppVO> getAppVOPage(Page<App> appPage);

    /**
     * 分页查询应用，指定删除标记时支持管理员查询逻辑删除的数据。
     * 调用方负责鉴权和用户接口的每页数量限制。
     * isDelete 非 null 时会临时关闭框架默认的删除过滤，只应向已鉴权的管理员开放该条件。
     *
     * @param appQueryRequest 查询条件，页码和每页数量必须大于 0
     * @return 数据库分页结果，未指定 isDelete 时自动排除已删除记录
     */
    Page<App> listAppByPage(AppQueryRequest appQueryRequest);

    /**
     * 删除应用及其对话历史；两项逻辑删除应在同一事务中完成。
     */
    boolean removeAppWithChatHistory(Long appId);

    /**
     * 以流式方式生成代码，并在每轮订阅中保存用户消息和最终 AI 回复。
     *
     * @param appId     应用 id
     * @param message   用户本轮输入
     * @param loginUser 当前登录用户，必须是应用创建者
     * @return 逐段输出的 SSE 内容流
     */
    Flux<String> chatToGenCode(Long appId, String message, User loginUser);

    /**
     * 把生成目录复制到部署目录并保存部署标识，返回可访问地址。
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户，必须是应用创建者
     * @return 部署后的访问 URL
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 把应用生成目录中的源码过滤后打包为 ZIP，直接写入 HTTP 响应。
     *
     * <p>只有应用创建者可以下载。下载的是生成目录而非部署目录：部署目录包含打包后的产物，
     * 而生成目录保存的是可继续编辑的源代码。</p>
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户，必须是应用创建者
     * @param response  用于输出 ZIP 文件和下载响应头的 HTTP 响应
     */
    void downloadAppCode(Long appId, User loginUser, HttpServletResponse response);


}

package com.zgq.zgqaicodemother.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.paginate.Page;
import com.zgq.annotation.AuthCheck;
import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.common.DeleteRequest;
import com.zgq.zgqaicodemother.common.ResultUtils;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.constant.UserConstant;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.exception.ThrowUtils;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.model.dto.app.*;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 应用控制层：负责请求校验、登录校验和资源归属校验，统一返回 BaseResponse。
 * 普通用户接口从 Session 获取身份，管理员专用接口由 AuthCheck 对应的切面校验角色。
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
@RestController
@RequestMapping("/app")
@Slf4j
public class AppController {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    /**
     * 创建应用并返回应用 id，初始化提示词是唯一允许由客户端指定的创建参数。
     * 生成类型由服务层调用 AI 智能路由选择；服务层统一负责名称、创建人和兜底策略。
     *
     * @param appAddRequest 创建应用请求
     * @param request       携带现有 User 模块 Session 登录态的请求
     * @return 应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        // 全空白提示词没有有效的应用需求，提前返回参数错误，避免写入无意义的数据。
        ThrowUtils.throwIf(StrUtil.isBlank(appAddRequest.getInitPrompt()), ErrorCode.PARAMS_ERROR,
                "初始化 prompt 不能为空");
        // 创建人必须来自登录态；不能信任客户端提供的 userId，否则可冒用其他用户创建应用。
        User loginUser = userService.getLoginUser(request);
        // 创建、命名、智能路由和持久化集中在服务层，避免控制器和其他入口出现两套创建规则。
        long appId = appService.addApp(appAddRequest, loginUser);
        return ResultUtils.success(appId);
    }


    /**
     * 修改自己的应用（仅支持应用名称）。
     * 管理员使用此接口也需要是创建者，跨用户更新请使用管理员专用接口。
     *
     * @param appUpdateRequest 仅包含应用 id 和新名称，避免普通用户修改封面、优先级等字段
     * @param request          用于读取当前登录用户
     * @return 更新成功标记，不存在、无权限或写入失败时抛出业务异常
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null
                || appUpdateRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(appUpdateRequest.getAppName()), ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        User loginUser = userService.getLoginUser(request);

        // 以数据库保存的创建者作为授权依据，不能仅凭客户端传入应用 id 就允许修改。
        App oldApp = appService.getById(appUpdateRequest.getId());
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        ThrowUtils.throwIf(!loginUser.getId().equals(oldApp.getUserId()), ErrorCode.NO_AUTH_ERROR);

        // 构造仅含允许修改字段的新实体；updateById 默认忽略 null，未提供的字段保留原值。
        App app = new App();
        BeanUtil.copyProperties(appUpdateRequest, app);
        appService.validApp(app, false);

        // editTime 表示本次业务编辑时间，updateTime 则由数据库在记录更新时自动维护。
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 删除应用（用户仅能删除自己的应用，管理员可删除任意应用）。
     * 两类角色共用此入口，通过创建者和管理员身份判断操作权限。
     *
     * @param deleteRequest 待删除应用的 id，不接受由客户端指定创建者或删除标记
     * @param request       用于读取当前登录用户及其角色
     * @return 删除成功标记
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null
                || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long appId = deleteRequest.getId();
        App oldApp = appService.getById(appId);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 满足“本人应用”或“管理员”任意条件即可删除，避免普通用户越权操作他人的应用。
        boolean isOwner = loginUser.getId().equals(oldApp.getUserId());
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        ThrowUtils.throwIf(!isOwner && !isAdmin, ErrorCode.NO_AUTH_ERROR);
        // 应用及其对话历史必须一起逻辑删除，避免留下无法归属的历史数据。
        boolean result = appService.removeAppWithChatHistory(appId);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取应用详情（含创建者的脱敏信息）。
     * 登录用户可以查看其他用户的应用，因此这里只校验登录，不限制应用归属。
     *
     * @param id      应用 id
     * @param request 用于校验登录态，即使不需要返回用户对象，也必须执行该校验
     * @return 面向普通用户的应用视图，通过 UserVO 返回创建者信息
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        userService.getLoginUser(request);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 分页查询自己的应用（支持按名称查询，每页最多 20 条）。
     *
     * @param appQueryRequest 名称及分页排序条件，管理员专用筛选字段会被丢弃
     * @param request         用于确定“我的应用”所对应的当前用户
     * @return 保留分页元数据、附带创建者脱敏信息的列表
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                      HttpServletRequest request) {
        AppQueryRequest queryRequest = getUserQueryRequest(appQueryRequest);
        User loginUser = userService.getLoginUser(request);
        // 先筛掉客户端的特权查询条件，再强制使用当前用户 id，防止请求查询其他人的应用。
        queryRequest.setUserId(loginUser.getId());
        Page<App> appPage = appService.listAppByPage(queryRequest);
        return ResultUtils.success(appService.getAppVOPage(appPage));
    }

    /**
     * 分页查询精选应用（支持按名称查询，每页最多 20 条）。
     * 精选由管理员设置的优先级决定，不按当前登录用户过滤创建者。
     *
     * @param appQueryRequest 名称及分页排序条件
     * @param request         用于校验访问者已登录
     * @return 优先级等于 GOOD_APP_PRIORITY 的应用视图分页
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                        HttpServletRequest request) {
        AppQueryRequest queryRequest = getUserQueryRequest(appQueryRequest);
        userService.getLoginUser(request);
        // 精选优先级由服务端指定，不能被客户端覆盖。
        queryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        Page<App> appPage = appService.listAppByPage(queryRequest);
        return ResultUtils.success(appService.getAppVOPage(appPage));
    }

    /**
     * 更新任意应用（仅管理员，支持名称、封面、优先级）。
     * AuthCheck 在进入方法前校验管理员角色，因此此处不再限制应用创建者。
     *
     * @param appAdminUpdateRequest id 必填，其他字段按需提供；封面为空字符串表示清空封面
     * @return 更新成功标记
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> adminUpdateApp(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        ThrowUtils.throwIf(appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null
                || appAdminUpdateRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        // null 表示不更新；priority=0 和 cover="" 都是有效修改，不能用“是否为空”统一判断。
        // 至少要求一个修改字段，避免只写入 editTime 却没有实际业务变更。
        ThrowUtils.throwIf(appAdminUpdateRequest.getAppName() == null && appAdminUpdateRequest.getCover() == null
                && appAdminUpdateRequest.getPriority() == null, ErrorCode.PARAMS_ERROR, "请至少填写一个更新字段");
        App oldApp = appService.getById(appAdminUpdateRequest.getId());
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 管理员 DTO 也只暴露允许维护的字段，避免改动创建者、初始化提示词或部署信息。
        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        appService.validApp(app, false);
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 分页查询应用（仅管理员，支持所有非时间字段，无每页数量上限）。
     * 允许按 isDelete 查看已删除记录，因此必须保留管理员鉴权，不能复用为普通用户入口。
     *
     * @param appQueryRequest 完整管理查询条件；页码及每页数量仍须为正数
     * @return 应用实体分页，供后台管理使用
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<App>> listAppByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appService.listAppByPage(appQueryRequest));
    }

    /**
     * 根据 id 获取应用（仅管理员）。
     * 返回实体以便管理端读取内部字段；常规 getById 仍遵循逻辑删除过滤。
     *
     * @param id 应用 id
     * @return 未被逻辑删除的应用实体
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<App> getAppById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(app);
    }

    /**
     * 校验普通用户的分页参数，只接受名称和分页排序参数。
     * 使用新请求对象逐项复制，避免 userId、priority、isDelete 等管理员查询条件被带入。
     * 尤其 isDelete 会触发服务层的删除记录查询能力，普通用户入口必须在此丢弃该字段。
     *
     * @param appQueryRequest 客户端原始请求，此方法不会修改它
     * @return 已校验大小且只保留普通用户可用字段的请求
     */
    private AppQueryRequest getUserQueryRequest(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        int pageNum = appQueryRequest.getPageNum();
        int pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageNum < 1 || pageSize < 1, ErrorCode.PARAMS_ERROR, "分页参数必须大于 0");
        // 20 条上限仅属于普通用户接口，不能放到共享的服务层以免限制管理员查询。
        ThrowUtils.throwIf(pageSize > AppConstant.MAX_PAGE_SIZE, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        AppQueryRequest queryRequest = new AppQueryRequest();
        queryRequest.setAppName(appQueryRequest.getAppName());
        queryRequest.setPageNum(pageNum);
        queryRequest.setPageSize(pageSize);
        queryRequest.setSortField(appQueryRequest.getSortField());
        queryRequest.setSortOrder(appQueryRequest.getSortOrder());
        return queryRequest;
    }

    /**
     * 应用聊天生成代码（流式 SSE）
     *
     * @param appId   应用 ID
     * @param message 用户消息
     * @param request 请求对象
     * @return 生成结果流
     */
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务生成代码（流式）
        Flux<String> contentFlux = appService.chatToGenCode(appId, message, loginUser);
        // 转换为 ServerSentEvent 格式
        return contentFlux
                .map(chunk -> {
                    // 将内容包装成JSON对象
                    Map<String, String> wrapper = Map.of("d", chunk);
                    String jsonData = JSONUtil.toJsonStr(wrapper);
                    return ServerSentEvent.<String>builder()
                            .data(jsonData)
                            .build();
                })
                .concatWith(Mono.just(
                        // 发送结束事件
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ))
                // 流式响应已经开始后无法再写普通 JSON 错误体；用 SSE error 事件通知前端，
                // 避免连接被直接断开时浏览器只显示 network error。
                .onErrorResume(error -> {
                    log.error("AI 代码生成流失败，appId={}", appId, error);
                    String reason = error instanceof BusinessException && StrUtil.isNotBlank(error.getMessage())
                            ? error.getMessage()
                            : "AI 生成中断，请重试";
                    String jsonData = JSONUtil.toJsonStr(Map.of("message", reason));
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data(jsonData)
                            .build());
                });
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 下载应用源代码（ZIP）。
     *
     * <p>响应体直接是二进制 ZIP，因此成功时不再包装 BaseResponse。创建者校验、生成目录定位和
     * 文件过滤由服务层完成；任何校验失败都会由全局异常处理器返回 JSON，前端需要同时判断
     * 响应类型，不能只依赖 HTTP 状态码。</p>
     *
     * @param appId    应用 ID
     * @param request  用于获取当前登录用户
     * @param response 用于输出 ZIP 文件和下载响应头
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        User loginUser = userService.getLoginUser(request);
        appService.downloadAppCode(appId, loginUser, response);
    }


}

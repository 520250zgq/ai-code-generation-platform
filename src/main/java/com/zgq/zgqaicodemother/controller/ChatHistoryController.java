package com.zgq.zgqaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.zgq.annotation.AuthCheck;
import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.common.ResultUtils;
import com.zgq.zgqaicodemother.constant.UserConstant;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.exception.ThrowUtils;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryAdminQueryRequest;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryCursorPage;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryCursorQueryRequest;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import com.zgq.zgqaicodemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话历史查询控制层。
 * 普通用户只能查询自己创建的应用；跨应用查询仅开放给管理员。
 */
@RestController
@RequestMapping("/chatHistory")
public class ChatHistoryController {

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    /**
     * 加载某个应用的消息历史。默认每次加载最新 10 条，递增 pageNum 可向前加载旧消息。
     *
     * @param queryRequest 应用 id 和页码
     * @param request      登录态
     * @return 聊天顺序排列的消息分页
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<ChatHistory>> listAppHistoryByPage(
            @RequestBody ChatHistoryQueryRequest queryRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(queryRequest == null || queryRequest.getAppId() == null
                        || queryRequest.getAppId() <= 0 || queryRequest.getPageNum() < 1
                        || queryRequest.getPageSize() != 10,
                ErrorCode.PARAMS_ERROR, "应用 id、页码必须为正数，且每页固定 10 条");
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(queryRequest.getAppId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isOwner = loginUser.getId().equals(app.getUserId());
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        ThrowUtils.throwIf(!isOwner && !isAdmin, ErrorCode.NO_AUTH_ERROR);
        return ResultUtils.success(chatHistoryService.listByAppId(queryRequest.getAppId(), queryRequest.getPageNum()));
    }

    /**
     * 通过稳定的时间和 id 复合游标读取更早的历史，首次请求时不传游标。
     */
    @PostMapping("/list/cursor")
    public BaseResponse<ChatHistoryCursorPage> listAppHistoryByCursor(
            @RequestBody ChatHistoryCursorQueryRequest queryRequest, HttpServletRequest request) {
        String cursorIdText = queryRequest == null ? null : queryRequest.getCursorId();
        Long cursorId = parseCursorId(cursorIdText);
        ThrowUtils.throwIf(queryRequest == null || queryRequest.getAppId() == null
                        || queryRequest.getAppId() <= 0 || queryRequest.getPageSize() < 1
                        || queryRequest.getPageSize() > 50
                        || (queryRequest.getCursorCreateTime() == null) != (cursorIdText == null)
                        || (cursorIdText != null && (cursorId == null || cursorId <= 0)),
                ErrorCode.PARAMS_ERROR, "应用 id、游标和每页数量无效");
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(queryRequest.getAppId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isOwner = loginUser.getId().equals(app.getUserId());
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        ThrowUtils.throwIf(!isOwner && !isAdmin, ErrorCode.NO_AUTH_ERROR);
        ChatHistoryCursorPage page = chatHistoryService.listByAppIdCursor(
                queryRequest.getAppId(), queryRequest.getCursorCreateTime(), cursorId, queryRequest.getPageSize());
        return ResultUtils.success(page);
    }

    /**
     * 把前端字符串游标解析为 Long，格式非法时返回 null 交给统一参数校验。
     */
    private Long parseCursorId(String cursorId) {
        if (cursorId == null) {
            return null;
        }
        try {
            return Long.valueOf(cursorId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 管理员查看所有应用的对话历史，按时间降序排序。
     *
     * @param queryRequest 分页参数和可选筛选项
     * @return 全局消息分页
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/admin/list/page")
    public BaseResponse<Page<ChatHistory>> listAllHistoryByPage(
            @RequestBody ChatHistoryAdminQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        if (queryRequest.getMessageType() != null) {
            ThrowUtils.throwIf(ChatHistoryMessageTypeEnum.getEnumByValue(queryRequest.getMessageType()) == null,
                    ErrorCode.PARAMS_ERROR, "消息类型无效");
        }
        return ResultUtils.success(chatHistoryService.listForAdmin(queryRequest));
    }
}

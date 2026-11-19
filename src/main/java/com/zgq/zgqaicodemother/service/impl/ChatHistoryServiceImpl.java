package com.zgq.zgqaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zgq.zgqaicodemother.constant.ChatHistoryConstant;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.exception.ThrowUtils;
import com.zgq.zgqaicodemother.mapper.ChatHistoryMapper;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryAdminQueryRequest;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryCursorPage;
import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import com.zgq.zgqaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史 服务层实现。
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {

    private static final int APP_PAGE_SIZE = 10;
    private static final int MAX_CURSOR_PAGE_SIZE = 50;

    /**
     * 保存一条用户或 AI 消息，并补齐时间、逻辑删除标记等审计字段。
     */
    @Override
    public void saveMessage(Long appId, Long userId, ChatHistoryMessageTypeEnum messageType, String message) {
        ThrowUtils.throwIf(appId == null || appId <= 0 || userId == null || userId <= 0,
                ErrorCode.PARAMS_ERROR, "应用或用户 id 无效");
        ThrowUtils.throwIf(messageType == null || StrUtil.isBlank(message),
                ErrorCode.PARAMS_ERROR, "消息类型或内容不能为空");
        LocalDateTime now = LocalDateTime.now();
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        chatHistory.setMessageType(messageType.getValue());
        chatHistory.setMessage(message);
        chatHistory.setCreateTime(now);
        chatHistory.setUpdateTime(now);
        chatHistory.setIsDelete(0);
        ThrowUtils.throwIf(!this.save(chatHistory), ErrorCode.OPERATION_ERROR, "保存对话历史失败");
    }

    /**
     * 逻辑删除指定应用的全部历史记录，供应用级联删除复用。
     */
    @Override
    public void removeByAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 无效");
        // ChatHistory.isDelete 配置为逻辑删除；应用删除时保留记录但从所有常规查询中排除。
        this.remove(QueryWrapper.create().eq("appId", appId));
    }

    /**
     * 按固定 10 条分页读取应用历史，并把当前页翻转为聊天窗口需要的正序。
     */
    @Override
    public Page<ChatHistory> listByAppId(Long appId, int pageNum) {
        ThrowUtils.throwIf(appId == null || appId <= 0 || pageNum < 1,
                ErrorCode.PARAMS_ERROR, "应用 id 和页码必须为正数");
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("createTime", false)
                .orderBy("id", false);
        Page<ChatHistory> page = this.page(new Page<>(pageNum, APP_PAGE_SIZE), queryWrapper);
        // 数据库以新到旧分页，翻转当前页供聊天窗口按旧到新顺序展示。
        ArrayList<ChatHistory> records = new ArrayList<>(page.getRecords());
        Collections.reverse(records);
        page.setRecords(records);
        return page;
    }

    /**
     * 使用 createTime + id 复合游标读取更早消息，避免偏移分页在并发写入时重复或遗漏。
     */
    @Override
    public ChatHistoryCursorPage listByAppIdCursor(Long appId, LocalDateTime cursorCreateTime,
                                                   Long cursorId, int pageSize) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 无效");
        ThrowUtils.throwIf(pageSize < 1 || pageSize > MAX_CURSOR_PAGE_SIZE,
                ErrorCode.PARAMS_ERROR, "每页数量必须在 1 到 50 之间");
        ThrowUtils.throwIf((cursorCreateTime == null) != (cursorId == null),
                ErrorCode.PARAMS_ERROR, "游标时间和游标 id 必须同时提供");
        ThrowUtils.throwIf(cursorId != null && cursorId <= 0,
                ErrorCode.PARAMS_ERROR, "游标 id 无效");

        // 查询结果为新到旧，多取一条判断是否仍有更早的历史；响应再翻转为旧到新。
        List<ChatHistory> records = new ArrayList<>(getMapper().selectByAppIdBefore(
                appId, cursorCreateTime, cursorId, pageSize + 1));
        boolean hasMore = records.size() > pageSize;
        if (hasMore) {
            records.remove(records.size() - 1);
        }
        Collections.reverse(records);

        ChatHistoryCursorPage result = new ChatHistoryCursorPage();
        result.setRecords(records);
        result.setHasMore(hasMore);
        if (hasMore && !records.isEmpty()) {
            // records 已按旧到新排列，首条消息是下一次查询所需的最旧位置。
            ChatHistory oldestRecord = records.get(0);
            result.setNextCursorCreateTime(oldestRecord.getCreateTime());
            result.setNextCursorId(String.valueOf(oldestRecord.getId()));
        }
        return result;
    }

    /**
     * 查询应用最近 limit 条消息并翻转为正序，供对话记忆回源使用。
     */
    @Override
    public List<ChatHistory> listRecentMessages(Long appId, int limit) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 无效");
        ThrowUtils.throwIf(limit < 1 || limit > ChatHistoryConstant.MAX_MEMORY_HISTORY_SIZE,
                ErrorCode.PARAMS_ERROR, "回源消息条数必须在 1 到 "
                        + ChatHistoryConstant.MAX_MEMORY_HISTORY_SIZE + " 之间");
        // 数据库按时间倒序取最近 limit 条，再翻转为聊天展示顺序。
        List<ChatHistory> records = new ArrayList<>(getMapper().selectRecentByAppId(appId, limit));
        Collections.reverse(records);
        return records;
    }

    /**
     * 管理员跨应用分页查询，按时间和 id 倒序返回最新消息。
     */
    @Override
    public Page<ChatHistory> listForAdmin(ChatHistoryAdminQueryRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        ThrowUtils.throwIf(request.getPageNum() < 1 || request.getPageSize() < 1,
                ErrorCode.PARAMS_ERROR, "页码和每页数量必须大于 0");
        ThrowUtils.throwIf(request.getAppId() != null && request.getAppId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用 id 无效");
        ThrowUtils.throwIf(request.getUserId() != null && request.getUserId() <= 0,
                ErrorCode.PARAMS_ERROR, "用户 id 无效");
        ThrowUtils.throwIf(request.getMessageType() != null
                        && ChatHistoryMessageTypeEnum.getEnumByValue(request.getMessageType()) == null,
                ErrorCode.PARAMS_ERROR, "消息类型无效");
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", request.getAppId())
                .eq("userId", request.getUserId())
                .eq("messageType", request.getMessageType())
                .orderBy("createTime", false)
                .orderBy("id", false);
        return this.page(new Page<>(request.getPageNum(), request.getPageSize()), queryWrapper);
    }

    /**
     * 兼容字符串消息类型的保存入口，先转换为枚举再复用统一保存逻辑。
     */
    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        ChatHistoryMessageTypeEnum type = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);
        saveMessage(appId, userId, type, message);
        return true;
    }

    /**
     * 兼容旧命名的关联删除入口，实际仍执行逻辑删除。
     */
    @Override
    public boolean deleteByAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 无效");
        return this.remove(QueryWrapper.create().eq("appId", appId));
    }

}

package com.zgq.zgqaicodemother.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryAdminQueryRequest;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryCursorPage;
import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import com.zgq.zgqaicodemother.model.enums.ChatHistoryMessageTypeEnum;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 保存一条用户或 AI 消息。
     */
    void saveMessage(Long appId, Long userId, ChatHistoryMessageTypeEnum messageType, String message);

    /**
     * 逻辑删除指定应用的全部对话历史。
     */
    void removeByAppId(Long appId);

    /**
     * 查询一个应用的固定 10 条消息分页，单页按聊天展示顺序正序返回。
     */
    Page<ChatHistory> listByAppId(Long appId, int pageNum);

    /**
     * 使用 createTime 和 id 作为复合游标，查询指定应用中游标之前的更早消息。
     * 首次查询时游标时间和 id 都传 null；结果按聊天展示顺序（旧到新）返回。
     */
    ChatHistoryCursorPage listByAppIdCursor(Long appId, LocalDateTime cursorCreateTime,
                                            Long cursorId, int pageSize);

    /** 兼容字符串消息类型的保存入口。 */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /** 兼容旧命名；关联记录仍通过逻辑删除处理。 */
    boolean deleteByAppId(Long appId);

    /**
     * 管理员跨应用分页查询，按消息时间和 id 降序排列。
     */
    Page<ChatHistory> listForAdmin(ChatHistoryAdminQueryRequest request);

    /**
     * 查询应用最近 limit 条消息，按聊天展示顺序（旧到新）返回。
     * 用于 Redis 对话记忆过期或服务重启后回源数据库。
     *
     * @param appId 应用 id
     * @param limit 最大消息条数，取值 1 到 200
     * @return 按时间正序排列的消息
     */
    List<ChatHistory> listRecentMessages(Long appId, int limit);

}

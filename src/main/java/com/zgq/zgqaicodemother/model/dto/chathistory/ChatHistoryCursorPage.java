package com.zgq.zgqaicodemother.model.dto.chathistory;

import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** 游标查询结果，消息按聊天展示顺序（旧到新）排列。 */
@Data
public class ChatHistoryCursorPage implements Serializable {

    private List<ChatHistory> records;

    private boolean hasMore;

    /** 下一次读取更早消息时原样传回的时间游标。 */
    private LocalDateTime nextCursorCreateTime;

    /** 字符串承载雪花 id，避免 JavaScript number 精度损失。 */
    private String nextCursorId;

    private static final long serialVersionUID = 1L;
}

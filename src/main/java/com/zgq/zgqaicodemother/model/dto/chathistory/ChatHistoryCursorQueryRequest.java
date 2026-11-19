package com.zgq.zgqaicodemother.model.dto.chathistory;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 单应用对话历史游标查询请求。首次请求不传游标，后续使用响应中的游标字段。 */
@Data
public class ChatHistoryCursorQueryRequest implements Serializable {

    private Long appId;

    private LocalDateTime cursorCreateTime;

    /** 使用字符串接收雪花 id，避免 JavaScript number 精度损失。 */
    private String cursorId;

    private int pageSize = 10;

    private static final long serialVersionUID = 1L;
}

package com.zgq.zgqaicodemother.model.dto.chathistory;

import com.zgq.zgqaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 查询单个应用对话历史的请求。
 * 每页固定 10 条，通过递增 pageNum 向前加载更早的消息。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryQueryRequest extends PageRequest implements Serializable {

    /** 要查询的应用 id。 */
    private Long appId;

    private static final long serialVersionUID = 1L;
}

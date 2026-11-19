package com.zgq.zgqaicodemother.model.dto.chathistory;

import com.zgq.zgqaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 管理员全局对话历史分页请求，可选按应用筛选。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryAdminQueryRequest extends PageRequest implements Serializable {

    private Long appId;

    private Long userId;

    private String messageType;

    private static final long serialVersionUID = 1L;
}

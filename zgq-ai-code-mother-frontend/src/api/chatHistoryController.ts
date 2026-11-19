// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 游标加载一个应用的聊天历史；首次请求省略 cursor 字段。 */
export async function listChatHistoryByCursor(
  body: API.ChatHistoryCursorQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseChatHistoryCursorPage>('/chatHistory/list/cursor', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 管理员按可选条件分页查询全部聊天历史。 */
export async function listChatHistoryForAdmin(
  body: API.ChatHistoryAdminQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageChatHistory>('/chatHistory/admin/list/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

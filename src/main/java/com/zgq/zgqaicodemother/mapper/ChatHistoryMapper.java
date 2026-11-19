package com.zgq.zgqaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 映射层。
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 */
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    /**
     * 以稳定的 createTime/id 复合游标读取较旧记录。额外多读一条供服务层判断是否还有下一页。
     */
    @Select("""
            <script>
            SELECT id, message, messageType, appId, userId, createTime, updateTime, isDelete
            FROM chat_history
            WHERE appId = #{appId}
              AND isDelete = 0
            <if test='cursorCreateTime != null and cursorId != null'>
              AND (createTime &lt; #{cursorCreateTime}
                   OR (createTime = #{cursorCreateTime} AND id &lt; #{cursorId}))
            </if>
            ORDER BY createTime DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ChatHistory> selectByAppIdBefore(@Param("appId") Long appId,
                                         @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                         @Param("cursorId") Long cursorId,
                                         @Param("limit") int limit);

    /**
     * 读取应用最近的 limit 条消息，按时间倒序返回，由服务层翻转为正序。
     */
    @Select("""
            SELECT id, message, messageType, appId, userId, createTime, updateTime, isDelete
            FROM chat_history
            WHERE appId = #{appId}
              AND isDelete = 0
            ORDER BY createTime DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChatHistory> selectRecentByAppId(@Param("appId") Long appId, @Param("limit") int limit);

}

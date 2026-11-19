package com.zgq.zgqaicodemother.service.impl;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.mapper.ChatHistoryMapper;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryAdminQueryRequest;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryCursorPage;
import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryServiceImplTest {

    private static final long APP_ID = 23L;

    @Mock
    private ChatHistoryMapper chatHistoryMapper;

    private ChatHistoryServiceImpl chatHistoryService;

    @BeforeEach
    void setUp() {
        chatHistoryService = new ChatHistoryServiceImpl();
        ReflectionTestUtils.setField(chatHistoryService, "mapper", chatHistoryMapper);
    }

    @Test
    void cursorQueryReturnsChronologicalPageAndCursorToOldestIncludedRecord() {
        LocalDateTime oldestTime = LocalDateTime.parse("2026-11-08T10:00:00");
        LocalDateTime middleTime = LocalDateTime.parse("2026-11-08T10:00:01");
        LocalDateTime newestTime = LocalDateTime.parse("2026-11-08T10:00:02");
        ChatHistory newest = record(303L, newestTime);
        ChatHistory middle = record(202L, middleTime);
        ChatHistory extraOlder = record(101L, oldestTime);
        when(chatHistoryMapper.selectByAppIdBefore(APP_ID, null, null, 3))
                .thenReturn(List.of(newest, middle, extraOlder));

        ChatHistoryCursorPage page = chatHistoryService.listByAppIdCursor(APP_ID, null, null, 2);

        assertEquals(List.of(middle, newest), page.getRecords());
        assertTrue(page.isHasMore());
        assertEquals(middleTime, page.getNextCursorCreateTime());
        assertEquals("202", page.getNextCursorId());
        verify(chatHistoryMapper).selectByAppIdBefore(APP_ID, null, null, 3);
    }

    @Test
    void cursorQueryWithoutMoreReturnsChronologicalPageAndNoCursor() {
        LocalDateTime olderTime = LocalDateTime.parse("2026-11-08T10:00:00");
        LocalDateTime newerTime = LocalDateTime.parse("2026-11-08T10:00:01");
        ChatHistory newer = record(202L, newerTime);
        ChatHistory older = record(101L, olderTime);
        when(chatHistoryMapper.selectByAppIdBefore(APP_ID, null, null, 3))
                .thenReturn(List.of(newer, older));

        ChatHistoryCursorPage page = chatHistoryService.listByAppIdCursor(APP_ID, null, null, 2);

        assertEquals(List.of(older, newer), page.getRecords());
        assertFalse(page.isHasMore());
        assertNull(page.getNextCursorCreateTime());
        assertNull(page.getNextCursorId());
    }

    @Test
    void cursorQueryPassesStableCursorToMapper() {
        LocalDateTime cursorTime = LocalDateTime.parse("2026-11-08T09:59:59");
        when(chatHistoryMapper.selectByAppIdBefore(APP_ID, cursorTime, 99L, 6))
                .thenReturn(List.of());

        ChatHistoryCursorPage page = chatHistoryService.listByAppIdCursor(APP_ID, cursorTime, 99L, 5);

        assertTrue(page.getRecords().isEmpty());
        assertFalse(page.isHasMore());
        verify(chatHistoryMapper).selectByAppIdBefore(APP_ID, cursorTime, 99L, 6);
    }

    @Test
    void rejectHalfCursorAndOversizedPageBeforeQuerying() {
        assertThrows(BusinessException.class,
                () -> chatHistoryService.listByAppIdCursor(APP_ID, null, 7L, 10));
        assertThrows(BusinessException.class,
                () -> chatHistoryService.listByAppIdCursor(APP_ID, null, null, 51));
    }

    @Test
    void recentMessagesAreReversedToChronologicalOrder() {
        LocalDateTime olderTime = LocalDateTime.parse("2026-11-08T10:00:00");
        LocalDateTime newerTime = LocalDateTime.parse("2026-11-08T10:00:01");
        ChatHistory newer = record(202L, newerTime);
        ChatHistory older = record(101L, olderTime);
        when(chatHistoryMapper.selectRecentByAppId(APP_ID, 2)).thenReturn(List.of(newer, older));

        List<ChatHistory> records = chatHistoryService.listRecentMessages(APP_ID, 2);

        assertEquals(List.of(older, newer), records);
        verify(chatHistoryMapper).selectRecentByAppId(APP_ID, 2);
    }

    @Test
    void rejectInvalidRecentMessageLimitBeforeQuerying() {
        assertThrows(BusinessException.class, () -> chatHistoryService.listRecentMessages(APP_ID, 0));
        assertThrows(BusinessException.class, () -> chatHistoryService.listRecentMessages(APP_ID, 201));
        assertThrows(BusinessException.class, () -> chatHistoryService.listRecentMessages(0L, 10));
    }

    @Test
    void rejectInvalidAdminFiltersBeforeQuerying() {
        ChatHistoryAdminQueryRequest request = new ChatHistoryAdminQueryRequest();
        request.setPageSize(0);
        assertThrows(BusinessException.class, () -> chatHistoryService.listForAdmin(request));

        request.setPageSize(10);
        request.setAppId(0L);
        assertThrows(BusinessException.class, () -> chatHistoryService.listForAdmin(request));

        request.setAppId(APP_ID);
        request.setUserId(-1L);
        assertThrows(BusinessException.class, () -> chatHistoryService.listForAdmin(request));

        request.setUserId(8L);
        request.setMessageType("system");
        assertThrows(BusinessException.class, () -> chatHistoryService.listForAdmin(request));
    }

    private static ChatHistory record(long id, LocalDateTime createTime) {
        ChatHistory record = new ChatHistory();
        record.setId(id);
        record.setCreateTime(createTime);
        return record;
    }
}

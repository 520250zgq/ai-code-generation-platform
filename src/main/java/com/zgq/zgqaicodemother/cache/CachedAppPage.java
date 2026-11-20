package com.zgq.zgqaicodemother.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mybatisflex.core.paginate.Page;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 可安全序列化到 Redis 的应用分页快照。
 *
 * <p>不直接缓存 MyBatis-Flex 的 {@link Page}，因为 {@code Page} 包含框架内部字段和配置，
 * 框架升级后字段增删可能导致旧缓存反序列化失败。这里只保留首页展示和分页所需的最小字段，
 * 记录内容是已经脱敏的 {@link AppVO}（其中 {@code UserVO} 不包含密码），不保存数据库实体。</p>
 *
 * <p>新增字段时 {@link JsonIgnoreProperties#ignoreUnknown()} 让旧版本代码可以忽略未知字段，
 * 便于滚动发布期间兼容新旧两种缓存格式。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CachedAppPage implements Serializable {

    /**
     * 当前页的应用视图列表。缓存中只保存展示字段，不包含用户密码等敏感信息；
     * 反序列化后由 {@link #toPage()} 转换为分页对象，为 null 时按空列表处理。
     */
    private List<AppVO> records;

    /**
     * 当前页码，从 1 开始。非法值（小于 1）在 {@link #toPage()} 中回退为 1。
     */
    private int pageNumber;

    /**
     * 每页条数。非法值（小于 1）在 {@link #toPage()} 中回退为 1，避免构造分页对象时抛异常。
     */
    private int pageSize;

    /**
     * 满足条件的总记录数。分页对象据此计算总页数和前端分页器。
     */
    private long totalRow;

    /**
     * 总页数。框架可能返回 -1 或 0 表示未计算，此时由 {@link #toPage()} 根据总记录数兜底计算。
     */
    private int totalPage;

    private static final long serialVersionUID = 1L;

    /**
     * 把 MyBatis-Flex 分页对象转换为缓存友好的快照。
     *
     * @param page 数据库分页结果，可为 null
     * @return 缓存快照；入参为 null 时返回 null，由调用方跳过缓存
     */
    public static CachedAppPage of(Page<AppVO> page) {
        if (page == null) {
            return null;
        }
        CachedAppPage cachedPage = new CachedAppPage();
        cachedPage.setRecords(page.getRecords() == null
                ? List.<AppVO>of() : new ArrayList<>(page.getRecords()));
        cachedPage.setPageNumber(toInt(page.getPageNumber()));
        cachedPage.setPageSize(toInt(page.getPageSize()));
        cachedPage.setTotalRow(page.getTotalRow());
        cachedPage.setTotalPage(toInt(page.getTotalPage()));
        return cachedPage;
    }

    /**
     * 还原为 MyBatis-Flex 分页对象，供 Controller 直接返回。
     *
     * <p>页码和每页条数先做下限保护；总页数非法（小于等于 0）时按
     * {@code ceil(totalRow / pageSize)} 兜底，避免前端拿到 -1 页。records 为 null 时使用空列表。</p>
     *
     * @return 分页对象，records 永不为 null
     */
    public Page<AppVO> toPage() {
        int safePageNumber = pageNumber > 0 ? pageNumber : 1;
        int safePageSize = pageSize > 0 ? pageSize : 1;
        long safeTotalRow = Math.max(totalRow, 0L);
        Page<AppVO> page = new Page<>(safePageNumber, safePageSize, safeTotalRow);
        page.setTotalPage(totalPage > 0 ? totalPage : calcTotalPage(safeTotalRow, safePageSize));
        page.setRecords(records == null ? List.<AppVO>of() : new ArrayList<>(records));
        return page;
    }

    /**
     * 按向上取整计算总页数，并防止 long 结果超出 DTO 的 int 字段范围。
     */
    private static long calcTotalPage(long totalRow, int pageSize) {
        if (totalRow <= 0 || pageSize <= 0) {
            return 0L;
        }
        long totalPage = totalRow / pageSize + (totalRow % pageSize == 0 ? 0L : 1L);
        return Math.min(totalPage, Integer.MAX_VALUE);
    }

    /**
     * 把框架的 long 分页字段收敛到 DTO 的 int 字段；只做溢出保护，保留负值等原始信息，
     * 由 {@link #toPage()} 在还原时统一处理非法值。
     */
    private static int toInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}

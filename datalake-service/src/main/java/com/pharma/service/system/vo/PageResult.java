package com.pharma.service.system.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 统一分页结果：锁定 {records,total,page,size,pages}，不暴露 MyBatis-Plus IPage 内部字段。
 */
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;
    private long pages;

    public PageResult() {}

    public static <T> PageResult<T> of(IPage<T> p) {
        PageResult<T> r = new PageResult<>();
        r.records = p.getRecords();
        r.total = p.getTotal();
        r.page = p.getCurrent();
        r.size = p.getSize();
        r.pages = p.getPages();
        return r;
    }

    public List<T> getRecords() { return records; }
    public void setRecords(List<T> records) { this.records = records; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long getPage() { return page; }
    public void setPage(long page) { this.page = page; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public long getPages() { return pages; }
    public void setPages(long pages) { this.pages = pages; }
}

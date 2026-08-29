package com.smartsupply.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PageQuery(
        @Min(value = 1, message = "页码最小为1") Integer page,
        @Min(value = 1, message = "每页至少1条") @Max(value = 200, message = "每页最多200条") Integer size
) {
    public int pageOrDefault() { return page == null || page < 1 ? 1 : page; }
    public int sizeOrDefault() { return size == null || size < 1 ? 10 : Math.min(size, 200); }
    public int offset() { return (pageOrDefault() - 1) * sizeOrDefault(); }
}

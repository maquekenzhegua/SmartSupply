package com.smartsupply.common;

import java.util.List;

public record PageResult<T>(List<T> records, long total, long current, long size) {}

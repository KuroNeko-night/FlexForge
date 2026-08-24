package com.flexforge.meta.domain;

import java.util.regex.Pattern;

/**
 * 元数据标识符规则（FR-META-05）：实体/字段名为小写 snake_case（列名安全，≤63 字符），
 * 显示名 1..100 字符。非法值在 API 边界拒绝，不做静默规范化（改名属显式 breaking 流程）。
 */
public final class Identifiers {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private Identifiers() {
    }

    public static void validateName(String value, String label) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " 须为 1..63 位小写字母开头的 snake_case（小写字母/数字/下划线）");
        }
    }

    public static void validateDisplayName(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(label + " 长度须在 1..100");
        }
    }
}

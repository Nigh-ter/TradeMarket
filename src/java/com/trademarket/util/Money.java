package com.trademarket.util;

import java.util.Locale;

/**
 * 金额工具：内部一律使用 long"分"（cents），杜绝浮点误差；
 * 只在与 Vault 交互的边界做 double 转换。
 */
public final class Money {

    private Money() {
    }

    public static final long MAX_CENTS = 9_999_999_999_99L; // 9999999999.99

    /** 元字符串 -> 分；非法返回 null。支持 1k / 1w / 2m 后缀与货币符号（仅字母后缀，避免汉字输入问题）。 */
    public static Long parse(String input) {
        if (input == null) return null;
        String s = input.trim()
                .replace(",", "")
                .replace("￥", "")
                .replace("$", "")
                .toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'w') { mult = 10_000L; s = s.substring(0, s.length() - 1); }
        else if (last == 'k') { mult = 1_000L; s = s.substring(0, s.length() - 1); }
        else if (last == 'm') { mult = 1_000_000L; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s) * mult;
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) return null;
            long cents = Math.round(v * 100.0);
            if (cents < 0 || cents > MAX_CENTS) return null;
            return cents;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static double toVault(long cents) {
        return cents / 100.0;
    }

    public static long fromVault(double amount) {
        long cents = Math.round(amount * 100.0);
        return Math.max(0, Math.min(cents, MAX_CENTS));
    }

    /** 123456 -> "1,234.56" */
    public static String format(long cents) {
        long abs = Math.abs(cents);
        String raw = String.format("%d.%02d", abs / 100, abs % 100);
        StringBuilder sb = new StringBuilder();
        String intPart = raw.substring(0, raw.length() - 3);
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) sb.append(',');
            sb.append(intPart.charAt(i));
        }
        String out = sb + raw.substring(raw.length() - 3);
        return cents < 0 ? "-" + out : out;
    }
}

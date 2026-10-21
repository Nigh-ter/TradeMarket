import com.trademarket.util.Money;

/**
 * 数字边界测试：NaN / Infinity / 超长数字 / 精度 / 溢出 / 税收极值。
 */
public class EdgeCheck {

    static int fails = 0;

    static void check(String name, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) { fails++; System.out.println("[FAIL] " + name + " 期望 " + expected + " 实际 " + actual); }
    }

    public static void main(String[] args) {
        // ---- 解析边界：危险输入必须返回 null ----
        check("NaN", Money.parse("NaN"), null);
        check("Infinity", Money.parse("Infinity"), null);
        check("-Infinity", Money.parse("-Infinity"), null);
        check("1e400(溢出为Inf)", Money.parse("1e400"), null);
        check("400位长数字", Money.parse("1".repeat(400)), null);
        check("超大整数", Money.parse("99999999999999"), null);          // 超过 MAX_CENTS
        check("MAX_CENTS 边界内", Money.parse("9999999999.99"), 999999999999L);
        check("负数", Money.parse("-5"), null);
        check("空串", Money.parse(""), null);
        check("纯字母", Money.parse("abc"), null);
        check("0.001(舍入为0分)", Money.parse("0.001"), 0L);
        check("1k", Money.parse("1k"), 100000L);
        check("9999999999.99k(溢出)", Money.parse("9999999999.99k"), null);
        // 千分位/符号
        check("1,234.5", Money.parse("1,234.5"), 123450L);
        check("$50", Money.parse("$50"), 5000L);

        // ---- format 负数安全（内部不应出现，但兜底）----
        check("format 负数", Money.format(-12345L), "-123.45");
        check("format MAX", Money.format(999999999999L), "9,999,999,999.99");

        // ---- 税收极值：100% 税 → 收入 0；不出现负收入 ----
        long total = 999999999999L;
        long tax = Math.round(total * 100.0 / 100.0);
        check("100%税=全额", tax, total);
        check("100%税收入非负", total - tax >= 0, true);
        // 150% 会被 clampPercent 拦截（Settings 层），这里验证计算本身不溢出
        tax = Math.round(total * 150.0 / 100.0);
        check("150%税计算不溢出", tax > 0 && tax < Long.MAX_VALUE, true);

        // ---- 托管总额极值：最大单价 × 最大数量 ----
        long unit = 100000000000L;   // 10 亿元/件（max-price 1亿 × 1000 单？
        int amount = 1000;
        long escrow = unit * amount;
        check("托管总额不溢出", escrow > 0 && escrow < Long.MAX_VALUE / 2, true);
        check("托管税计算不溢出", Math.round(escrow * 100.0 / 100.0) > 0, true);

        // ---- fromVault 对极端 double 的饱和处理 ----
        check("fromVault(Double.MAX)", Money.fromVault(Double.MAX_VALUE), 999999999999L);
        check("fromVault(负数)", Money.fromVault(-100.0), 0L);
        check("fromVault(NaN)", Money.fromVault(Double.NaN), 0L);

        System.out.println(fails == 0 ? "===== EDGE CHECK PASSED =====" : "===== EDGE CHECK FAILED: " + fails + " =====");
        System.exit(fails == 0 ? 0 : 1);
    }
}

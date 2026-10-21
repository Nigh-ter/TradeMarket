package com.trademarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** config.yml 的强类型封装；/market reload 时整体替换实例。金额字段单位一律为"分"。 */
public final class Settings {

    public final long minPriceCents;
    public final long maxPriceCents;
    public final double marketFeePercent;
    public final long marketFeeMaxCents;
    public final double marketSaleTaxPercent;
    public final int maxListingsPerPlayer;
    public final int listingExpireDays;
    public final Set<Material> blacklist;

    public final double auctionFeePercent;
    public final long auctionMinStartCents;
    public final long auctionMinIncrementCents;
    public final long auctionMaxPriceCents;
    public final int defaultDurationMin;
    public final int maxDurationMin;
    public final int maxAuctionsPerPlayer;
    public final double auctionSaleTaxPercent;
    public final int antiSnipeSeconds;
    public final int antiSnipeExtendSeconds;
    public final boolean broadcastAuction;
    public final boolean broadcastOrders;
    /** 拍卖结束前倒计时提醒阈值（秒，降序）；空 = 关闭。剩余时间每到一个阈值播报一次。 */
    public final java.util.List<Integer> countdownSecondsList;

    public final boolean mailNotifyOnJoin;
    public final int mailPurgeClaimedDays;
    /** 玩家互发邮件手续费（分）；0 = 免费。 */
    public final long mailFeeCents;

    public final double priceConfirmMultiplier;
    public final int orderExpireDays;
    public final int orderMaxPerPlayer;
    public final int orderMaxAmount;
    /** LuckPerms 组名(小写) -> 求购单上限覆盖。 */
    public final java.util.Map<String, Integer> groupOrderLimit;
    public final double orderSaleTaxPercent;
    /** 求购申请托管超时（小时）；0 = 永不超时。 */
    public final int offerTimeoutHours;
    /** 求购申请主动取消的手续费（分）。 */
    public final long offerCancelFeeCents;
    public final int txlogKeepDays;
    public final int terminalKeepDays;
    public final int salesKeepDays;
    public final boolean customItemDetect;
    /** 禁用交易功能的世界名（小写）。 */
    public final java.util.Set<String> disabledWorlds;
    /** OP 是否豁免世界限制。 */
    public final boolean worldOpExempt;
    /** 分世界白名单：世界名(小写) -> 允许的子命令集合。 */
    public final java.util.Map<String, java.util.Set<String>> worldAllow;
    /** 未领邮件超过 N 天时上线二次提醒；0 = 关闭。 */
    public final int oldRemindDays;

    /** 语言文件名（zh_CN / en_US，config 的 language 归一化而来）。 */
    public final String language;
    public final String currencySymbol;
    public final boolean soundsEnabled;

    public Settings(FileConfiguration c) {
        minPriceCents = MoneyCompat.cents(c, "market.min-price", 1.0);
        maxPriceCents = MoneyCompat.cents(c, "market.max-price", 10_000_000.0);
        marketFeePercent = clampPercent(c.getDouble("market.fee-percent", 0));
        marketFeeMaxCents = MoneyCompat.cents(c, "market.fee-max", 0);
        marketSaleTaxPercent = clampPercent(c.getDouble("market.sale-tax-percent", 0));
        maxListingsPerPlayer = Math.max(1, c.getInt("market.max-listings-per-player", 45));
        listingExpireDays = Math.max(1, c.getInt("market.listing-expire-days", 14));
        blacklist = parseBlacklist(c.getStringList("market.blacklist-materials"));

        auctionFeePercent = clampPercent(c.getDouble("auction.fee-percent", 0));
        auctionMinStartCents = MoneyCompat.cents(c, "auction.min-start-price", 1.0);
        auctionMinIncrementCents = MoneyCompat.cents(c, "auction.min-increment", 1.0);
        auctionMaxPriceCents = MoneyCompat.cents(c, "auction.max-price", 100_000_000.0);
        defaultDurationMin = clampRange(c.getInt("auction.default-duration-min", 60), 1, 10080);
        maxDurationMin = clampRange(c.getInt("auction.max-duration-min", 1440), 1, 10080);
        maxAuctionsPerPlayer = Math.max(1, c.getInt("auction.max-auctions-per-player", 20));
        auctionSaleTaxPercent = clampPercent(c.getDouble("auction.sale-tax-percent", 0));
        antiSnipeSeconds = clampRange(c.getInt("auction.anti-snipe-seconds", 30), 0, 3600);
        antiSnipeExtendSeconds = clampRange(c.getInt("auction.anti-snipe-extend-seconds", 30), 5, 3600);
        broadcastAuction = c.getBoolean("auction.broadcast-wins", true);
        broadcastOrders = c.getBoolean("order.broadcast-created", true);
        java.util.List<Integer> cds = new java.util.ArrayList<>();
        if (c.isList("auction.countdown-seconds")) {
            for (Object v : c.getList("auction.countdown-seconds")) {
                try { cds.add(Integer.parseInt(String.valueOf(v))); } catch (NumberFormatException ignored) {}
            }
        } else {
            try { cds.add(c.getInt("auction.countdown-seconds", 60)); } catch (Exception ignored) {}
        }
        java.util.Collections.sort(cds, java.util.Collections.reverseOrder());
        countdownSecondsList = java.util.Collections.unmodifiableList(cds);

        mailNotifyOnJoin = c.getBoolean("mailbox.notify-on-join", true);
        mailPurgeClaimedDays = Math.max(1, c.getInt("mailbox.purge-claimed-days", 7));
        mailFeeCents = Math.max(0, Math.round(c.getDouble("mailbox.player-fee", 0) * 100.0));

        priceConfirmMultiplier = clampPercent(c.getDouble("market.price-confirm-multiplier", 3.0)) <= 0
                ? 0 : Math.max(1.0, c.getDouble("market.price-confirm-multiplier", 3.0));
        orderExpireDays = clampRange(c.getInt("advanced.order-expire-days", 7), 1, 365);
        orderMaxPerPlayer = Math.max(1, c.getInt("order.max-per-player", 5));
        orderMaxAmount = Math.max(1, c.getInt("order.max-amount", 1000));
        java.util.Map<String, Integer> gol = new java.util.HashMap<>();
        org.bukkit.configuration.ConfigurationSection gsec =
                c.getConfigurationSection("order.group-max-per-player");
        if (gsec != null) {
            for (String k : gsec.getKeys(false)) {
                try {
                    gol.put(k.toLowerCase(java.util.Locale.ROOT), Math.max(1, gsec.getInt(k, 5)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        groupOrderLimit = java.util.Collections.unmodifiableMap(gol);
        orderSaleTaxPercent = clampPercent(c.getDouble("order.sale-tax-percent", 5.0));
        offerTimeoutHours = clampRange(c.getInt("order.offer-timeout-hours", 24), 0, 8760);
        offerCancelFeeCents = Math.max(0, Math.round(c.getDouble("order.offer-cancel-fee", 1000) * 100.0));
        txlogKeepDays = Math.max(1, c.getInt("advanced.txlog-keep-days", 30));
        terminalKeepDays = Math.max(1, c.getInt("advanced.terminal-keep-days", 14));
        salesKeepDays = Math.max(1, c.getInt("advanced.sales-keep-days", 30));
        customItemDetect = c.getBoolean("advanced.custom-item-detect", true);
        java.util.Set<String> dw = new java.util.HashSet<>();
        for (String w : c.getStringList("world-limits.disabled-worlds")) {
            dw.add(w.toLowerCase(java.util.Locale.ROOT));
        }
        // 兼容旧配置键（advanced.disabled-worlds），两者并集生效
        for (String w : c.getStringList("advanced.disabled-worlds")) {
            dw.add(w.toLowerCase(java.util.Locale.ROOT));
        }
        disabledWorlds = java.util.Collections.unmodifiableSet(dw);
        worldOpExempt = c.getBoolean("world-limits.op-exempt", true);
        java.util.Map<String, java.util.Set<String>> wa = new java.util.HashMap<>();
        org.bukkit.configuration.ConfigurationSection wsec =
                c.getConfigurationSection("world-limits.allow");
        if (wsec != null) {
            for (String world : wsec.getKeys(false)) {
                java.util.Set<String> subs = new java.util.HashSet<>();
                for (String sub : wsec.getStringList(world)) {
                    subs.add(sub.toLowerCase(java.util.Locale.ROOT));
                }
                wa.put(world.toLowerCase(java.util.Locale.ROOT),
                        java.util.Collections.unmodifiableSet(subs));
            }
        }
        worldAllow = java.util.Collections.unmodifiableMap(wa);
        oldRemindDays = clampRange(c.getInt("mailbox.old-remind-days", 7), 0, 365);

        String langCode = c.getString("language", "zh_CN").trim().toLowerCase(java.util.Locale.ROOT);
        if (langCode.equals("zh")) langCode = "zh_CN";
        if (langCode.equals("en")) langCode = "en_US";
        language = langCode;
        currencySymbol = c.getString("economy.currency-symbol", "$");
        soundsEnabled = c.getBoolean("economy.sounds-enabled", true);
    }

    private static double clampPercent(double v) {
        if (Double.isNaN(v) || v < 0) return 0;
        return Math.min(v, 100);
    }

    private static int clampRange(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Set<Material> parseBlacklist(java.util.List<String> names) {
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        for (String n : names) {
            Material m = Material.matchMaterial(n.trim().toUpperCase(Locale.ROOT));
            if (m != null) set.add(m);
        }
        return set;
    }

    /** yml 里价格用"元"书写，这里统一转分。 */
    static final class MoneyCompat {
        static long cents(FileConfiguration c, String path, double def) {
            double v = c.getDouble(path, def);
            if (Double.isNaN(v) || v < 0) v = def;
            return Math.min(Math.round(v * 100.0), Money_MAX);
        }

        private static final long Money_MAX = 9_999_999_999_99L;
    }
}

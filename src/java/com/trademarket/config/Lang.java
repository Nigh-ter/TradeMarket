package com.trademarket.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 语言文件管理。核心约束：代码里用到的每个 key 必须同时登记在 {@link #REQUIRED_KEYS}，
 * 启动时逐个校验并在控制台报告缺失项 —— 从机制上杜绝旧版"界面全是 <key> 占位符"的问题。
 */
public final class Lang {

    private final JavaPlugin plugin;
    private YamlConfiguration yaml;
    private final List<String> warnedMissing = new ArrayList<>();
    /**
     * 系统模板键（带占位符的 lore/info 模板）：启动时强制以 jar 内最新默认值刷新。
     * 这类键由系统渲染占位符，服主无需修改 —— 保证占位符模板永远与代码匹配，
     * 从机制上杜绝"旧模板 + 新代码"出现的原始占位符残留。
     */
    private static final java.util.Set<String> SYSTEM_TEMPLATE_KEYS = java.util.Set.of(
            "common.world-disabled", "gui.mailbox.offer-lore", "gui.mailbox.note-label", "gui.mailbox.note-continue",
            "gui.send.info", "gui.orderfill.info",            "gui.mailconfirm.info", "gui.confirm.info", "gui.confirm-list.info",
            "gui.ordercreate.add-item-lore", "gui.listing.lore", "gui.orders.lore",
            "gui.myoffers.lore", "gui.myoffers.confirm-info", "gui.reconcile.lore",
            "gui.history.lore", "gui.mailbox.mail-lore", "gui.mailbox.mail-lore-item",
            "gui.mailbox.mail-lore-money", "gui.mailbox.mail-lore-command",
            "gui.listing.lore-avg", "gui.listing.lore-preview"
    );
    /** 自动注入的默认占位符（如 {symbol} = 货币符号），调用点无需再传。 */
    private volatile java.util.Map<String, Object> defaults = java.util.Map.of();

    /** 代码引用的全部语言键（与 lang/zh_CN.yml 一一对应，新增键时两处同步）。 */
    private static final String[] REQUIRED_KEYS = {
            "prefix",
            "common.no-permission", "common.player-only", "common.economy-unavailable",
            "common.invalid-number", "common.hand-empty", "common.inventory-full",
            "common.not-enough-money", "common.internal-error", "common.input-cancelled",
            "common.input-timeout", "common.page", "common.reloaded", "common.invalid-amount",
            "market.listed", "market.list-fee-charged", "market.list-limit", "market.list-gone",
            "market.price-too-low", "market.price-too-high", "market.blacklisted", "market.price-confirm",
            "market.cant-afford-fee", "market.buy-success", "market.buy-own",
            "market.cancel-success", "market.cancel-not-owner", "market.sold-notify-seller",
            "market.expired-returned-subject", "market.buy.subject", "market.buy.body",
            "market.offline-sold-notice-subject", "market.offline-sold-notice-body",
            "market.search-prompt", "market.price-prompt",
            "auction.created", "auction.created-broadcast", "auction.list-limit",
            "auction.cant-afford-fee", "auction.start-price-too-low", "auction.price-too-high",
            "auction.duration-limit", "auction.blacklisted", "auction.gone", "auction.bid-own",
            "auction.bid-too-low", "auction.bid-success", "auction.bid-cant-afford",
"auction.buyout-success", "auction.outbid-notify",
            "auction.expired-notify", "auction.cancel-success", "auction.cancel-not-owner",
            "auction.cancel-has-bids", "auction.return-subject", "auction.won-subject",
            "auction.custom-bid", "auction.won-broadcast", "auction.snipe-extended", "auction.countdown-broadcast",
            "mailbox.notify-join", "mailbox.gone", "mailbox.claimed-item", "mailbox.claimed-notice",
            "mailbox.claimed-money", "mailbox.claimed-both", "mailbox.claim-all-done",
            "mailbox.claim-none", "mailbox.item-subject", "mailbox.money-subject",
            "mailbox.command-subject", "mailbox.offer-subject",
            "mailbox.offer-notify", "mailbox.notify-old",
            "gui.common.back", "gui.preview.title", "gui.preview.back",
            "gui.history.title", "gui.history.empty", "gui.history.back", "gui.history.lore",
            "gui.reconcile.title", "gui.reconcile.empty", "gui.reconcile.count", "gui.reconcile.all-btn",
            "gui.reconcile.lore", "gui.reconcile.need-item", "gui.reconcile.need-pay", "gui.reconcile.need-both", "gui.reconcile.action-hint", "gui.mailbox.mail-lore-command", "gui.mailbox.note-label", "gui.mailbox.note-continue",
            "gui.click.open-auction", "gui.click.open-orders", "gui.click.open-mailbox", "gui.click.hover",
            "gui.reconcile.title", "gui.reconcile.empty", "gui.reconcile.count", "gui.reconcile.all-btn",
            "gui.reconcile.lore", "gui.reconcile.need-item", "gui.reconcile.need-pay",
            "gui.reconcile.need-both", "gui.reconcile.action-hint",
            "order.created", "order.created-broadcast", "order.limit", "order.cancel-success", "order.cancel-not-owner", "order.cancel-in-myorders",
            "order.gone", "order.expired-notify", "order.fill-subject", "order.fill-success",
            "order.filled-notify-buyer", "order.not-holding", "order.sell-own",
            "gui.market.title", "gui.market.prev", "gui.market.next", "gui.market.page-info",
            "gui.market.search", "gui.market.searching", "gui.market.sort",
            "gui.market.sort-NEWEST", "gui.market.sort-PRICE_ASC", "gui.market.sort-PRICE_DESC",
            "gui.market.my-listings", "gui.market.empty", "gui.market.orders", "gui.market.orders-hint",
            "gui.market.category", "gui.market.category-ALL", "gui.market.category-WEAPONS",
            "gui.market.category-TOOLS", "gui.market.category-ARMOR", "gui.market.category-BLOCKS",
            "gui.market.category-FOOD", "gui.market.category-MISC", "gui.market.price-filter",
            "gui.market.price-off", "gui.market.price-filtering", "gui.listing.lore-preview",
            "gui.confirm.title", "gui.confirm.buy", "gui.confirm.cancel", "gui.confirm.info",
            "gui.confirm-list.title", "gui.confirm-list.confirm", "gui.confirm-list.cancel",
            "gui.confirm-list.info", "gui.confirm-list.no-history",
            "gui.mylistings.title", "gui.mylistings.back", "gui.mylistings.cancel-hint",
            "gui.mylistings.empty",
            "gui.orders.title", "gui.orders.empty", "gui.orders.lore", "gui.orders.sell-hint", "gui.orders.own-hint", "gui.orders.offer-hint",
            "gui.orders.item-name", "gui.orders.back-market",
            "gui.auction.title", "gui.auction.empty", "gui.auction.my-hint",
            "gui.myauctions.title", "gui.myauctions.back", "gui.myauctions.cancel-hint",
            "gui.myauctions.cancel-has-bids", "gui.myauctions.empty",
            "gui.bid.title", "gui.bid.info", "gui.bid.min-increment", "gui.bid.min-increment-lore",
            "gui.bid.plus-ten", "gui.bid.plus-ten-lore", "gui.bid.custom",
            "gui.bid.buyout", "gui.bid.buyout-lore", "gui.bid.back", "gui.bid.history",
            "gui.mailbox.title", "gui.mailbox.claim-all", "gui.mailbox.empty",
            "gui.mailbox.mail-lore", "gui.mailbox.mail-lore-item", "gui.mailbox.mail-lore-money",
            "gui.mailbox.mail-lore-claimed", "gui.mailbox.mail-lore-claim-hint",
            "gui.listing.lore", "gui.listing.lore-avg",
            "gui.auctionitem.lore-current", "gui.auctionitem.lore-no-bid",
            "gui.auctionitem.lore-buyout", "gui.auctionitem.lore-no-buyout",
            "gui.auctionitem.lore-timeleft", "gui.auctionitem.lore-seller",
            "gui.auctionitem.lore-bid-hint",
            "help.header", "help.line-market", "help.line-sell", "help.line-order",
            "help.line-orders", "help.line-auction",
            "help.line-auction-sell", "help.line-mailbox", "help.line-my", "help.line-send", "help.line-offers", "help.line-myorders",
            "help.admin-header", "help.admin-line-mail", "help.admin-line-mailall",
            "help.admin-line-mailonline", "help.admin-line-refund", "help.admin-line-redeliver", "help.admin-line-stats",
            "help.admin-line-reload",
            "admin.mail-sent", "admin.mailall-sent", "admin.player-not-found",
            "admin.refund-done", "admin.stats",
            "admin.stats-listings", "admin.stats-auctions", "admin.stats-mails",
            "admin.stats-orders",
            "admin.usage-mail", "admin.usage-mailonline", "admin.usage-mailall", "admin.usage-refund",
            "admin.no-targets", "admin.reconcile-done",
            "gui.mailconfirm.title", "gui.mailconfirm.confirm", "gui.mailconfirm.cancel",
            "gui.mailconfirm.note-none", "gui.mailconfirm.info",
            "gui.ordercreate.title", "gui.ordercreate.no-sample", "gui.ordercreate.no-sample-lore",
            "gui.ordercreate.sample-lore", "gui.ordercreate.provider-vanilla", "gui.ordercreate.strict-on",
            "gui.ordercreate.strict-off", "gui.ordercreate.add-item", "gui.ordercreate.add-item-lore",
            "gui.ordercreate.clear", "gui.ordercreate.mode-unit", "gui.ordercreate.mode-total",
            "gui.ordercreate.mode-active", "gui.ordercreate.mode-idle", "gui.ordercreate.set-price",
            "gui.ordercreate.set-price-lore", "gui.ordercreate.set-amount", "gui.ordercreate.set-amount-lore",
            "gui.ordercreate.unset", "gui.ordercreate.summary", "gui.ordercreate.summary-incomplete",
            "gui.ordercreate.summary-lore", "gui.ordercreate.confirm",
            "gui.send.title", "gui.send.type-item", "gui.send.type-message", "gui.send.info",
            "gui.orderfill.title", "gui.orderfill.confirm", "gui.orderfill.cancel", "gui.orderfill.info",
            "gui.mailconfirm.type-money", "gui.mailconfirm.type-command", "gui.mailconfirm.type-item",
            "gui.mailconfirm.detail-item", "gui.mailconfirm.scope-online", "gui.mailconfirm.scope-all",
            "admin.mailconfirm-none", "admin.mailconfirm-expired",
            "admin.mailundo-none", "admin.mailundo-done", "admin.stats-trade-today",
            "admin.stats-trade-total", "admin.stats-escrow-auction", "admin.stats-escrow-order",
            "auction.snipe-extended", "auction.sold-notify-seller",
            "help.admin-line-redeliver-id", "help.admin-line-mailundo",
            "admin.usage-redeliver", "admin.no-entry"
    };

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        load("zh_CN");
    }

    /** 启动/重载时调用：确保语言文件在磁盘上（缺则提取），再按配置加载。 */
  private void ensureExtracted(String code) {
        String resource = "lang/" + code + ".yml";
        if (plugin.getResource(resource) == null) return;
        File file = new File(plugin.getDataFolder(), resource.replace('/', File.separatorChar));
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
    }

    /** 按语言代码加载 lang/<code>.yml；缺失键自动从 jar 默认值补齐并写回磁盘（不动用户已改的值）。 */
    public void load(String code) {
        warnedMissing.clear();
        String code2 = (code == null || code.isBlank()) ? "zh_CN" : code.trim();
        String resource = "lang/" + code2 + ".yml";
        if (plugin.getResource(resource) == null) {
            plugin.getLogger().warning("语言文件 lang/" + code2 + ".yml 不存在，回退到 zh_CN");
            code2 = "zh_CN";
            resource = "lang/zh_CN.yml";
        }
        ensureExtracted(code2);
        ensureExtracted("en_US");

        File file = new File(plugin.getDataFolder(), resource.replace('/', File.separatorChar));
        yaml = YamlConfiguration.loadConfiguration(file);

        // 以 jar 内同名文件为默认值，补齐缺失键后写回磁盘（用户改过的值保持不变）
        YamlConfiguration jarDefaults = null;
        try (var in = plugin.getResource(resource)) {
            if (in != null) {
                jarDefaults = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                yaml.setDefaults(jarDefaults);
            }
        } catch (java.io.IOException ignored) {
        }
        if (jarDefaults != null) {
            int healed = 0;
            for (String key : REQUIRED_KEYS) {
                if (!yaml.isSet(key) && jarDefaults.isSet(key)) {
                    yaml.set(key, jarDefaults.get(key));
                    healed++;
                }
            }
            for (String tk : SYSTEM_TEMPLATE_KEYS) {
                if (jarDefaults.isSet(tk) && !yaml.get(tk, "").equals(jarDefaults.get(tk))) {
                    yaml.set(tk, jarDefaults.get(tk));
                    healed++;
                }
            }
            if (healed > 0) {
                try {
                    yaml.save(file);
                    plugin.getLogger().info("[Lang] 已自动补齐 " + healed + " 个缺失键并写回 " + resource);
                } catch (Exception e) {
                    plugin.getLogger().warning("[Lang] 缺失键写回失败（不影响运行，内存中已补齐）: " + e.getMessage());
                }
            }
        }
        for (String key : REQUIRED_KEYS) {
            if (!yaml.isSet(key)) {
                plugin.getLogger().warning("[Lang] 缺失语言键: " + key);
            }
        }
    }

    /** 原始字符串（& 色码未解析），缺键时返回 key 本身并告警一次。 */
    public String raw(String key) {
        String s = yaml.getString(key);
        if (s == null) {
            if (!warnedMissing.contains(key)) {
                warnedMissing.add(key);
                plugin.getLogger().warning("[Lang] 使用了缺失的语言键: " + key);
            }
            return key;
        }
        return s;
    }

    /** 解析 & 色码后的单行组件。 */
    public Component line(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw(key));
    }

    /** 带前缀的聊天消息。 */
    public Component msg(String key) {
        return compose(raw("prefix") + raw(key), null);
    }

    /**
     * 带前缀 + 占位符替换（成对传入 {"{price}", "12.00"}）。
     * 值可以是 String，也可以是 Component —— 物品名必须传
     * {@link com.trademarket.util.Items#displayName} 的组件（原版物品为可翻译组件，
     * 客户端按语言显示"橡木原木/下界合金剑"，改过名的显示自定义名）。
     */
    public Component msg(String key, Object... kv) {
        return compose(raw("prefix") + raw(key), kv);
    }

    /** 无前缀聊天消息（参数规则同 {@link #msg(String, Object...)}）。 */
    public Component plain(String key, Object... kv) {
        return compose(raw(key), kv);
    }

    /** 设置自动占位符（每次配置加载后调用一次）。 */
    public void setDefaultPlaceholders(java.util.Map<String, Object> d) {
        this.defaults = java.util.Map.copyOf(d);
    }

    /** 模板拼接：把 {token} 替换为字符串或内嵌组件。默认占位符自动可用，调用点传入的优先。 */
    private Component compose(String template, Object[] kv) {
        java.util.Map<String, Object> map;
        if (kv == null || kv.length == 0) {
            map = defaults.isEmpty() ? java.util.Map.of() : new java.util.HashMap<>(defaults);
        } else {
            map = new java.util.HashMap<>(defaults);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                map.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
        int i = 0;
        int n = template.length();
        while (i < n) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                builder.append(legacy(template.substring(i)));
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                builder.append(legacy(template.substring(i)));
                break;
            }
            Object val = map.get(template.substring(open, close + 1));
            if (val == null) {
                builder.append(legacy(template.substring(i, close + 1)));
            } else {
                if (open > i) builder.append(legacy(template.substring(i, open)));
                if (val instanceof Component c) {
                    builder.append(c);
                } else {
                    builder.append(legacy(String.valueOf(val)));
                }
            }
            i = close + 1;
        }
        return builder.build();
    }

    private Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    /**
     * 可点击动作按钮：聊天里点击直接执行命令（如 [打开拍卖] → /tk auction），
     * 悬停显示 gui.click.hover 提示。用于拼接到消息末尾。
     */
    public net.kyori.adventure.text.TextComponent action(String labelKey, String command) {
        return net.kyori.adventure.text.Component.text()
                .append(net.kyori.adventure.text.Component.text(" "))
                .append(line(labelKey))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(line("gui.click.hover")))
                .build();
    }

    /** GUI 按钮多行 lore（& 色码 + 占位符）。 */
    public List<Component> lore(String key, Object... kv) {
        List<Component> out = new ArrayList<>();
        for (String line : yaml.getStringList(key)) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                line = line.replace(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
            }
            out.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
        return out;
    }

    /** 多行原始字符串（未解析色码、未替换占位符），供需要二次处理的调用方使用。 */
    public List<String> yamlList(String key) {
        return yaml.getStringList(key);
    }

    public Component title(String key) {
        return line(key);
    }
}

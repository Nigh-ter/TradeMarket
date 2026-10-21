package com.trademarket;

import com.trademarket.auction.AuctionManager;
import com.trademarket.command.ChatInputService;
import com.trademarket.command.MarketCommand;
import com.trademarket.config.Lang;
import com.trademarket.config.Settings;
import com.trademarket.data.Database;
import com.trademarket.economy.EconomyHook;
import com.trademarket.gui.GuiListener;
import com.trademarket.listener.JoinListener;
import com.trademarket.mail.MailManager;
import com.trademarket.model.Listing;
import com.trademarket.model.Auction;
import com.trademarket.market.MarketManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * TradeMarket 2.0 —— 全球市场 / 竞价拍卖 / 智能邮箱。
 *
 * <p>线程模型（与 1.x 的根本区别）：所有业务状态变更都在主线程串行执行，
 * SQLite（WAL）同步直写 —— 本地写入亚毫秒级，用"顺序一致"换掉全部锁与回调，
 * 从结构上消灭 1.x 的跨线程锁释放、事务内转账、异步发货等一整类缺陷。</p>
 */
public final class TradeMarketPlugin extends JavaPlugin {

    public static final int CONFIG_VERSION = 2;

    private Settings settings;
    private Lang lang;
    private final EconomyHook economy = new EconomyHook();
    private Database database;
    private MarketManager market;
    private AuctionManager auction;
    private MailManager mail;
    private com.trademarket.market.OrderManager order;
    private com.trademarket.market.SaleHistory history;
    private ChatInputService chatInput;

    @Override
    public void onEnable() {
        migrateLegacyConfig();
        saveDefaultConfig();

        // 玩家改过的配置值不覆盖；新版本新增的配置键自动补写进文件（copyDefaults）
        getConfig().options().copyDefaults(true);
        saveConfig();

        this.lang = new Lang(this);
        this.settings = new Settings(getConfig());
        // {symbol} 全局自动占位符：任何消息无需传货币符号
        this.lang.setDefaultPlaceholders(java.util.Map.of("{symbol}", settings.currencySymbol));
        this.lang.load(settings.language);

        this.economy.init();
        if (!economy.available()) {
            getLogger().warning("未检测到 Vault 经济插件！金币相关功能将不可用（市场/拍卖无法交易）");
        } else {
            getLogger().info("经济后端: " + economy.backendName());
        }

        try {
            this.database = new Database(this);
            this.database.open();
        } catch (Exception e) {
            getLogger().severe("数据库初始化失败，插件禁用: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 读取存量数据（主线程同步，量级为千行以内，毫秒级）
        this.history = new com.trademarket.market.SaleHistory();
        this.history.load(database, 5000);
        this.market = new MarketManager(this);
        this.auction = new AuctionManager(this);
        this.mail = new MailManager(this);
        this.order = new com.trademarket.market.OrderManager(this);
        reconcilePending();

        this.chatInput = new ChatInputService(this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
        Bukkit.getPluginManager().registerEvents(this.chatInput, this);
        Bukkit.getPluginManager().registerEvents(new JoinListener(this), this);

        PluginCommand cmd = getCommand("market");
        if (cmd != null) {
            MarketCommand executor = new MarketCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // 主循环：拍卖结算 / 市场过期（每秒）；邮件清理（每 5 分钟）
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                market.tick();
                auction.tick();
                order.tickOffers();
            } catch (Throwable t) {
                getLogger().severe("周期任务异常: " + t);
            }
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                mail.purge();
                order.purge();
                long now = System.currentTimeMillis();
                database.pruneTxLog(now - settings.txlogKeepDays * 86_400_000L);
                database.pruneSales(now - settings.salesKeepDays * 86_400_000L);
                database.pruneTerminalListings(now - settings.terminalKeepDays * 86_400_000L);
                database.pruneTerminalAuctions(now - settings.terminalKeepDays * 86_400_000L);
                database.pruneOrders(now - settings.terminalKeepDays * 86_400_000L);
            } catch (Throwable t) {
                getLogger().severe("周期清理异常: " + t);
            }
        }, 20L * 60, 20L * 300);

        getLogger().info(() -> "TradeMarket 已启用: 在售 " + market.count()
                + " | 拍卖 " + auction.count() + " | 未领邮件 " + mail.unclaimedCount());
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("TradeMarket 已安全关闭。");
    }

    /** 检测 1.x 旧配置（无 config-version 标记）→ 整体备份后重新生成，避免新旧键混杂。 */
    private void migrateLegacyConfig() {
        File marker = new File(getDataFolder(), "config.yml");
        if (!marker.exists()) return;
        var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(marker);
        if (cfg.getInt("config-version", 1) >= CONFIG_VERSION) return;

        File backup = new File(getDataFolder(), "backup-v1-" + System.currentTimeMillis());
        try {
            Files.createDirectories(backup.toPath());
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(getDataFolder().toPath())) {
                paths = walk.filter(p -> !p.startsWith(backup.toPath()))
                        .sorted(Comparator.reverseOrder())
                        .toList();
            }
            for (Path p : paths) {
                try {
                    Files.move(p, backup.toPath().resolve(getDataFolder().toPath().relativize(p)));
                } catch (IOException ignored) {
                }
            }
            getLogger().warning("检测到 1.x 旧版配置，已整体备份到 " + backup.getName()
                    + " 并重新生成 2.0 配置。旧版数据不迁移（1.x 功能异常期间产生的测试数据）。");
        } catch (IOException e) {
            getLogger().severe("旧配置备份失败: " + e.getMessage());
        }
    }

    /**
     * 启动对账：补齐"状态已翻转但交割未完成"的残留记录。
     * 覆盖场景：结算中途崩溃、发货邮件中断、下架/过期退回中断。
     * 每一步完成后立即落标记，重启后只补做缺标记的步骤。
     */
    /** 启动对账：补齐"状态已翻转但交割未完成"的残留记录（/tk admin redeliver 一键补发复用）。 */
    public void reconcilePending() {
        int fixed = 0;
        for (Listing l : database.loadPendingListings()) {
            boolean sold = "SOLD".equals(l.status);
            if (sold && !l.paid) {
                long tax = Math.round(l.totalCents() * settings.marketSaleTaxPercent / 100.0);
                var seller = Bukkit.getOfflinePlayer(l.sellerId);
                if (economy.deposit(seller, l.totalCents() - tax)) {
                    database.markListingPaid(l.id);
                    getLogger().info("[对账] 商品 #" + l.id + " 卖家款项已补发: "
                            + com.trademarket.util.Money.format(l.totalCents() - tax));
                    fixed++;
                } else {
                    getLogger().severe("[对账] 商品 #" + l.id + " 卖家补款失败，请人工处理");
                    database.tx(l.sellerId, "RECONCILE_FAILED", "listing=" + l.id);
                }
            }
            if (!l.delivered) {
                java.util.UUID to = sold && l.buyerId != null ? l.buyerId : l.sellerId;
                String subject = sold ? lang.raw("market.buy.subject")
                        : lang.raw("market.expired-returned-subject");
                try {
                    mail.sendItem(to, nameOf(to), "系统", subject, "", l.item);
                    database.markListingDelivered(l.id);
                    getLogger().info("[对账] 商品 #" + l.id + " 物品已补发");
                    fixed++;
                } catch (Exception e) {
                    getLogger().severe("[对账] 商品 #" + l.id + " 物品补发失败: " + e.getMessage());
                }
            }
        }
        for (Auction a : database.loadPendingAuctions()) {
            boolean sold = "SOLD".equals(a.status);
            if (sold && !a.paid) {
                long price = a.currentBidCents > 0 ? a.currentBidCents : a.buyoutCents;
                long tax = Math.round(price * settings.auctionSaleTaxPercent / 100.0);
                var seller = Bukkit.getOfflinePlayer(a.sellerId);
                if (economy.deposit(seller, price - tax)) {
                    database.markAuctionPaid(a.id);
                    getLogger().info("[对账] 拍卖 #" + a.id + " 卖家款项已补发: "
                            + com.trademarket.util.Money.format(price - tax));
                    fixed++;
                } else {
                    getLogger().severe("[对账] 拍卖 #" + a.id + " 卖家补款失败，请人工处理");
                    database.tx(a.sellerId, "RECONCILE_FAILED", "auction=" + a.id);
                }
            }
            if (!a.delivered) {
                java.util.UUID to = sold && a.topBidderId != null ? a.topBidderId : a.sellerId;
                String subject = sold ? lang.raw("auction.won-subject")
                        : lang.raw("auction.return-subject");
                try {
                    mail.sendItem(to, nameOf(to), "系统", subject, "", a.item);
                    database.markAuctionDelivered(a.id);
                    getLogger().info("[对账] 拍卖 #" + a.id + " 物品已补发");
                    fixed++;
                } catch (Exception e) {
                    getLogger().severe("[对账] 拍卖 #" + a.id + " 物品补发失败: " + e.getMessage());
                }
            }
        }
        if (fixed > 0) {
            getLogger().info("[对账] 共自动修复 " + fixed + " 条中断的交割记录");
        }
    }

    /** 该玩家所在世界是否禁用交易（全禁世界，无白名单时）。 */
    public boolean isTradeBlocked(Player player) {
        return settings.disabledWorlds.contains(
                player.getWorld().getName().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 世界限制中央判定：全禁世界 / OP 豁免 / 分世界白名单。
     * sub 为 /tk 的子命令名（market/sell/my/order/orders/offers/auction/mailbox/send）。
     */
    public boolean isTradeCommandAllowed(Player player, String sub) {
        String world = player.getWorld().getName().toLowerCase(java.util.Locale.ROOT);
        if (!settings.disabledWorlds.contains(world)) return true;
        if (settings.worldOpExempt
                && (player.isOp() || player.hasPermission("trademarket.admin"))) {
            return true;
        }
        java.util.Set<String> allow = settings.worldAllow.get(world);
        return allow != null && allow.contains(sub.toLowerCase(java.util.Locale.ROOT));
    }

    public String nameOf(java.util.UUID uuid) {
        org.bukkit.OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        return p.getName() == null ? "玩家" : p.getName();
    }

    public void reloadAll() {
        reloadConfig();
        this.settings = new Settings(getConfig());
        this.lang.setDefaultPlaceholders(java.util.Map.of("{symbol}", settings.currencySymbol));
        this.lang.load(settings.language);
    }

    // ===== 访问器 =====

    public Settings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    public EconomyHook economy() {
        return economy;
    }

    public Database database() {
        return database;
    }

    public MarketManager market() {
        return market;
    }

    public AuctionManager auction() {
        return auction;
    }

    public MailManager mail() {
        return mail;
    }

    public com.trademarket.market.OrderManager order() {
        return order;
    }

    public com.trademarket.market.SaleHistory history() {
        return history;
    }

    public ChatInputService chatInput() {
        return chatInput;
    }
}

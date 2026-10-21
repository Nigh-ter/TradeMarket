package com.trademarket.gui;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.model.Auction;
import com.trademarket.model.Listing;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 卡住交易管理界面（管理员）：列出交割未完成的商品/拍卖，
 * 显示编号与缺什么；左键补发物品、右键补付款项，一键全部补发在左下角。
 * 打开方式：/tk admin redeliver（不带参数）。
 */
public final class ReconcileGui extends GuiBase {

    private final List<Object> entries = new ArrayList<>(); // Listing 或 Auction

    private TradeMarketPlugin tm() {
        return (TradeMarketPlugin) plugin;
    }

    public ReconcileGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.reconcile.title"));
    }

    @Override
    protected void build() {
        entries.clear();
        entries.addAll(tm().database().loadPendingListings());
        entries.addAll(tm().database().loadPendingAuctions());
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length && i < entries.size(); i++) {
            place(CONTENT_SLOTS[i], entryIcon(entries.get(i)));
        }
        place(46, Items.guiIcon(Material.HOPPER, lang.line("gui.reconcile.all-btn"), List.of()));
        place(49, Items.guiIcon(Material.PAPER, lang.line("gui.reconcile.count"),
                List.of(lang.plain("common.page", "{page}", String.valueOf(entries.size()),
                        "{max}", String.valueOf(entries.size())))));
        if (entries.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.reconcile.empty"), List.of()));
        }
    }

    private ItemStack entryIcon(Object entry) {
        List<String> rawLore = new ArrayList<>(lang.yamlList("gui.reconcile.lore"));
        boolean sold;
        boolean paid;
        boolean delivered;
        UUID receiver;
        String sellerName;
        long id;
        ItemStack item;
        if (entry instanceof Listing l) {
            sold = "SOLD".equals(l.status);
            paid = l.paid;
            delivered = l.delivered;
            id = l.id;
            sellerName = l.sellerName;
            item = l.item;
            receiver = sold && l.buyerId != null ? l.buyerId : l.sellerId;
        } else if (entry instanceof Auction a) {
            sold = "SOLD".equals(a.status);
            paid = a.paid;
            delivered = a.delivered;
            id = a.id;
            sellerName = a.sellerName;
            item = a.item;
            receiver = sold && a.topBidderId != null ? a.topBidderId : a.sellerId;
        } else {
            return Items.guiIcon(Material.BARRIER, Items.loreLine("?"), List.of());
        }
        String need = !paid && !delivered ? lang.raw("gui.reconcile.need-both")
                : !paid ? lang.raw("gui.reconcile.need-pay")
                : lang.raw("gui.reconcile.need-item");
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : rawLore) {
            lore.add(Items.loreLine(line
                    .replace("{id}", String.valueOf(id))
                    .replace("{status}", sold ? "已售出" : "已退回")
                    .replace("{seller}", sellerName)
                    .replace("{receiver}", tm().nameOf(receiver))
                    .replace("{need}", need)
                    .replace("{symbol}", settings.currencySymbol)));
        }
        lore.addAll(lang.lore("gui.reconcile.action-hint"));
        return Items.icon(item, lore);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        if (event.getRawSlot() == 46) {
            tm.reconcilePending();
            sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
            open();
            return;
        }
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == event.getRawSlot()) { contentIndex = i; break; }
        }
        if (contentIndex < 0 || contentIndex >= entries.size()) return;
        Object entry = entries.get(contentIndex);
        boolean pay = event.isRightClick();
        boolean ok;
        if (entry instanceof Listing l) {
            ok = pay ? payListing(tm, l) : deliverListing(tm, l);
        } else if (entry instanceof Auction a) {
            ok = pay ? payAuction(tm, a) : deliverAuction(tm, a);
        } else {
            ok = false;
        }
        if (ok) {
            player.sendMessage(tm.lang().msg("admin.reconcile-done"));
            sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        } else {
            player.sendMessage(tm.lang().msg("common.internal-error"));
            sound(Sound.ENTITY_VILLAGER_NO);
        }
        open();
    }

    // ===== 单条补发动作 =====

    private boolean deliverListing(TradeMarketPlugin tm, Listing l) {
        boolean sold = "SOLD".equals(l.status);
        UUID to = sold && l.buyerId != null ? l.buyerId : l.sellerId;
        String subject = tm.lang().raw(sold ? "market.buy.subject" : "market.expired-returned-subject");
        try {
            tm.mail().sendItem(to, tm.nameOf(to), "系统", subject, "", l.item);
        } catch (Exception e) {
            tm.getLogger().severe("[手动补发] 商品 #" + l.id + " 失败: " + e.getMessage());
            return false;
        }
        tm.database().markListingDelivered(l.id);
        tm.database().tx(to, "ADMIN_REDELIVER", "listing=" + l.id);
        return true;
    }

    private boolean payListing(TradeMarketPlugin tm, Listing l) {
        long tax = Math.round(l.totalCents() * tm.settings().marketSaleTaxPercent / 100.0);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.sellerId);
        if (!tm.economy().deposit(seller, l.totalCents() - tax)) return false;
        tm.database().markListingPaid(l.id);
        tm.database().tx(l.sellerId, "ADMIN_PAY", "listing=" + l.id
                + " amount=" + (l.totalCents() - tax));
        return true;
    }

    private boolean deliverAuction(TradeMarketPlugin tm, Auction a) {
        boolean sold = "SOLD".equals(a.status);
        UUID to = sold && a.topBidderId != null ? a.topBidderId : a.sellerId;
        String subject = tm.lang().raw(sold ? "auction.won-subject" : "auction.return-subject");
        try {
            tm.mail().sendItem(to, tm.nameOf(to), "系统", subject, "", a.item);
        } catch (Exception e) {
            tm.getLogger().severe("[手动补发] 拍卖 #" + a.id + " 失败: " + e.getMessage());
            return false;
        }
        tm.database().markAuctionDelivered(a.id);
        tm.database().tx(to, "ADMIN_REDELIVER", "auction=" + a.id);
        return true;
    }

    private boolean payAuction(TradeMarketPlugin tm, Auction a) {
        long price = a.currentBidCents > 0 ? a.currentBidCents : a.buyoutCents;
        long tax = Math.round(price * tm.settings().auctionSaleTaxPercent / 100.0);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(a.sellerId);
        if (!tm.economy().deposit(seller, price - tax)) return false;
        tm.database().markAuctionPaid(a.id);
        tm.database().tx(a.sellerId, "ADMIN_PAY", "auction=" + a.id
                + " amount=" + (price - tax));
        return true;
    }
}

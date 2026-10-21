package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.model.BuyOrder;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 求购大厅：展示全部进行中的求购单。
 * 点击自己的求购单 = 取消并退回托管金；
 * 点击别人的求购单 = 手持对应物品时整批/部分出售，物品经邮箱发给买家。
 */
public final class OrdersGui extends GuiBase {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private int page;

    public OrdersGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.orders.title"));
    }

    @Override
    protected void build() {
        List<BuyOrder> list = ((TradeMarketPlugin) plugin).order().all();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (page >= maxPage) page = maxPage - 1;
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= list.size()) break;
            place(CONTENT_SLOTS[i], orderIcon(list.get(index)));
        }
        placePageNav(page, maxPage);
        place(46, Items.guiIcon(Material.BARRIER, lang.line("gui.myoffers.title"),
                lang.lore("gui.myoffers.empty")));
        place(47, Items.guiIcon(Material.BOOK, lang.line("gui.myorders.title"),
                lang.lore("gui.myorders.entry-hint")));
        place(48, Items.guiIcon(Material.ARROW, lang.line("gui.orders.back-market"), List.of()));
        place(49, Items.guiIcon(Material.PAPER, lang.line("gui.market.page-info"),
                List.of(lang.plain("common.page", "{page}", String.valueOf(page + 1),
                        "{max}", String.valueOf(maxPage)))));
        if (list.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.orders.empty"), List.of()));
        }
    }

    private org.bukkit.inventory.ItemStack orderIcon(BuyOrder o) {
        boolean own = o.buyerId.equals(player.getUniqueId());
        String hintKey = own ? "gui.orders.own-hint" : "gui.orders.sell-hint";
        var lore = lang.lore("gui.orders.lore",
                "{symbol}", settings.currencySymbol,
                "{unit}", Money.format(o.unitPriceCents),
                "{remaining}", String.valueOf(o.remaining),
                "{total}", String.valueOf(o.total),
                "{buyer}", o.buyerName,
                "{time}", FMT.format(new Date(o.createdAt)));
        lore.add(lang.line(o.material == org.bukkit.Material.AIR
                ? "gui.orders.offer-hint" : hintKey));
        org.bukkit.inventory.ItemStack rep = o.icon();
        String display;
        if (rep != null) {
            display = com.trademarket.util.Items.plainName(rep);
        } else {
            String zh = com.trademarket.util.ItemNamesZh.zh(o.material);
            display = zh == null ? (o.material == null ? "?" : o.material.name().toLowerCase()) : zh;
        }
        net.kyori.adventure.text.Component name = Items.loreLine(
                lang.raw("gui.orders.item-name").replace("{item}", display));
        if (rep != null) {
            return Items.named(rep, name, lore);
        }
        Material iconMaterial = o.material == null ? Material.BARRIER : o.material;
        return Items.guiIcon(iconMaterial, name, lore);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        List<BuyOrder> list = tm.order().all();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (event.getRawSlot() == 45 && page > 0) { page--; open(); return; }
        if (event.getRawSlot() == 53 && page < maxPage - 1) { page++; open(); return; }
        if (event.getRawSlot() == 47) { new MyOrdersGui(tm, player).open(); return; }
        if (event.getRawSlot() == 48) { new MarketGui(tm, player).open(); return; }
        if (event.getRawSlot() == 46) { new MyOffersGui(tm, player).open(); return; }
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == event.getRawSlot()) { contentIndex = i; break; }
        }
        if (contentIndex < 0) return;
        int index = page * CONTENT_SLOTS.length + contentIndex;
        if (index >= list.size()) return;
        BuyOrder o = list.get(index);
        if (o.buyerId.equals(player.getUniqueId())) {
            // 自己的单子只能在我的求购单里取消
            player.sendMessage(tm.lang().msg("order.cancel-in-myorders"));
            return;
        }
        // 手持物品 = 提交托管申请（自定义求购单 / 改名物品的验货通道）
        ItemStack held = player.getInventory().getItemInMainHand();
        boolean customOrder = o.material == org.bukkit.Material.AIR;
        boolean heldIsRenamed = false;
        if (!held.getType().isAir()) {
            var mt = held.getItemMeta();
            heldIsRenamed = mt != null && mt.hasDisplayName();
        }
        if (customOrder || (!held.getType().isAir() && heldIsRenamed)) {
            String error = tm.order().submitOffer(player, o, held);
            if (error == null) sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
            else {
                player.sendMessage(tm.lang().msg(error));
                sound(Sound.ENTITY_VILLAGER_NO);
            }
            open();
            return;
        }
        // 空手 = 扫描背包即售（原版物品通道）
        int sellable = tm.order().countMatchable(player, o);
        if (sellable <= 0) {
            player.sendMessage(tm.lang().msg("order.not-holding"));
            sound(Sound.ENTITY_VILLAGER_NO);
            return;
        }
        long gross = o.unitPriceCents * sellable;
        long tax = Math.round(gross * tm.settings().orderSaleTaxPercent / 100.0);
        var info = tm.lang().lore("gui.orderfill.info",
                "{item}", com.trademarket.util.Items.plainName(o.icon() == null
                        ? new org.bukkit.inventory.ItemStack(o.material) : o.icon()),
                "{unit}", Money.format(o.unitPriceCents),
                "{amount}", String.valueOf(sellable),
                "{income}", Money.format(gross - tax),
                "{tax}", Money.format(tax),
                "{symbol}", settings.currencySymbol);
        org.bukkit.inventory.ItemStack showIcon = o.icon() == null
                ? new org.bukkit.inventory.ItemStack(o.material) : o.icon();
        new com.trademarket.gui.ConfirmActionGui(tm, player,
                tm.lang().title("gui.orderfill.title"), showIcon, info,
                () -> {
                    String error = tm.order().fill(player, o.id);
                    if (error != null) {
                        player.sendMessage(tm.lang().msg(error));
                        sound(Sound.ENTITY_VILLAGER_NO);
                    } else {
                        sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                    }
                },
                () -> { }).open();
    }
}

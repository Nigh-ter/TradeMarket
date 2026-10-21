package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.model.BuyOrder;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 我的求购单：列出自己发布的求购单，点击弹确认取消（退回剩余托管金）。 */
public final class MyOrdersGui extends GuiBase {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private final List<BuyOrder> mine = new ArrayList<>();

    public MyOrdersGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.myorders.title"));
    }

    private TradeMarketPlugin tm() {
        return (TradeMarketPlugin) plugin;
    }

    private void reload() {
        mine.clear();
        for (BuyOrder o : tm().order().all()) {
            if (o.buyerId.equals(player.getUniqueId())) mine.add(o);
        }
    }

    @Override
    protected void build() {
        reload();
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length && i < mine.size(); i++) {
            BuyOrder o = mine.get(i);
            org.bukkit.inventory.ItemStack rep = o.icon();
            String display = rep != null ? Items.plainName(rep)
                    : (o.material == null ? "?" : o.material.name().toLowerCase());
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(
                    lang.lore("gui.myorders.lore",
                            "{unit}", Money.format(o.unitPriceCents),
                            "{remaining}", String.valueOf(o.remaining),
                            "{total}", String.valueOf(o.total),
                            "{escrow}", Money.format(o.escrowCents()),
                            "{symbol}", settings.currencySymbol,
                            "{time}", FMT.format(new Date(o.createdAt))));
            lore.add(lang.line("gui.myorders.cancel-hint"));
            ItemStack icon = rep != null ? Items.named(rep,
                    Items.loreLine(lang.raw("gui.orders.item-name").replace("{item}", display)), lore)
                    : Items.guiIcon(o.material == null ? Material.PAPER : o.material,
                            Items.loreLine(lang.raw("gui.orders.item-name").replace("{item}", display)), lore);
            place(CONTENT_SLOTS[i], icon);
        }
        place(49, Items.guiIcon(Material.ARROW, lang.line("gui.orders.back-market"), List.of()));
        if (mine.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.myorders.empty"), List.of()));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (event.getRawSlot() == 49) {
            new OrdersGui(tm(), player).open();
            return;
        }
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == event.getRawSlot()) { contentIndex = i; break; }
        }
        if (contentIndex < 0 || contentIndex >= mine.size()) return;
        BuyOrder o = mine.get(contentIndex);
        long escrow = o.escrowCents();
        var info = lang.lore("gui.myorders.confirm-info",
                "{item}", o.icon() == null ? "?" : Items.plainName(o.icon()),
                "{unit}", Money.format(o.unitPriceCents),
                "{remaining}", String.valueOf(o.remaining),
                "{escrow}", Money.format(escrow),
                "{symbol}", settings.currencySymbol);
        ItemStack show = o.icon() == null
                ? new ItemStack(o.material == null ? Material.PAPER : o.material) : o.icon();
        new com.trademarket.gui.ConfirmActionGui(tm(), player,
                tm().lang().title("gui.myorders.confirm-title"), show, info,
                "gui.myorders.confirm-btn", "gui.myorders.cancel-btn",
                () -> {
                    String error = tm().order().cancel(player, o.id, false);
                    if (error == null) {
                        sound(Sound.ENTITY_ITEM_PICKUP);
                    } else {
                        player.sendMessage(tm().lang().msg(error));
                        sound(Sound.ENTITY_VILLAGER_NO);
                    }
                    // 成功或失败都回到我的求购单列表（状态已刷新）
                    new MyOrdersGui(tm(), player).open();
                },
                () -> new MyOrdersGui(tm(), player).open()).open();
    }
}

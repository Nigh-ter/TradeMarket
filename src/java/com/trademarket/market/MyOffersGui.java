package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.model.BuyOrder;
import com.trademarket.model.Offer;
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

/** 我的求购申请（托管中）：点击取消并取回物品。 */
public final class MyOffersGui extends GuiBase {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private final List<Offer> offers = new ArrayList<>();

    public MyOffersGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.myoffers.title"));
    }

    private TradeMarketPlugin tm() {
        return (TradeMarketPlugin) plugin;
    }

    @Override
    protected void build() {
        offers.clear();
        offers.addAll(tm().database().loadPendingOffersBySeller(player.getUniqueId()));
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length && i < offers.size(); i++) {
            Offer f = offers.get(i);
            ItemStack item = f.item();
            if (item == null) continue;
            BuyOrder order = tm().order().get(f.orderId);
            String unit = order == null ? "?" : Money.format(order.unitPriceCents);
            var lore = new ArrayList<net.kyori.adventure.text.Component>();
            for (String line : lang.yamlList("gui.myoffers.lore")) {
                lore.add(Items.loreLine(line
                        .replace("{order}", String.valueOf(f.orderId))
                        .replace("{time}", FMT.format(new Date(f.time)))));
            }
            lore.add(Items.loreLine(lang.raw("gui.orders.item-name")
                    .replace("{symbol}", settings.currencySymbol)
                    .replace("{item}", "")));
            lore.set(lore.size() - 1, Items.loreLine("&7单价: &f" + settings.currencySymbol + unit));
            lore.addAll(lang.lore("gui.myoffers.cancel-hint"));
            lore.add(Items.loreLine(lang.raw("gui.myoffers.cancel-fee")
                    .replace("{symbol}", settings.currencySymbol)
                    .replace("{fee}", Money.format(tm().settings().offerCancelFeeCents))));
            lore.add(Items.loreLine(lang.raw("gui.myoffers.no-fee-return")));
            if (tm().settings().offerTimeoutHours > 0) {
                long remainMs = f.time + tm().settings().offerTimeoutHours * 3_600_000L
                        - System.currentTimeMillis();
                lore.add(Items.loreLine(lang.raw("gui.myoffers.remaining")
                        .replace("{time}", remainMs > 0
                                ? com.trademarket.util.Items.formatDuration(remainMs) : "0s")));
            }
            place(CONTENT_SLOTS[i], Items.icon(item, lore));
        }
        place(49, Items.guiIcon(Material.ARROW, lang.line("gui.orders.back-market"), List.of()));
        if (offers.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.myoffers.empty"), List.of()));
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
        if (contentIndex < 0 || contentIndex >= offers.size()) return;
        Offer f = offers.get(contentIndex);
        ItemStack show = f.item() == null ? new ItemStack(Material.BARRIER) : f.item();
        long fee = tm().settings().offerCancelFeeCents;
        var info = lang.lore("gui.myoffers.confirm-info",
                "{order}", String.valueOf(f.orderId),
                "{fee}", fee > 0 ? Money.format(fee) : "0",
                "{symbol}", settings.currencySymbol);
        new com.trademarket.gui.ConfirmActionGui(tm(), player,
                tm().lang().title("gui.myoffers.confirm-title"), show, info,
                () -> {
                    String error = tm().order().cancelOffer(player, f.id);
                    if (error == null) sound(Sound.ENTITY_ITEM_PICKUP);
                    else {
                        player.sendMessage(tm().lang().msg(error));
                        sound(Sound.ENTITY_VILLAGER_NO);
                    }
                },
                () -> new MyOffersGui(tm(), player).open()).open();
    }
}

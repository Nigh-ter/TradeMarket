package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 定价防手滑确认：当上架价与近期成交均价偏离超过阈值时弹出。
 * 确认前物品仍在手上，取消即无事发生。
 */
public final class ConfirmListGui extends GuiBase {

    private final long unitPriceCents;
    private final int amount;
    private final long avgCents;

    public ConfirmListGui(TradeMarketPlugin plugin, Player player,
                          long unitPriceCents, int amount, long avgCents) {
        super(plugin, player, 3, plugin.lang().title("gui.confirm-list.title"));
        this.unitPriceCents = unitPriceCents;
        // 数量缺省（-1）= 手上整组
        ItemStack hand0 = player.getInventory().getItemInMainHand();
        this.amount = amount < 1 ? hand0.getAmount() : Math.min(amount, hand0.getAmount());
        this.avgCents = avgCents;
    }

    @Override
    protected void build() {
        fillBorder();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            place(13, Items.guiIcon(Material.BARRIER, lang.line("common.hand-empty"), List.of()));
            return;
        }
        String avgText = avgCents > 0 ? Money.format(avgCents) : lang.raw("gui.confirm-list.no-history");
        var lore = lang.lore("gui.confirm-list.info",
                "{symbol}", settings.currencySymbol,
                "{price}", Money.format(unitPriceCents),
                "{amount}", String.valueOf(amount),
                "{total}", Money.format(unitPriceCents * amount),
                "{avg}", avgText);
        place(13, Items.icon(hand.asQuantity(this.amount), lore));
        place(11, Items.guiIcon(Material.RED_CONCRETE, lang.line("gui.confirm-list.cancel"), List.of()));
        place(15, Items.guiIcon(Material.LIME_CONCRETE, lang.line("gui.confirm-list.confirm"), List.of()));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        if (event.getRawSlot() == 11) {
            player.closeInventory();
            return;
        }
        if (event.getRawSlot() == 15) {
            String error = tm.market().sell(player, unitPriceCents, amount, true);
            if (error == null) {
                sound(Sound.ENTITY_ITEM_PICKUP);
                player.closeInventory();
            } else {
                player.sendMessage(tm.lang().msg(error));
                sound(Sound.ENTITY_VILLAGER_NO);
                open();
            }
        }
    }
}

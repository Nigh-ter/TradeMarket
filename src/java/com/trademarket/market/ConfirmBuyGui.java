package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.model.Listing;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** 购买确认（3 行小窗）：中间商品、左取消右确认。取消后带原分页/排序/搜索状态返回列表。 */
public final class ConfirmBuyGui extends GuiBase {

    private final MarketGui.Sort returnSort;
    private final String returnSearch;
    private final int returnPage;
    private final long listingId;

    public ConfirmBuyGui(TradeMarketPlugin plugin, Player player, long listingId,
                         int returnPage, MarketGui.Sort returnSort, String returnSearch) {
        super(plugin, player, 3, plugin.lang().title("gui.confirm.title"));
        this.listingId = listingId;
        this.returnPage = returnPage;
        this.returnSort = returnSort;
        this.returnSearch = returnSearch;
    }

    @Override
    protected void build() {
        fillBorder();
        Listing l = ((TradeMarketPlugin) plugin).market().get(listingId);
        if (l == null) {
            place(13, Items.guiIcon(Material.BARRIER, lang.line("market.list-gone"), List.of()));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        var lore = lang.lore("gui.confirm.info",
                "{unit}", Money.format(l.amount == 0 ? l.unitPriceCents : Math.round(l.unitPriceCents * 1.0 / l.amount)),
                "{amount}", String.valueOf(l.amount),
                "{total}", Money.format(l.totalCents()),
                "{symbol}", settings.currencySymbol,
                "{balance}", Money.format(((TradeMarketPlugin) plugin).economy().balanceCents(player)),
                "{seller}", l.sellerName,
                "{time}", fmt.format(new Date(l.createdAt)));
        place(13, Items.icon(l.item, lore));
        place(11, Items.guiIcon(Material.RED_CONCRETE, lang.line("gui.confirm.cancel"), List.of()));
        place(15, Items.guiIcon(Material.LIME_CONCRETE, lang.line("gui.confirm.buy"), List.of()));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        if (event.getRawSlot() == 11) {
            back();
            return;
        }
        if (event.getRawSlot() == 15) {
            String error = tm.market().buy(player, listingId);
            if (error == null) {
                sound(Sound.ENTITY_PLAYER_LEVELUP);
                // 买完返回市场列表（保留分页/排序/搜索状态），方便连续购买
                new MarketGui(tm, player, returnPage, returnSort, returnSearch).open();
            } else {
                player.sendMessage(tm.lang().msg(error));
                sound(Sound.ENTITY_VILLAGER_NO);
                open(); // 刷新（可能已售出/余额变化）
            }
        }
    }

    private void back() {
        new MarketGui((TradeMarketPlugin) plugin, player, returnPage, returnSort, returnSearch).open();
    }
}

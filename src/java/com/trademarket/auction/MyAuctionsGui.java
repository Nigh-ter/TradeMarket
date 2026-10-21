package com.trademarket.auction;

import com.trademarket.TradeMarketPlugin;
import net.kyori.adventure.text.Component;
import com.trademarket.gui.GuiBase;
import com.trademarket.model.Auction;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

/** 我的拍卖：点击取消（仅无出价时），物品退回邮箱。 */
public final class MyAuctionsGui extends GuiBase {

    private int page;

    public MyAuctionsGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.myauctions.title"));
    }

    private List<Auction> mine() {
        List<Auction> out = new ArrayList<>();
        for (Auction a : ((TradeMarketPlugin) plugin).auction().all()) {
            if (a.sellerId.equals(player.getUniqueId())) out.add(a);
        }
        return out;
    }

    @Override
    protected void build() {
        List<Auction> list = mine();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (page >= maxPage) page = maxPage - 1;
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= list.size()) break;
            Auction a = list.get(index);
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Items.loreLine((a.currentBidCents > 0
                    ? lang.raw("gui.auctionitem.lore-current")
                    : lang.raw("gui.auctionitem.lore-no-bid"))
                    .replace("{bid}", Money.format(Math.max(a.currentBidCents, a.startPriceCents)))
                    .replace("{start}", Money.format(a.startPriceCents))
                    .replace("{symbol}", settings.currencySymbol)
                    .replace("{bidder}", a.topBidderName == null ? "-" : a.topBidderName)));
            lore.add(Items.loreLine(lang.raw("gui.auctionitem.lore-timeleft")
                    .replace("{time}", Items.formatDuration(a.endAt - System.currentTimeMillis()))));
            lore.add(Component.empty());
            lore.add(lang.line(a.currentBidCents > 0
                    ? "gui.myauctions.cancel-has-bids" : "gui.myauctions.cancel-hint"));
            place(CONTENT_SLOTS[i], Items.icon(a.item, lore));
        }
        placePageNav(page, maxPage);
        place(49, Items.guiIcon(Material.ARROW, lang.line("gui.myauctions.back"), List.of()));
        if (list.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.myauctions.empty"), List.of()));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        if (event.getRawSlot() == 49) {
            new AuctionGui(tm, player).open();
            return;
        }
        List<Auction> current = mine();
        int maxPage = Math.max(1, (current.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (event.getRawSlot() == 45 && page > 0) { page--; open(); return; }
        if (event.getRawSlot() == 53 && page < maxPage - 1) { page++; open(); return; }
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == event.getRawSlot()) { contentIndex = i; break; }
        }
        if (contentIndex < 0) return;
        int index = page * CONTENT_SLOTS.length + contentIndex;
        if (index >= current.size()) return;
        Auction a = current.get(index);
        String error = tm.auction().cancel(player, a.id, false);
        if (error == null) sound(Sound.ENTITY_ITEM_PICKUP);
        else {
            player.sendMessage(tm.lang().msg(error));
            sound(Sound.ENTITY_VILLAGER_NO);
        }
        open();
    }
}

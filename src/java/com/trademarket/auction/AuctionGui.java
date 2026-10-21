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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** 拍卖行列表：按结束时间升序，点击进入出价界面。 */
public final class AuctionGui extends GuiBase {

    private int page;

    public AuctionGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.auction.title"));
    }

    /** 从出价界面返回时恢复分页位置。 */
    public void reopenAt(int page) {
        this.page = page;
        open();
    }

    @Override
    protected void build() {
        List<Auction> list = ((TradeMarketPlugin) plugin).auction().all();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (page >= maxPage) page = maxPage - 1;
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= list.size()) break;
            place(CONTENT_SLOTS[i], Items.icon(list.get(index).item, lore(list.get(index))));
        }
        placePageNav(page, maxPage);
        place(51, Items.guiIcon(Material.CHEST, lang.line("gui.market.my-listings"),
                List.of(lang.plain("gui.auction.my-hint"))));
        place(49, Items.guiIcon(Material.PAPER, lang.line("gui.market.page-info"),
                List.of(lang.plain("common.page", "{page}", String.valueOf(page + 1),
                        "{max}", String.valueOf(maxPage)))));
        if (list.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.auction.empty"), List.of()));
        }
    }

    private List<net.kyori.adventure.text.Component> lore(Auction a) {
        String symbol = settings.currencySymbol;
        String current = a.currentBidCents > 0
                ? lang.raw("gui.auctionitem.lore-current")
                        .replace("{bid}", Money.format(a.currentBidCents))
                        .replace("{symbol}", symbol)
                        .replace("{bidder}", a.topBidderName == null ? "?" : a.topBidderName)
                : lang.raw("gui.auctionitem.lore-no-bid")
                        .replace("{start}", Money.format(a.startPriceCents))
                        .replace("{symbol}", symbol);
        List<net.kyori.adventure.text.Component> lines = new java.util.ArrayList<>();
        lines.add(Items.loreLine(current));
        lines.add(Items.loreLine(a.buyoutCents > 0
                ? lang.raw("gui.auctionitem.lore-buyout")
                        .replace("{buyout}", Money.format(a.buyoutCents))
                        .replace("{symbol}", symbol)
                : lang.raw("gui.auctionitem.lore-no-buyout")));
        lines.add(Items.loreLine(lang.raw("gui.auctionitem.lore-timeleft")
                .replace("{time}", Items.formatDuration(a.endAt - System.currentTimeMillis()))));
        lines.add(Items.loreLine(lang.raw("gui.auctionitem.lore-seller")
                .replace("{seller}", a.sellerName)));
        lines.add(Component.empty());
        if (com.trademarket.util.Items.isShulker(a.item)) {
            lines.add(lang.line("gui.listing.lore-preview"));
        }
        lines.add(lang.line("gui.auctionitem.lore-bid-hint"));
        return lines;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        if (event.getRawSlot() == 51) {
            new MyAuctionsGui(tm, player).open();
            return;
        }
        List<Auction> list = tm.auction().all();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (event.getRawSlot() == 45 && page > 0) { page--; open(); return; }
        if (event.getRawSlot() == 53 && page < maxPage - 1) { page++; open(); return; }
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == event.getRawSlot()) { contentIndex = i; break; }
        }
        if (contentIndex < 0) return;
        int index = page * CONTENT_SLOTS.length + contentIndex;
        if (index >= list.size()) return;
        Auction target = list.get(index);
        if (event.isRightClick() && com.trademarket.util.Items.isShulker(target.item)) {
            sound(Sound.UI_BUTTON_CLICK);
            new com.trademarket.gui.ShulkerPreviewGui(tm, player, target.item, () -> reopenAt(page)).open();
            return;
        }
        sound(Sound.UI_BUTTON_CLICK);
        new BidGui(tm, player, target.id, page).open();
    }
}

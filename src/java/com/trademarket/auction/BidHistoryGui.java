package com.trademarket.auction;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 拍卖出价历史：金额倒序展示该拍卖的全部出价记录。 */
public final class BidHistoryGui extends GuiBase {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final long auctionId;
    private int page;

    private TradeMarketPlugin tm() {
        return (TradeMarketPlugin) plugin;
    }

    public BidHistoryGui(TradeMarketPlugin plugin, Player player, long auctionId) {
        super(plugin, player, 6, plugin.lang().title("gui.history.title"));
        this.auctionId = auctionId;
    }

    private List<com.trademarket.data.Database.BidRow> bids() {
        return new ArrayList<>(tm().database().loadBids(auctionId));
    }

    @Override
    protected void build() {
        List<com.trademarket.data.Database.BidRow> list = bids();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (page >= maxPage) page = maxPage - 1;
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= list.size()) break;
            var row = list.get(index);
            place(CONTENT_SLOTS[i], Items.guiIcon(Material.PAPER,
                    Items.loreLine("&f" + row.bidderName()),
                    lang.lore("gui.history.lore",
                            "{amount}", Money.format(row.amount()),
                            "{symbol}", settings.currencySymbol,
                            "{time}", FMT.format(new Date(row.time())))));
        }
        placePageNav(page, maxPage);
        place(49, Items.guiIcon(Material.ARROW, lang.line("gui.history.back"), List.of()));
        if (list.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.history.empty"), List.of()));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        List<com.trademarket.data.Database.BidRow> list = bids();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (event.getRawSlot() == 45 && page > 0) { page--; open(); return; }
        if (event.getRawSlot() == 53 && page < maxPage - 1) { page++; open(); return; }
        if (event.getRawSlot() == 49) {
            BidGui back = new BidGui((TradeMarketPlugin) plugin, player, auctionId, 0);
            back.open();
        }
    }
}

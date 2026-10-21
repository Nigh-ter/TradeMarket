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

/**
 * 出价界面：显示拍卖详情 + 快捷出价（最低加价 / +10%）+ 自定义输入 + 一口价。
 */
public final class BidGui extends GuiBase {

    private final long auctionId;
    private final int returnPage;

    public BidGui(TradeMarketPlugin plugin, Player player, long auctionId, int returnPage) {
        super(plugin, player, 5, plugin.lang().title("gui.bid.title"));
        this.auctionId = auctionId;
        this.returnPage = returnPage;
    }

    private Auction auction() {
        return ((TradeMarketPlugin) plugin).auction().get(auctionId);
    }

    private long minBid(Auction a) {
        return a.currentBidCents == 0
                ? a.startPriceCents
                : a.currentBidCents + settings.auctionMinIncrementCents;
    }

    @Override
    protected void build() {
        fillBorder();
        Auction a = auction();
        if (a == null) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("auction.gone"), List.of()));
            return;
        }
        place(13, Items.icon(a.item, infoLore(a)));
        String symbol = settings.currencySymbol;

        long min = minBid(a);
        place(29, Items.guiIcon(Material.LIME_TERRACOTTA,
                lang.line("gui.bid.min-increment"),
                lang.lore("gui.bid.min-increment-lore",
                        "{amount}", Money.format(min), "{symbol}", symbol)));

        long plusTen = Math.max(min, (long) Math.ceil(a.currentBidCents * 1.10));
        if (a.currentBidCents == 0) plusTen = Math.max(min, a.startPriceCents * 2);
        place(31, Items.guiIcon(Material.GOLD_BLOCK,
                lang.line("gui.bid.plus-ten"),
                lang.lore("gui.bid.plus-ten-lore",
                        "{amount}", Money.format(plusTen), "{symbol}", symbol)));

        place(33, Items.guiIcon(Material.WRITABLE_BOOK, lang.line("gui.bid.custom"), List.of()));

        if (a.buyoutCents > 0) {
            place(40, Items.guiIcon(Material.EMERALD_BLOCK, lang.line("gui.bid.buyout"),
                    lang.lore("gui.bid.buyout-lore",
                            "{buyout}", Money.format(a.buyoutCents), "{symbol}", symbol)));
        }
        place(36, Items.guiIcon(Material.ARROW, lang.line("gui.bid.back"), List.of()));
        place(42, Items.guiIcon(Material.BOOK, lang.line("gui.bid.history"), List.of()));
    }

    private List<net.kyori.adventure.text.Component> infoLore(Auction a) {
        String symbol = settings.currencySymbol;
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        lines.add(Items.loreLine((a.currentBidCents > 0
                ? lang.raw("gui.auctionitem.lore-current")
                : lang.raw("gui.auctionitem.lore-no-bid"))
                .replace("{bid}", Money.format(Math.max(a.currentBidCents, a.startPriceCents)))
                .replace("{start}", Money.format(a.startPriceCents))
                .replace("{symbol}", symbol)
                .replace("{bidder}", a.topBidderName == null ? "-" : a.topBidderName)));
        if (a.buyoutCents > 0) {
            lines.add(Items.loreLine(lang.raw("gui.auctionitem.lore-buyout")
                    .replace("{buyout}", Money.format(a.buyoutCents))
                    .replace("{symbol}", symbol)));
        }
        lines.add(Items.loreLine(lang.raw("gui.auctionitem.lore-timeleft")
                .replace("{time}", Items.formatDuration(a.endAt - System.currentTimeMillis()))));
        lines.add(Items.loreLine(lang.raw("gui.auctionitem.lore-seller")
                .replace("{seller}", a.sellerName)));
        lines.add(Component.empty());
        lines.add(lang.line("gui.bid.info"));
        return lines;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        Auction a = auction();
        if (event.getRawSlot() == 36) {
            AuctionGui back = new AuctionGui(tm, player);
            back.reopenAt(returnPage);
            return;
        }
        if (event.getRawSlot() == 42) {
            new BidHistoryGui(tm, player, auctionId).open();
            return;
        }
        if (a == null) {
            player.sendMessage(tm.lang().msg("auction.gone"));
            sound(Sound.ENTITY_VILLAGER_NO);
            open();
            return;
        }
        switch (event.getRawSlot()) {
            case 29 -> doBid(tm, minBid(a));
            case 31 -> doBid(tm, quickBid(a));
            case 33 -> {
                player.closeInventory();
                tm.chatInput().request(player, "auction.custom-bid", text -> {
                    Long cents = Money.parse(text);
                    if (cents == null) {
                        player.sendMessage(tm.lang().msg("common.invalid-number"));
                        reopen();
                        return;
                    }
                    apply(tm, tm.auction().bid(player, auctionId, cents));
                });
            }
            case 40 -> {
                if (a.buyoutCents <= 0) return;
                apply(tm, tm.auction().buyout(player, auctionId));
                open();
            }
            default -> { }
        }
    }

    private long quickBid(Auction a) {
        if (a.currentBidCents == 0) return Math.max(minBid(a), a.startPriceCents * 2);
        return Math.max(minBid(a), (long) Math.ceil(a.currentBidCents * 1.10));
    }

    private void doBid(TradeMarketPlugin tm, long amount) {
        apply(tm, tm.auction().bid(player, auctionId, amount));
        open();
    }

    private void apply(TradeMarketPlugin tm, String error) {
        if (error == null) {
            sound(Sound.BLOCK_NOTE_BLOCK_PLING);
        } else {
            player.sendMessage(tm.lang().msg(error));
            sound(Sound.ENTITY_VILLAGER_NO);
        }
    }

    private void reopen() {
        open();
    }
}

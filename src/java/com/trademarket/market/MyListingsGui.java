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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 我的在售：点击即下架，物品经邮箱退回。 */
public final class MyListingsGui extends GuiBase {

    private int page;

    public MyListingsGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.mylistings.title"));
    }

    private List<Listing> mine() {
        List<Listing> out = new ArrayList<>();
        for (Listing l : ((TradeMarketPlugin) plugin).market().all()) {
            if (l.sellerId.equals(player.getUniqueId())) out.add(l);
        }
        return out;
    }

    @Override
    protected void build() {
        List<Listing> list = mine();
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (page >= maxPage) page = maxPage - 1;
        fillBorder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= list.size()) break;
            Listing l = list.get(index);
            var lore = new ArrayList<>(lang.lore("gui.listing.lore",
                    "{unit}", Money.format(l.amount == 0 ? l.unitPriceCents : Math.round(l.unitPriceCents * 1.0 / l.amount)),
                    "{amount}", String.valueOf(l.amount),
                    "{total}", Money.format(l.totalCents()),
                    "{symbol}", settings.currencySymbol,
                    "{seller}", l.sellerName,
                    "{time}", fmt.format(new Date(l.createdAt))));
            lore.add(lang.line("gui.mylistings.cancel-hint"));
            place(CONTENT_SLOTS[i], Items.icon(l.item, lore));
        }
        placePageNav(page, maxPage);
        place(49, Items.guiIcon(Material.ARROW, lang.line("gui.mylistings.back"),
                List.of(lang.plain("gui.common.back"))));
        if (list.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.mylistings.empty"), List.of()));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        if (event.getRawSlot() == 49) {
            new MarketGui(tm, player).open();
            return;
        }
        List<Listing> current = mine();
        int maxPage = Math.max(1, (current.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (event.getRawSlot() == 45 && page > 0) {
            page--;
            open();
            return;
        }
        if (event.getRawSlot() == 53 && page < maxPage - 1) {
            page++;
            open();
            return;
        }
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == event.getRawSlot()) { contentIndex = i; break; }
        }
        if (contentIndex < 0) return;
        List<Listing> list = mine();
        int index = page * CONTENT_SLOTS.length + contentIndex;
        if (index >= list.size()) return;
        Listing l = list.get(index);
        String error = tm.market().cancel(player, l.id, false);
        if (error == null) sound(Sound.ENTITY_ITEM_PICKUP);
        else player.sendMessage(tm.lang().msg(error));
        open();
    }
}

package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.model.Listing;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 全球市场浏览界面：分页 / 排序 / 搜索 / 我的在售。布局固定在代码里，文案走语言文件。 */
public final class MarketGui extends com.trademarket.gui.GuiBase {

    public enum Sort { NEWEST, PRICE_ASC, PRICE_DESC, NAME }

    private static final int PER_PAGE = com.trademarket.gui.GuiBase.CONTENT_SLOTS.length;

    private int page;
    private Sort sort = Sort.NEWEST;
    private String search = "";
    private Category category = Category.ALL;
    private Long minPriceFilter;
    private Long maxPriceFilter;

    public MarketGui(TradeMarketPlugin plugin, Player player) {
        this(plugin, player, 0, Sort.NEWEST, "");
    }

    public MarketGui(TradeMarketPlugin plugin, Player player, int page, Sort sort, String search) {
        super(plugin, player, 6, plugin.lang().title("gui.market.title"));
        this.page = page;
        this.sort = sort;
        this.search = search == null ? "" : search;
    }

    private List<Listing> filtered() {
        MarketManager market = ((TradeMarketPlugin) plugin).market();
        List<Listing> list = new ArrayList<>();
        String q = search.toLowerCase(Locale.ROOT).trim();
        for (Listing l : market.all()) {
            if (!q.isEmpty() && !Items.searchableName(l.item).contains(q)) continue;
            if (category != Category.ALL && Category.of(l.item) != category) continue;
            if (minPriceFilter != null && l.unitPriceCents < minPriceFilter) continue;
            if (maxPriceFilter != null && l.unitPriceCents > maxPriceFilter) continue;
            list.add(l);
        }
        switch (sort) {
            case PRICE_ASC -> list.sort(Comparator.comparingLong(l -> l.unitPriceCents));
            case PRICE_DESC -> list.sort(Comparator.comparingLong((Listing l) -> -l.unitPriceCents));
            case NAME -> list.sort(Comparator.comparing(l -> Items.searchableName(l.item)));
            default -> { /* 已按 id 倒序 */ }
        }
        return list;
    }

    @Override
    protected void build() {
        List<Listing> list = filtered();
        int maxPage = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= maxPage) page = maxPage - 1;

        fillBorder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= list.size()) break;
            Listing l = list.get(index);
            place(CONTENT_SLOTS[i], Items.icon(l.item, listingLore(l, fmt)));
        }

        // 导航行
        place(45, com.trademarket.util.Items.guiIcon(page > 0 ? Material.ARROW : Material.GRAY_DYE,
                lang.line("gui.market.prev"), List.of()));
        place(53, com.trademarket.util.Items.guiIcon(page < maxPage - 1 ? Material.ARROW : Material.GRAY_DYE,
                lang.line("gui.market.next"), List.of()));
        place(46, com.trademarket.util.Items.guiIcon(Material.ITEM_FRAME,
                lang.line("gui.market.category"),
                List.of(lang.plain("gui.market.category-" + category.name()))));
        place(47, com.trademarket.util.Items.guiIcon(Material.SPYGLASS,
                lang.line("gui.market.search"),
                lang.lore("gui.market.searching", "{query}", search)));
        place(48, com.trademarket.util.Items.guiIcon(Material.COMPARATOR,
                lang.line("gui.market.sort"),
                List.of(lang.plain("gui.market.sort-" + sort.name()))));
        place(50, com.trademarket.util.Items.guiIcon(Material.GOLD_INGOT,
                lang.line("gui.market.orders"),
                lang.lore("gui.market.orders-hint")));
        place(51, com.trademarket.util.Items.guiIcon(Material.CHEST,
                lang.line("gui.market.my-listings"), List.of()));
        place(52, com.trademarket.util.Items.guiIcon(Material.GOLD_NUGGET,
                lang.line("gui.market.price-filter"),
                lang.lore("gui.market.price-filtering",
                        "{min}", minPriceFilter == null ? lang.raw("gui.market.price-off") : Money.format(minPriceFilter),
                        "{max}", maxPriceFilter == null ? lang.raw("gui.market.price-off") : Money.format(maxPriceFilter))));
        place(49, com.trademarket.util.Items.guiIcon(Material.PAPER,
                lang.line("gui.market.page-info"),
                List.of(lang.plain("common.page", "{page}", String.valueOf(page + 1),
                        "{max}", String.valueOf(maxPage)))));

        if (list.isEmpty()) {
            place(22, com.trademarket.util.Items.guiIcon(Material.BARRIER,
                    lang.line("gui.market.empty"), List.of()));
        }
    }

    private List<Component> listingLore(Listing l, SimpleDateFormat fmt) {
        String symbol = settings.currencySymbol;
        List<Component> lore = new ArrayList<>(lang.lore("gui.listing.lore",
                "{unit}", Money.format(l.unitPriceCents),
                "{amount}", String.valueOf(l.amount),
                "{total}", Money.format(l.totalCents()),
                "{symbol}", symbol,
                "{seller}", l.sellerName,
                "{time}", fmt.format(new Date(l.createdAt))));
        long avg = ((TradeMarketPlugin) plugin).market().recentAverage(l.item.getType());
        if (avg > 0 && lore.size() >= 2) {
            lore.add(lore.size() - 2, com.trademarket.util.Items.loreLine(
                    lang.raw("gui.listing.lore-avg")
                            .replace("{symbol}", symbol)
                            .replace("{avg}", Money.format(avg))));
        }
        if (Items.isShulker(l.item) && lore.size() >= 2) {
            lore.add(lore.size() - 2, lang.line("gui.listing.lore-preview"));
        }
        return lore;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        MarketManager market = ((TradeMarketPlugin) plugin).market();
        switch (slot) {
            case 45 -> { if (page > 0) { page--; open(); } }
            case 53 -> {
                List<Listing> list = filtered();
                int maxPage = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
                if (page < maxPage - 1) { page++; open(); }
            }
            case 47 -> {
                player.closeInventory();
                ((TradeMarketPlugin) plugin).chatInput().request(player, "market.search-prompt", text -> {
                    MarketGui next = new MarketGui((TradeMarketPlugin) plugin, player, 0, sort,
                            text.equals("-") ? "" : text);
                    next.open();
                });
            }
            case 48 -> {
                sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
                page = 0;
                open();
            }
            case 46 -> {
                category = Category.values()[(category.ordinal() + 1) % Category.values().length];
                page = 0;
                open();
            }
            case 52 -> {
                player.closeInventory();
                ((TradeMarketPlugin) plugin).chatInput().request(player, "market.price-prompt", text -> {
                    applyPriceFilter(text);
                    open();
                });
            }
            case 51 -> new MyListingsGui((TradeMarketPlugin) plugin, player).open();
            case 50 -> new OrdersGui((TradeMarketPlugin) plugin, player).open();
            case 49 -> { /* 页码信息，无动作 */ }
            default -> {
                int contentIndex = contentIndexOf(slot);
                if (contentIndex < 0) return;
                List<Listing> list = filtered();
                int index = page * PER_PAGE + contentIndex;
                if (index >= list.size()) return;
                Listing l = list.get(index);
                if (event.isRightClick() && Items.isShulker(l.item)) {
                    sound(Sound.UI_BUTTON_CLICK);
                    new com.trademarket.gui.ShulkerPreviewGui((TradeMarketPlugin) plugin, player, l.item, this::open).open();
                    return;
                }
                sound(Sound.UI_BUTTON_CLICK);
                new ConfirmBuyGui((TradeMarketPlugin) plugin, player, l.id, page, sort, search).open();
            }
        }
    }

    /** 解析价格筛选输入：'-' 清除；单数=仅最低价；'最低 最高' 双边（单边用 - 占位）。 */
    private void applyPriceFilter(String text) {
        Long min = null;
        Long max = null;
        String[] parts = text.trim().split("\\s+");
        if (!(parts.length == 1 && parts[0].equals("-"))) {
            if (parts.length >= 1 && !parts[0].equals("-")) {
                Long v = Money.parse(parts[0]);
                if (v != null) min = v;
            }
            if (parts.length >= 2 && !parts[1].equals("-")) {
                Long v = Money.parse(parts[1]);
                if (v != null) max = v;
            }
        }
        this.minPriceFilter = min;
        this.maxPriceFilter = max;
        this.page = 0;
    }

    private static int contentIndexOf(int slot) {
        int[] slots = com.trademarket.gui.GuiBase.CONTENT_SLOTS;
        for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        return -1;
    }
}

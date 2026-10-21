package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.util.Items;
import com.trademarket.util.ItemNamesZh;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 求购发布向导：选择求购物品（手持识别 / 聊天输入材质或中文名）→
 * 切换单价/总价模式 → 输入价格与数量 → 确认发布。
 * 状态存于 OrderManager.OrderDraft，聊天输入往返间不丢失。
 */
public final class OrderCreateGui extends GuiBase {

    private final OrderManager.OrderDraft draft;

    public OrderCreateGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.ordercreate.title"));
        this.draft = ((TradeMarketPlugin) plugin).order().draft(player.getUniqueId());
    }

    private OrderManager om() {
        return ((TradeMarketPlugin) plugin).order();
    }

    private TradeMarketPlugin tm() {
        return (TradeMarketPlugin) plugin;
    }

    @Override
    protected void build() {
        fillBorder();
        // 13 样品预览
        if (draft.sample != null) {
            place(13, Items.icon(draft.sample, lang.lore("gui.ordercreate.sample-lore",
                    "{provider}", draft.provider.equals(com.trademarket.item.ItemProviderRegistry.VANILLA)
                            ? lang.raw("gui.ordercreate.provider-vanilla") : draft.provider,
                    "{strict}", lang.raw(draft.strictName
                            ? "gui.ordercreate.strict-on" : "gui.ordercreate.strict-off"))));
        } else {
            place(13, Items.guiIcon(Material.GRAY_DYE, lang.line("gui.ordercreate.no-sample"),
                    lang.lore("gui.ordercreate.no-sample-lore")));
        }
        // 28 添加求购物品（聊天输入）  30 清除样品
        place(28, Items.guiIcon(Material.WRITABLE_BOOK, lang.line("gui.ordercreate.add-item"),
                lang.lore("gui.ordercreate.add-item-lore")));
        place(30, Items.guiIcon(Material.BUCKET, lang.line("gui.ordercreate.clear"),
                List.of(lang.plain("gui.common.back"))));
        // 31 单价模式（上） / 32 总价模式（下），当前模式高亮
        boolean unit = draft.unitMode;
        place(31, Items.guiIcon(unit ? Material.GOLD_BLOCK : Material.GOLD_NUGGET,
                lang.line("gui.ordercreate.mode-unit"),
                lang.lore(unit ? "gui.ordercreate.mode-active" : "gui.ordercreate.mode-idle")));
        place(32, Items.guiIcon(!unit ? Material.GOLD_BLOCK : Material.GOLD_NUGGET,
                lang.line("gui.ordercreate.mode-total"),
                lang.lore(!unit ? "gui.ordercreate.mode-active" : "gui.ordercreate.mode-idle")));
        // 33 设置价格 / 34 设置数量
        place(33, Items.guiIcon(Material.SUNFLOWER, lang.line("gui.ordercreate.set-price"),
                lang.lore("gui.ordercreate.set-price-lore",
                        "{price}", draft.priceCents == null ? lang.raw("gui.ordercreate.unset")
                                : Money.format(draft.priceCents),
                        "{symbol}", settings.currencySymbol)));
        place(34, Items.guiIcon(Material.GLOWSTONE_DUST, lang.line("gui.ordercreate.set-amount"),
                lang.lore("gui.ordercreate.set-amount-lore",
                        "{amount}", draft.amount == null ? lang.raw("gui.ordercreate.unset")
                                : String.valueOf(draft.amount))));
        // 40 状态摘要
        place(40, Items.guiIcon(Material.BOOK, lang.line("gui.ordercreate.summary"),
                summaryLore()));
        // 49 确认发布
        boolean ready = draft.sample != null && draft.priceCents != null && draft.priceCents > 0
                && draft.amount != null && draft.amount >= 1;
        place(49, Items.guiIcon(ready ? Material.EMERALD_BLOCK : Material.GRAY_DYE,
                lang.line("gui.ordercreate.confirm"), List.of()));
    }

    private List<net.kyori.adventure.text.Component> summaryLore() {
        String symbol = settings.currencySymbol;
        if (draft.sample == null || draft.priceCents == null || draft.amount == null) {
            return lang.lore("gui.ordercreate.summary-incomplete");
        }
        long unit = draft.unitMode ? draft.priceCents
                : (draft.amount == 0 ? draft.priceCents : (draft.priceCents + draft.amount - 1) / draft.amount);
        long total = draft.unitMode ? draft.priceCents * draft.amount : draft.priceCents;
        return lang.lore("gui.ordercreate.summary-lore",
                "{item}", com.trademarket.util.Items.plainName(draft.sample),
                "{unit}", Money.format(unit),
                "{total}", Money.format(total),
                "{amount}", String.valueOf(draft.amount),
                "{symbol}", symbol);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        switch (slot) {
            case 13 -> { // 点击样品 = 清除
                draft.sample = null;
                draft.provider = com.trademarket.item.ItemProviderRegistry.VANILLA;
                draft.strictName = false;
                sound(Sound.UI_BUTTON_CLICK);
                open();
            }
            case 28 -> { // 添加求购物品：聊天输入材质 ID 或中文名
                player.closeInventory();
                tm().chatInput().request(player, "order.input-item", text -> {
                    var draft2 = om().draft(player.getUniqueId());
                    Material m = resolveItemInput(text);
                    if (m == null) {
                        // 非原版物品 → 自定义关键词求购单（卖家手持物品提交申请，买家验货）
                        if (text.trim().length() < 1 || text.trim().length() > 32) {
                            player.sendMessage(tm().lang().msg("order.item-not-found", "{input}", text));
                        } else {
                            draft2.material = Material.AIR;
                            draft2.customName = text.trim();
                            draft2.sample = com.trademarket.util.Items.guiIcon(Material.PAPER,
                                    com.trademarket.util.Items.loreLine(text.trim()),
                                    java.util.List.of());
                            draft2.provider = com.trademarket.item.ItemProviderRegistry.VANILLA;
                            draft2.strictName = false;
                            player.sendMessage(tm().lang().msg("order.custom-set", "{name}", text.trim()));
                        }
                    } else {
                        draft2.material = m;
                        draft2.customName = null;
                        draft2.sample = new ItemStack(m);
                        draft2.provider = com.trademarket.item.ItemProviderRegistry.VANILLA;
                        draft2.strictName = true; // 文字发布：拒绝改名物品
                        player.sendMessage(tm().lang().msg("order.item-set",
                                "{item}", com.trademarket.util.Items.displayName(draft2.sample)));
                    }
                    new OrderCreateGui(tm(), player).open();
                });
            }
            case 30 -> { // 清除样品
                draft.sample = null;
                draft.provider = com.trademarket.item.ItemProviderRegistry.VANILLA;
                draft.strictName = false;
                sound(Sound.UI_BUTTON_CLICK);
                open();
            }
            case 31 -> { // 单价模式
                draft.unitMode = true;
                sound(Sound.UI_BUTTON_CLICK);
                open();
            }
            case 32 -> { // 总价模式
                draft.unitMode = false;
                sound(Sound.UI_BUTTON_CLICK);
                open();
            }
            case 33 -> { // 设置价格
                player.closeInventory();
                tm().chatInput().request(player, "order.input-price", text -> {
                    var draft2 = om().draft(player.getUniqueId());
                    Long v = Money.parse(text);
                    if (v == null || v <= 0) {
                        player.sendMessage(tm().lang().msg("common.invalid-number"));
                    } else {
                        draft2.priceCents = v;
                    }
                    new OrderCreateGui(tm(), player).open();
                });
            }
            case 34 -> { // 设置数量
                player.closeInventory();
                tm().chatInput().request(player, "order.input-amount", text -> {
                    var draft2 = om().draft(player.getUniqueId());
                    try {
                        int v = Integer.parseInt(text.trim());
                        if (v < 1 || v > tm().settings().orderMaxAmount) throw new NumberFormatException();
                        draft2.amount = v;
                    } catch (NumberFormatException e) {
                        player.sendMessage(tm().lang().msg("common.invalid-amount"));
                    }
                    new OrderCreateGui(tm(), player).open();
                });
            }
            case 49 -> { // 确认发布
                if (draft.sample == null || draft.material == null
                        || (draft.material == Material.AIR && draft.customName == null)
                        || draft.priceCents == null || draft.priceCents <= 0
                        || draft.amount == null || draft.amount < 1) {
                    player.sendMessage(tm().lang().msg("order.incomplete"));
                    sound(Sound.ENTITY_VILLAGER_NO);
                    return;
                }
                // 换算单价：单价模式直接用；总价模式 = 总价 / 数量（向上取整，多退少不补）
                long unit = draft.unitMode ? draft.priceCents
                        : (draft.priceCents + draft.amount - 1) / draft.amount;
                long escrow = unit * draft.amount;
                if (!tm().economy().has(player, escrow)) {
                    player.sendMessage(tm().lang().msg("common.not-enough-money"));
                    sound(Sound.ENTITY_VILLAGER_NO);
                    return;
                }
                if (!player.isOp() && om().countBy(player.getUniqueId()) >= om().maxOrdersFor(player.getUniqueId())) {
                    player.sendMessage(tm().lang().msg("order.limit"));
                    sound(Sound.ENTITY_VILLAGER_NO);
                    return;
                }
                String error;
                try {
                    error = om().create(player, draft.material, draft.sample,
                            draft.provider, draft.strictName, draft.customName, unit, draft.amount);
                } catch (Throwable t) {
                    // 任何未预期异常都落日志并给出明确键，杜绝“点了没反应”
                    plugin.getLogger().severe("[求购发布] 未预期异常 material=" + draft.material
                            + " custom=" + draft.customName + " unit=" + unit + " amount=" + draft.amount);
                    t.printStackTrace();
                    error = "common.internal-error";
                }
                if (error == null) {
                    sound(Sound.ENTITY_PLAYER_LEVELUP);
                    om().clearDraft(player.getUniqueId());
                    new OrdersGui(tm(), player).open();
                } else {
                    player.sendMessage(tm().lang().msg(error));
                    sound(Sound.ENTITY_VILLAGER_NO);
                }
            }
            default -> { }
        }
    }

    /** 解析玩家输入的物品：材质 ID（diamond）或中文名（钻石）。 */
    private Material resolveItemInput(String text) {
        String t = text.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        Material m = Material.matchMaterial(t);
        if (m != null) return m;
        return ItemNamesZh.fromZh(text.trim());
    }
}

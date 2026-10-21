package com.trademarket.gui;

import com.trademarket.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 潜影盒内容预览（只读）：市场/拍卖浏览界面里右键潜影盒类商品时打开，
 * 不取出物品即可查看盒内所有内容。所有点击由 GuiListener 统一取消。
 */
public final class ShulkerPreviewGui extends GuiBase {

    private final Runnable back;
    private final ItemStack[] contents;

    public ShulkerPreviewGui(com.trademarket.TradeMarketPlugin plugin,
                             org.bukkit.entity.Player player, ItemStack shulker, Runnable back) {
        super(plugin, player, 6, plugin.lang().title("gui.preview.title"));
        this.back = back;
        this.contents = Items.shulkerContents(shulker);
    }

    @Override
    protected void build() {
        fillBorder();
        if (contents != null) {
            // 盒内 27 格映射到边框内圈的 28 个内容槽，避免物品压住玻璃边框
            for (int i = 0; i < Math.min(contents.length, CONTENT_SLOTS.length); i++) {
                if (contents[i] != null && !contents[i].getType().isAir()) {
                    place(CONTENT_SLOTS[i], contents[i].clone());
                }
            }
        }
        place(49, com.trademarket.util.Items.guiIcon(Material.ARROW,
                lang.line("gui.preview.back"), List.<Component>of()));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (event.getRawSlot() == 49 && back != null) {
            back.run();
        }
    }
}

package com.trademarket.gui;

import com.trademarket.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 通用操作确认界面：展示将执行的操作详情（图标 + 说明），
 * 左下取消 / 右下确认。确认/取消行为由构造方注入。
 */
public final class ConfirmActionGui extends GuiBase {

    private final ItemStack display;
    private final List<Component> info;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private Component confirmLabel;
    private Component cancelLabel;

    public ConfirmActionGui(com.trademarket.TradeMarketPlugin plugin, org.bukkit.entity.Player player,
                            Component title, ItemStack display, List<Component> info,
                            Runnable onConfirm, Runnable onCancel) {
        super(plugin, player, 3, title);
        this.display = display;
        this.info = info;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.confirmLabel = plugin.lang().line("gui.mailconfirm.confirm");
        this.cancelLabel = plugin.lang().line("gui.mailconfirm.cancel");
    }

    /** 按钮文案可自定义的构造器（确认/取消标签语言键）。 */
    public ConfirmActionGui(com.trademarket.TradeMarketPlugin plugin,
                            org.bukkit.entity.Player player, Component title, ItemStack display,
                            List<Component> info, String confirmKey, String cancelKey,
                            Runnable onConfirm, Runnable onCancel) {
        this(plugin, player, title, display, info, onConfirm, onCancel);
        this.confirmLabel = plugin.lang().line(confirmKey);
        this.cancelLabel = plugin.lang().line(cancelKey);
    }

    @Override
    protected void build() {
        fillBorder();
        place(13, Items.icon(display, info));
        place(11, Items.guiIcon(Material.RED_CONCRETE, cancelLabel, List.of()));
        place(15, Items.guiIcon(Material.LIME_CONCRETE, confirmLabel, List.of()));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (event.getRawSlot() == 11) {
            sound(Sound.UI_BUTTON_CLICK);
            player.closeInventory();
            if (onCancel != null) onCancel.run();
            return;
        }
        if (event.getRawSlot() == 15) {
            sound(Sound.UI_BUTTON_CLICK);
            player.closeInventory();
            if (onConfirm != null) onConfirm.run();
        }
    }
}

package com.trademarket.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 全局 GUI 防护：凡是顶部容器 holder 为本插件菜单的一切点击/拖拽一律取消，
 * 再把顶部容器内的点击路由给菜单本身。这样即使子类路由有遗漏也不会复制物品。
 */
public final class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiBase gui)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) return;
        try {
            gui.onClick(event);
        } catch (Throwable t) {
            gui.plugin.getLogger().severe("GUI 点击处理异常: " + t);
            org.bukkit.Bukkit.getLogger().severe("TradeMarket GUI 异常堆栈：");
            t.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiBase) {
            event.setCancelled(true);
        }
    }
}

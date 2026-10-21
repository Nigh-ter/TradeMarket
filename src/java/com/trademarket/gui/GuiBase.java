package com.trademarket.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.List;

/**
 * 菜单基类：以自身为 InventoryHolder 创建容器，点击统一由 {@link GuiListener}
 * 取消并路由到 {@link #onClick}。子类在 {@link #build} 里摆放内容。
 */
public abstract class GuiBase implements InventoryHolder {

    protected final org.bukkit.plugin.java.JavaPlugin plugin;
    protected final com.trademarket.config.Lang lang;
    protected final com.trademarket.config.Settings settings;
    protected final Player player;

    private final Inventory inventory;
    private final int size;

    protected GuiBase(org.bukkit.plugin.java.JavaPlugin plugin, Player player, int rows, Component title) {
        this.plugin = plugin;
        this.lang = ((com.trademarket.TradeMarketPlugin) plugin).lang();
        this.settings = ((com.trademarket.TradeMarketPlugin) plugin).settings();
        this.player = player;
        this.size = rows * 9;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    /** 重新填充内容并打开（可反复调用以刷新同一实例）。 */
    public void open() {
        inventory.clear();
        build();
        player.openInventory(inventory);
    }

    protected abstract void build();

    /** 只处理顶部容器内的有效点击；调用前事件已被取消。 */
    public abstract void onClick(InventoryClickEvent event);

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected void place(int slot, ItemStack item) {
        if (slot >= 0 && slot < size) inventory.setItem(slot, item);
    }

    protected ItemStack borderIcon() {
        return com.trademarket.util.Items.guiIcon(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    /** 用灰色玻璃填满边框一圈。 */
    protected void fillBorder() {
        ItemStack filler = borderIcon();
        for (int i = 0; i < size; i++) {
            if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) {
                inventory.setItem(i, filler);
            }
        }
    }

    protected static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    /** 双分页按钮图标（45 上一页 / 53 下一页）；是否可点由子类自行判断。 */
    protected void placePageNav(int page, int maxPage) {
        place(45, com.trademarket.util.Items.guiIcon(page > 0 ? Material.ARROW : Material.GRAY_DYE,
                lang.line("gui.market.prev"), List.of()));
        place(53, com.trademarket.util.Items.guiIcon(page < maxPage - 1 ? Material.ARROW : Material.GRAY_DYE,
                lang.line("gui.market.next"), List.of()));
    }

    protected void sound(org.bukkit.Sound sound) {
        if (settings.soundsEnabled) {
            player.playSound(player.getLocation(), sound, 1f, 1f);
        }
    }
}

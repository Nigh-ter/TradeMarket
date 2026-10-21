package com.trademarket.market;

import com.trademarket.util.Items;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 市场分类浏览：按物品类型自动归类（参考 PlayerAuctions / zAuctionHouse 的分类体验）。
 */
public enum Category {

    ALL, WEAPONS, TOOLS, ARMOR, BLOCKS, FOOD, MISC;

    public static Category of(ItemStack item) {
        if (item == null) return MISC;
        Material m = item.getType();
        String n = m.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE")
                || m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT
                || m == Material.MACE || n.equals("ARROW") || n.equals("SPECTRAL_ARROW")
                || n.equals("TIPPED_ARROW")) {
            return WEAPONS;
        }
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || m == Material.SHEARS || m == Material.FLINT_AND_STEEL
                || m == Material.FISHING_ROD || m == Material.BRUSH
                || m == Material.COMPASS || m == Material.SPYGLASS) {
            return TOOLS;
        }
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || m == Material.SHIELD || m == Material.ELYTRA
                || n.endsWith("_HORSE_ARMOR") || n.endsWith("HEAD") || n.endsWith("SKULL")) {
            return ARMOR;
        }
        if (m.isEdible() || n.equals("CAKE") || n.equals("PUMPKIN_PIE")
                || n.endsWith("_STEW") || n.equals("MILK_BUCKET")) {
            return FOOD;
        }
        if (m.isBlock()) {
            return BLOCKS;
        }
        return MISC;
    }

    /** 分类按钮/过滤使用的物品中文名或英文名。 */
    public static String displayName(Category c) {
        return switch (c) {
            case WEAPONS -> "武器";
            case TOOLS -> "工具";
            case ARMOR -> "盔甲";
            case BLOCKS -> "方块";
            case FOOD -> "食物";
            case MISC -> "杂项";
            default -> "全部";
        };
    }
}

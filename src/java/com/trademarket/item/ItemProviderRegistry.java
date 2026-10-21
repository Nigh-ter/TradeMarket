package com.trademarket.item;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * 自定义物品来源识别：读取物品 PDC 的全部命名空间，任何非原版（minecraft）、
 * 非本插件（trademarket）的命名空间都视为自定义物品来源 —— 不局限于特定插件，
 * 将来任何自定义物品插件都能被识别。结果以 "插件名:键" 随商品/拍卖落库
 * （item_provider 列）。识别失败或原版物品一律返回 "VANILLA"。
 */
public final class ItemProviderRegistry {

    private ItemProviderRegistry() {
    }

    public static final String VANILLA = "VANILLA";

    /** 识别物品来源；给不出具体键时返回 "插件名"。 */
    public static String resolve(ItemStack item) {
        if (item == null) return VANILLA;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return VANILLA;
        try {
            for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                String ns = key.getNamespace().toLowerCase(Locale.ROOT);
                if (ns.equals("minecraft") || ns.equals("trademarket") || ns.equals("bukkit")) continue;
                String id = meta.getPersistentDataContainer()
                        .get(key, PersistentDataType.STRING);
                return ns.toUpperCase(Locale.ROOT)
                        + (id == null || id.isEmpty() ? "" : ":" + id);
            }
        } catch (Throwable ignored) {
            return VANILLA;
        }
        return VANILLA;
    }
}

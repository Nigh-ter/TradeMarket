package com.trademarket.model;

import org.bukkit.inventory.ItemStack;

/** 市场在售商品。价格单位：分。 */
public final class Listing {

    public long id;
    public final java.util.UUID sellerId;
    public final String sellerName;
    public final String itemData;      // Base64
    public final ItemStack item;       // 内存展示用快照
    public final int amount;
    public final long unitPriceCents;   // 整批总价（v2.8 起：上架金额即整批售价）
    public final long createdAt;
    public final String itemProvider;  // 物品来源插件识别（VANILLA 或 ITEMSADDER:xxx 等）
    public java.util.UUID buyerId;     // 售出时由对账/补发逻辑使用（内存中的在售单为 null）
    public String status;              // 对账用（内存中的在售单为 null）
    public boolean paid;               // 对账用：卖家款项已结
    public boolean delivered;          // 对账用：物品已投递

    public Listing(long id, java.util.UUID sellerId, String sellerName, String itemData,
                   ItemStack item, int amount, long unitPriceCents, long createdAt,
                   String itemProvider) {
        this.id = id;
        this.sellerId = sellerId;
        this.sellerName = sellerName;
        this.itemData = itemData;
        this.item = item;
        this.amount = amount;
        this.unitPriceCents = unitPriceCents;
        this.createdAt = createdAt;
        this.itemProvider = itemProvider;
    }

    public long totalCents() {
        return unitPriceCents;
    }
}

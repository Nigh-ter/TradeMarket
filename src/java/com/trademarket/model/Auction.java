package com.trademarket.model;

import org.bukkit.inventory.ItemStack;

/** 竞价拍卖。价格单位：分。status: ACTIVE / SOLD / EXPIRED / CANCELLED */
public final class Auction {

    public long id;
    public final java.util.UUID sellerId;
    public final String sellerName;
    public final String itemData;
    public final ItemStack item;
    public final long startPriceCents;
    public final long buyoutCents;     // 0 = 无一口价
    public final long createdAt;
    public long endAt;
    public long currentBidCents;       // 0 = 无人出价
    public java.util.UUID topBidderId;
    public String topBidderName;
    public String status;
    public boolean paid;               // 对账用：卖家款项已结
    public boolean delivered;          // 对账用：物品已投递
    public final String itemProvider;  // 物品来源插件识别

    public Auction(long id, java.util.UUID sellerId, String sellerName, String itemData, ItemStack item,
                   long startPriceCents, long buyoutCents, long createdAt, long endAt,
                   String itemProvider) {
        this.id = id;
        this.sellerId = sellerId;
        this.sellerName = sellerName;
        this.itemData = itemData;
        this.item = item;
        this.startPriceCents = startPriceCents;
        this.buyoutCents = buyoutCents;
        this.createdAt = createdAt;
        this.endAt = endAt;
        this.currentBidCents = 0;
        this.status = "ACTIVE";
        this.itemProvider = itemProvider;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}

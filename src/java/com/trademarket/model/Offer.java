package com.trademarket.model;

import org.bukkit.inventory.ItemStack;

/**
 * 求购申请（托管中）：卖家提交的验货物品。
 * status: PENDING / ACCEPTED / REJECTED / RETURNED
 */
public final class Offer {

    public long id;
    public final long orderId;
    public final java.util.UUID sellerId;
    public final String sellerName;
    public final java.util.UUID buyerId;
    public final String itemData;      // Base64（托管的物品）
    public final long unitPriceCents;  // 成交单价（冗余存储，订单关闭后仍可显示）
    public final long time;
    public String status;

    public Offer(long id, long orderId, java.util.UUID sellerId, String sellerName,
                 java.util.UUID buyerId, String itemData, long unitPriceCents,
                 long time, String status) {
        this.id = id;
        this.orderId = orderId;
        this.sellerId = sellerId;
        this.sellerName = sellerName;
        this.buyerId = buyerId;
        this.itemData = itemData;
        this.unitPriceCents = unitPriceCents;
        this.time = time;
        this.status = status;
    }

    public ItemStack item() {
        return com.trademarket.util.Items.deserialize(itemData);
    }
}

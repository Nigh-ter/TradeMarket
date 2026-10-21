package com.trademarket.model;

import org.bukkit.Material;

/**
 * 求购单：买家预先全额托管资金，卖家手持对应物品一键出售。
 * status: OPEN / CLOSED / CANCELLED / EXPIRED
 */
public final class BuyOrder {

    public long id;
    public final java.util.UUID buyerId;
    public final String buyerName;
    public final Material material;
    public final String itemData;      // 样本物品（1 件），用于大厅图标与自定义物品识别
    public final String itemProvider;  // 来源插件识别（VANILLA 或 ITEMSADDER:xxx 等）
    public final boolean strictName;   // true = 出售时拒绝改名物品（文字发布的求购单）
    public final String customName;    // 自定义求购（文字关键词）；非空时 material=AIR，仅接受申请
    public final long unitPriceCents;
    public final int total;
    public int remaining;
    public final long createdAt;
    public String status;

    public BuyOrder(long id, java.util.UUID buyerId, String buyerName, Material material,
                    String itemData, String itemProvider, boolean strictName, String customName, long unitPriceCents,
                    int total, int remaining, long createdAt, String status) {
        this.id = id;
        this.buyerId = buyerId;
        this.buyerName = buyerName;
        this.material = material;
        this.itemData = itemData;
        this.itemProvider = itemProvider;
        this.strictName = strictName;
        this.customName = customName;
        this.unitPriceCents = unitPriceCents;
        this.total = total;
        this.remaining = remaining;
        this.createdAt = createdAt;
        this.status = status;
    }

    /** 样本物品图标（惰性反序列化；损坏返回 null，由界面退回材质图标）。 */
    public org.bukkit.inventory.ItemStack icon() {
        if (itemData == null) return null;
        return com.trademarket.util.Items.deserialize(itemData);
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public long escrowCents() {
        return unitPriceCents * remaining;
    }
}

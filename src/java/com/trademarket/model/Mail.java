package com.trademarket.model;

import org.bukkit.inventory.ItemStack;

/**
 * 邮箱邮件。附件三选多：物品（itemData）/ 金币（moneyCents>0）/ 命令（command）。
 * 命令邮件在领取时以控制台身份执行、{@code %player%} 替换为领取者 ——
 * 这是与称号、抽奖箱等其他插件联动的通用通道（对方插件只需有发放命令）。
 */
public final class Mail {

    public long id;
    public final java.util.UUID ownerId;
    public final String ownerName;
    public final String sender;
    public java.util.UUID senderId;   // 发件人 UUID（玩家互发/求购申请付款用）
    public final String subject;
    public final String body;
    public final String itemData;      // 可空
    public final long moneyCents;      // 0 = 无金币
    public final String command;       // 可空；控制台命令模板，%player% 为领取者
    public String kind = "NORMAL";     // NORMAL / OFFER（求购申请：买家需达成或拒绝）
    public long refId;                 // OFFER 关联的求购单 ID
    public final long createdAt;
    public boolean claimed;

    private ItemStack iconCache;

    public Mail(long id, java.util.UUID ownerId, String ownerName, String sender,
                String subject, String body, String itemData, long moneyCents,
                String command, long createdAt, boolean claimed) {
        this.id = id;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.sender = sender;
        this.subject = subject;
        this.body = body;
        this.itemData = itemData;
        this.moneyCents = moneyCents;
        this.command = command;
        this.createdAt = createdAt;
        this.claimed = claimed;
    }

    /** 惰性反序列化图标；坏数据只影响这一封，返回 null 由界面跳过。 */
    public ItemStack icon() {
        if (itemData == null) return null;
        if (iconCache == null) {
            iconCache = com.trademarket.util.Items.deserialize(itemData);
        }
        return iconCache;
    }
}

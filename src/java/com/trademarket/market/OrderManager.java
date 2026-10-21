package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.model.BuyOrder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 求购单：买家发布时全额托管资金，卖家手持对应物品点击求购单即完成交易。
 * 资金由插件托管（发布时已扣款），出售时直接从托管支付，无二次转账风险。
 */
public final class OrderManager {

    private final TradeMarketPlugin plugin;
    private final Map<Long, BuyOrder> orders = new HashMap<>();
    private long nextId = 1;

    /** 求购发布向导的草稿（聊天输入往返间保持状态）。 */
    public static final class OrderDraft {
        public org.bukkit.Material material;
        public String customName;         // 文字关键词求购（非空 = 自定义单，material=AIR）
        public ItemStack sample;
        public String provider = com.trademarket.item.ItemProviderRegistry.VANILLA;
        public boolean strictName;
        public boolean unitMode = true;   // true = 按单价发布
        public Long priceCents;
        public Integer amount;
    }

    private final Map<UUID, OrderDraft> drafts = new HashMap<>();

    /** 获取（或创建）玩家的发布草稿。 */
    public OrderDraft draft(UUID player) {
        return drafts.computeIfAbsent(player, k -> new OrderDraft());
    }

    public void clearDraft(UUID player) {
        drafts.remove(player);
    }

    /** MailManager 达成后关单（同步内存与调用方引用）。 */
    public void removeClosed(com.trademarket.model.BuyOrder o) {
        orders.remove(o.id);
        o.status = "CLOSED";
        plugin.database().updateOrder(o);
    }

    /** 求购单关闭（卖完/取消/过期）时：全部托管中的申请自动退回卖家邮箱。 */
    public void returnPendingOffersForOrder(long orderId) {
        for (com.trademarket.model.Offer f : plugin.database().loadPendingOffersByOrder(orderId)) {
            if (!plugin.database().flipOfferStatus(f.id, "RETURNED")) continue;
            ItemStack item = f.item();
            if (item != null) {
                try {
                    plugin.mail().sendItem(f.sellerId, f.sellerName, "系统",
                            plugin.lang().raw("order.offer-rejected-subject"),
                            plugin.lang().raw("order.offer-partial-note"), item);
                } catch (Exception e) {
                    plugin.getLogger().severe("求购关闭退回失败 offer=" + f.id + ": " + e.getMessage());
                    plugin.database().tx(f.sellerId, "OFFER_RETURN_FAILED", "offer=" + f.id);
                }
            }
            plugin.database().tx(f.sellerId, "OFFER_AUTO_RETURNED",
                    "offer=" + f.id + " order=" + orderId);
        }
    }

    /** 求购单上限：OP 豁免（无限）> LuckPerms 全部继承组取最大 > 全局默认。 */
    public int maxOrdersFor(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null && (p.isOp() || p.hasPermission("trademarket.admin"))) {
            return Integer.MAX_VALUE;
        }
        int best = plugin.settings().orderMaxPerPlayer;
        for (String group : plugin.economy().luckPerms.groups(player)) {
            Integer over = plugin.settings().groupOrderLimit.get(group.toLowerCase(java.util.Locale.ROOT));
            if (over != null && over > best) best = over;
        }
        return best;
    }

    public OrderManager(TradeMarketPlugin plugin) {
        this.plugin = plugin;
        for (BuyOrder o : plugin.database().loadOpenOrders()) {
            orders.put(o.id, o);
            nextId = Math.max(nextId, o.id + 1);
        }
    }

    public int count() {
        return orders.size();
    }

    public int countBy(UUID buyer) {
        int n = 0;
        for (BuyOrder o : orders.values()) if (o.buyerId.equals(buyer)) n++;
        return n;
    }

    private void logCreate(String reason) {
        plugin.getLogger().warning("[求购发布失败] " + reason);
    }

    /** 按单价从高到低（买家出价慷慨的排前面）。 */
    public List<BuyOrder> all() {
        List<BuyOrder> out = new ArrayList<>(orders.values());
        out.sort((a, b) -> Long.compare(b.unitPriceCents, a.unitPriceCents));
        return out;
    }

    public BuyOrder get(long id) {
        return orders.get(id);
    }

    // ==================== 发布 ====================

    /**
     * 发布求购单（经确认 GUI 后调用）。
     * sample：样品物品（文字发布 = new ItemStack(material)；手持发布 = 手上实物，可含自定义来源）。
     * strictName：true = 出售时拒绝带自定义名称的物品（文字发布的求购单防改名冒充）。
     * 返回 null=成功。
     */
    public String create(Player player, org.bukkit.Material material, ItemStack sample,
                         String provider, boolean strictName, String customName,
                         long unitPriceCents, int amount) {
        if (!plugin.economy().available()) { logCreate("经济不可用"); return "common.economy-unavailable"; }
        if (plugin.isTradeBlocked(player)) { logCreate("世界禁用"); return "common.world-disabled"; }
        if (unitPriceCents < plugin.settings().minPriceCents) { logCreate("低于限价"); return "market.price-too-low"; }
        if (unitPriceCents > plugin.settings().maxPriceCents) { logCreate("超过限价"); return "market.price-too-high"; }
        if (amount < 1 || amount > plugin.settings().orderMaxAmount) { logCreate("数量非法:" + amount); return "common.invalid-amount"; }
        if (countBy(player.getUniqueId()) >= maxOrdersFor(player.getUniqueId())) { logCreate("达到上限"); return "order.limit"; }
        long total = unitPriceCents * amount;
        if (!plugin.economy().has(player, total)) { logCreate("余额不足 escrow=" + total); return "common.not-enough-money"; }

        ItemStack sampleItem = sample == null ? new ItemStack(material) : sample;
        if (material == org.bukkit.Material.AIR) {
            sampleItem = com.trademarket.util.Items.guiIcon(org.bukkit.Material.PAPER,
                    com.trademarket.util.Items.loreLine(customName), java.util.List.of());
        }
        String data = com.trademarket.util.Items.serialize(sampleItem.asQuantity(1));
        if (data == null) { logCreate("样品序列化失败"); return "common.internal-error"; }
        BuyOrder o = new BuyOrder(nextId++, player.getUniqueId(), player.getName(),
                material, data, provider, strictName, customName, unitPriceCents, amount, amount,
                System.currentTimeMillis(), "OPEN");
        try {
            plugin.database().insertOrder(o);
        } catch (Exception e) {
            nextId--;
            return "common.internal-error";
        }
        if (!plugin.economy().withdraw(player, total)) {
            orders.remove(o.id);
            plugin.database().updateOrder(new BuyOrder(o.id, o.buyerId, o.buyerName, o.material,
                    o.itemData, o.itemProvider, o.strictName, o.customName, o.unitPriceCents, o.total, 0, o.createdAt, "CANCELLED"));
            return "common.not-enough-money";
        }
        orders.put(o.id, o);
        plugin.database().tx(player.getUniqueId(), "ORDER_CREATE",
                "order=" + o.id + " material=" + o.material + " unit=" + unitPriceCents
                        + " amount=" + amount + " escrow=" + total);
        player.sendMessage(plugin.lang().msg("order.created",
                "{item}", com.trademarket.util.Items.displayName(sampleItem),
                "{amount}", String.valueOf(amount),
                "{symbol}", plugin.settings().currencySymbol,
                "{unit}", com.trademarket.util.Money.format(unitPriceCents),
                "{total}", com.trademarket.util.Money.format(total)));
        if (plugin.settings().broadcastOrders) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(plugin.lang().msg("order.created-broadcast",
                        "{player}", player.getName(),
                        "{item}", com.trademarket.util.Items.displayName(sampleItem),
                        "{amount}", String.valueOf(amount),
                        "{unit}", com.trademarket.util.Money.format(unitPriceCents))
                        .append(plugin.lang().action("gui.click.open-orders", "/tk orders")));
            }
        }
        return null;
    }

    // ==================== 取消 ====================

    /** 取消求购，退回剩余托管金。返回 null=成功。 */
    public String cancel(Player player, long orderId, boolean admin) {
        BuyOrder o = orders.get(orderId);
        if (o == null || !o.isOpen()) return "order.gone";
        if (!admin && !o.buyerId.equals(player.getUniqueId())) return "order.cancel-not-owner";
        long refund = o.escrowCents();
        if (refund > 0 && !plugin.economy().deposit(Bukkit.getOfflinePlayer(o.buyerId), refund)) {
            plugin.getLogger().severe("求购取消退款失败 order=" + o.id + " player=" + o.buyerId
                    + " amount=" + refund);
            plugin.database().tx(o.buyerId, "ORDER_REFUND_FAILED",
                    "order=" + o.id + " amount=" + refund);
            return "common.internal-error";
        }
        o.status = "CANCELLED";
        orders.remove(o.id);
        plugin.database().updateOrder(o);
        returnPendingOffersForOrder(o.id);
        plugin.database().tx(o.buyerId, admin ? "ORDER_ADMIN_CANCEL" : "ORDER_CANCEL",
                "order=" + o.id + " refund=" + refund);
        player.sendMessage(plugin.lang().msg("order.cancel-success",
                "{symbol}", plugin.settings().currencySymbol,
                "{refund}", com.trademarket.util.Money.format(refund)));
        return null;
    }

    // ==================== 出售给求购单 ====================

    /**
     * 出售给求购单（经确认 GUI 后调用）：自动扫描背包内所有匹配物品出售，无需手持。
     * 普通物品立即成交；strict 求购单中的改名物品会转为"求购申请"邮件，
     * 由买家验货后达成或拒绝（防改名冒充）。
     * 返回 null=成功。
     */
    public String fill(Player player, long orderId) {
        BuyOrder o = orders.get(orderId);
        if (o == null || !o.isOpen()) return "order.gone";
        if (o.buyerId.equals(player.getUniqueId())) return "order.sell-own";
        if (!plugin.economy().available()) return "common.economy-unavailable";
        if (o.material == null) return "order.gone";

        int needed = o.remaining;
        java.util.List<ItemStack> plain = new ArrayList<>();   // 匹配物品：立即成交
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length && needed > 0; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getType() != o.material) continue;
            String p = plugin.settings().customItemDetect
                    ? com.trademarket.item.ItemProviderRegistry.resolve(it)
                    : com.trademarket.item.ItemProviderRegistry.VANILLA;
            if (!p.equals(o.itemProvider)) continue;
            var meta = it.getItemMeta();
            if (o.strictName && meta != null && meta.hasDisplayName()) continue; // 严格单不收改名物品
            int take = Math.min(it.getAmount(), needed);
            ItemStack taken = it.asQuantity(take);
            int left = it.getAmount() - take;
            if (left > 0) it.setAmount(left);
            else player.getInventory().setItem(i, null);
            plain.add(taken);
            needed -= take;
        }
        int sold = plain.stream().mapToInt(ItemStack::getAmount).sum();
        if (sold == 0) return "order.not-holding";
        long gross = o.unitPriceCents * sold;
        long tax = Math.round(gross * plugin.settings().orderSaleTaxPercent / 100.0);
        long payout = gross - tax;

        if (!plugin.economy().deposit(player, payout)) {
            for (ItemStack st : plain) {
                var leftover = player.getInventory().addItem(st);
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            return "common.internal-error";
        }

        // 普通部分：立即经邮箱交付买家
        String subject = plugin.lang().raw("order.fill-subject");
        try {
            for (ItemStack st : plain) {
                plugin.mail().sendItem(o.buyerId, o.buyerName, player.getName(), subject, "", st);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("求购发货邮件失败 order=" + o.id + ": " + e.getMessage());
        }

        o.remaining -= sold;
        if (o.remaining <= 0) {
            o.remaining = 0;
            o.status = "CLOSED";
            orders.remove(o.id);
        }
            returnPendingOffersForOrder(o.id);
        plugin.database().updateOrder(o);
        plugin.history().record(o.material.name(), o.unitPriceCents);
        plugin.database().insertSale(o.material.name(), o.unitPriceCents, sold, System.currentTimeMillis());
        plugin.database().tx(player.getUniqueId(), "ORDER_FILL",
                "order=" + o.id + " amount=" + sold + " gross=" + gross + " tax=" + tax + " payout=" + payout);
        Player buyerOnline = Bukkit.getPlayer(o.buyerId);
        if (buyerOnline != null) {
            buyerOnline.sendMessage(plugin.lang().msg("order.filled-notify-buyer",
                    "{seller}", player.getName(),
                    "{amount}", String.valueOf(sold),
                    "{item}", com.trademarket.util.Items.displayName(plain.get(0)),
                    "{remaining}", String.valueOf(o.remaining)));
        }
        player.sendMessage(plugin.lang().msg("order.fill-success",
                "{amount}", String.valueOf(sold),
                "{item}", com.trademarket.util.Items.displayName(plain.get(0)),
                "{income}", com.trademarket.util.Money.format(payout),
                "{symbol}", plugin.settings().currencySymbol,
                "{tax}", com.trademarket.util.Money.format(tax),
                "{total}", com.trademarket.util.Money.format(payout)));
        sound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        return null;
    }

    /**
     * 提交求购申请（托管验货）：手持物品整组托管，发给买家邮箱等确认。
     * 买家达成即成交；拒绝/超时退回。返回 null=成功。
     */
    public String submitOffer(Player player, BuyOrder order, ItemStack hand) {
        if (!plugin.economy().available()) return "common.economy-unavailable";
        if (order == null || !order.isOpen()) return "order.gone";
        if (order.buyerId.equals(player.getUniqueId())) return "order.sell-own";
        if (order.material != org.bukkit.Material.AIR && hand.getType() != order.material) {
            return "order.not-holding";
        }
        int n = hand.getAmount();
        long gross = order.unitPriceCents * n;
        long tax = Math.round(gross * plugin.settings().orderSaleTaxPercent / 100.0);

        if (hand.getType().isAir()) return "order.hold-to-offer";
        // 托管数量不超过求购单剩余（多出的留在手上）
        int submit = Math.min(hand.getAmount(), order.remaining);
        if (submit < 1) return "order.gone";
        ItemStack taken = hand.asQuantity(submit);
        int leftInHand = hand.getAmount() - submit;
        if (leftInHand > 0) hand.setAmount(leftInHand);
        else player.getInventory().setItemInMainHand(null);

        com.trademarket.model.Offer f = new com.trademarket.model.Offer(0, order.id,
                player.getUniqueId(), player.getName(), order.buyerId,
                com.trademarket.util.Items.serialize(taken), order.unitPriceCents,
                System.currentTimeMillis(), "PENDING");
        String data = f.itemData;
        if (data == null) {
            player.getInventory().setItemInMainHand(taken);
            return "common.internal-error";
        }
        try {
            plugin.database().insertOffer(f);
        } catch (Exception e) {
            player.getInventory().setItemInMainHand(taken);
            return "common.internal-error";
        }
        try {
            plugin.mail().sendOffer(order.buyerId, order.buyerName, player.getUniqueId(),
                    player.getName(), "", f.id);
        } catch (Exception e) {
            // 邮件失败：退回物品、删申请
            plugin.database().flipOfferStatus(f.id, "RETURNED");
            player.getInventory().setItemInMainHand(taken);
            return "common.internal-error";
        }
        plugin.database().tx(player.getUniqueId(), "OFFER_SUBMIT",
                "offer=" + f.id + " order=" + order.id + " amount=" + n);
        player.sendMessage(plugin.lang().msg("order.offer-submitted"));
        return null;
    }

    /** 卖家取消申请：物品退回，收取手续费（唯一扣费场景）。返回 null=成功。 */
    public String cancelOffer(Player player, long offerId) {
        com.trademarket.model.Offer f = plugin.database().getOffer(offerId);
        if (f == null || !"PENDING".equals(f.status)) return "order.offer-none";
        if (!f.sellerId.equals(player.getUniqueId())) return "order.cancel-not-owner";
        long fee = plugin.settings().offerCancelFeeCents;
        if (fee > 0) {
            if (!plugin.economy().has(player, fee)) {
                return "order.cancel-fee-cant-afford";
            }
        }
        if (!plugin.database().flipOfferStatus(offerId, "RETURNED")) return "order.offer-none";
        if (fee > 0 && !plugin.economy().withdraw(player, fee)) {
            plugin.getLogger().warning("求购申请取消手续费扣除失败 offer=" + offerId);
        }
        ItemStack item = f.item();
        if (item != null) {
            var leftover = player.getInventory().addItem(item);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        plugin.database().tx(player.getUniqueId(), "OFFER_CANCEL",
                "offer=" + offerId + " fee=" + fee);
        player.sendMessage(plugin.lang().msg("order.offer-cancelled",
                "{fee}", com.trademarket.util.Money.format(fee),
                "{symbol}", plugin.settings().currencySymbol));
        return null;
    }

    /** 周期任务：超时申请自动退回（收取配置手续费）。 */
    public void tickOffers() {
        int hours = plugin.settings().offerTimeoutHours;
        if (hours <= 0) return;
        long timeoutMillis = hours * 3_600_000L;
        for (com.trademarket.model.Offer f : plugin.database().loadTimedOutOffers(timeoutMillis)) {
            if (!plugin.database().flipOfferStatus(f.id, "RETURNED")) continue;
            ItemStack item = f.item();
            if (item != null) {
                try {
                    plugin.mail().sendItem(f.sellerId, f.sellerName, "系统",
                            plugin.lang().raw("order.offer-rejected-subject"), "", item);
                } catch (Exception e) {
                    plugin.getLogger().severe("求购申请超时退回失败 offer=" + f.id + ": " + e.getMessage());
                    plugin.database().tx(f.sellerId, "OFFER_RETURN_FAILED", "offer=" + f.id);
                    continue;
                }
            }
            Player online = Bukkit.getPlayer(f.sellerId);
            if (online != null) {
                online.sendMessage(plugin.lang().msg("order.offer-timeout-returned"));
            }
            plugin.database().tx(f.sellerId, "OFFER_TIMEOUT", "offer=" + f.id + " fee=0");
        }
    }

    /** 背包内可出售给该求购单的数量（确认界面预览用）。 */
    public int countMatchable(Player player, BuyOrder o) {
        int n = 0;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (ItemStack it : storage) {
            if (it == null || it.getType() != o.material) continue;
            String p = plugin.settings().customItemDetect
                    ? com.trademarket.item.ItemProviderRegistry.resolve(it)
                    : com.trademarket.item.ItemProviderRegistry.VANILLA;
            if (!p.equals(o.itemProvider)) continue;
            if (o.strictName) {
                var mt = it.getItemMeta();
                if (mt != null && mt.hasDisplayName()) continue;
            }
            n += it.getAmount();
        }
        return Math.min(n, o.remaining);
    }

    // ==================== 过期 ====================

    public void purge() {
        long expireBefore = System.currentTimeMillis()
                - plugin.settings().orderExpireDays * 86_400_000L;
        for (BuyOrder o : new ArrayList<>(orders.values())) {
            if (o.createdAt < expireBefore) {
                long refund = o.escrowCents();
                if (refund > 0) {
                    if (!plugin.economy().deposit(Bukkit.getOfflinePlayer(o.buyerId), refund)) {
                        plugin.getLogger().severe("求购过期退款失败 order=" + o.id + " amount=" + refund);
                        plugin.database().tx(o.buyerId, "ORDER_REFUND_FAILED",
                                "order=" + o.id + " amount=" + refund);
                        continue;
                    }
                }
                o.status = "EXPIRED";
                orders.remove(o.id);
                plugin.database().updateOrder(o);
                returnPendingOffersForOrder(o.id);
                plugin.database().tx(o.buyerId, "ORDER_EXPIRED", "order=" + o.id + " refund=" + refund);
                Player online = Bukkit.getPlayer(o.buyerId);
                if (online != null) {
                    online.sendMessage(plugin.lang().msg("order.expired-notify",
                            "{symbol}", plugin.settings().currencySymbol,
                            "{refund}", com.trademarket.util.Money.format(refund)));
                }
            }
        }
    }

    private void sound(Player p, Sound s) {
        if (plugin.settings().soundsEnabled) p.playSound(p.getLocation(), s, 1f, 1f);
    }
}

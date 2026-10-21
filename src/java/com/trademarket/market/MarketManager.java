package com.trademarket.market;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.model.Listing;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全球市场：内存权威 + SQLite 同步落库，全部主线程串行，购买为原子操作。
 * 物品交割一律走邮箱，杜绝背包满丢件。
 */
public final class MarketManager {

    private final TradeMarketPlugin plugin;
    private final Map<Long, Listing> listings = new HashMap<>();
    private long nextId = 1;

    public MarketManager(TradeMarketPlugin plugin) {
        this.plugin = plugin;
        for (Listing l : plugin.database().loadListings()) {
            listings.put(l.id, l);
            nextId = Math.max(nextId, l.id + 1);
        }
    }

    public int count() {
        return listings.size();
    }

    public int countBy(UUID seller) {
        int n = 0;
        for (Listing l : listings.values()) if (l.sellerId.equals(seller)) n++;
        return n;
    }

    public Listing get(long id) {
        return listings.get(id);
    }

    public List<Listing> all() {
        List<Listing> out = new ArrayList<>(listings.values());
        out.sort(Comparator.comparingLong(l -> -l.id));
        return out;
    }

    // ==================== 上架 ====================

    /** 是否触发定价防手滑确认：有近期均价且偏离超过倍数阈值。 */
    public boolean needsPriceConfirm(Player player, long unitPriceCents) {
        double mult = plugin.settings().priceConfirmMultiplier;
        if (mult <= 0) return false;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return false;
        long avg = plugin.history().average(hand.getType().name());
        if (avg <= 0) return false;
        return unitPriceCents > avg * mult || unitPriceCents * mult < avg;
    }

    /** 近期成交均价（无记录返回 -1）。 */
    public long recentAverage(Material material) {
        return plugin.history().average(material.name());
    }

    /** 手持物品上架。confirmed=true 表示已通过定价确认。返回 null=成功，否则为失败语言键。 */
    public String sell(Player player, long unitPriceCents, int amount, boolean confirmed) {
        if (plugin.isTradeBlocked(player)) return "common.world-disabled";
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return "common.hand-empty";
        if (plugin.settings().blacklist.contains(hand.getType())) return "market.blacklisted";
        if (unitPriceCents < plugin.settings().minPriceCents) return "market.price-too-low";
        if (unitPriceCents > plugin.settings().maxPriceCents) return "market.price-too-high";
        if (amount < 1 || amount > hand.getAmount()) amount = hand.getAmount();
        if (!plugin.economy().available()) return "common.economy-unavailable";
        if (!player.isOp() && countBy(player.getUniqueId()) >= plugin.settings().maxListingsPerPlayer) {
            return "market.list-limit";
        }
        long fee = 0;
        if (plugin.settings().marketFeePercent > 0) {
            fee = Math.round(unitPriceCents * amount * plugin.settings().marketFeePercent / 100.0);
            if (plugin.settings().marketFeeMaxCents > 0) {
                fee = Math.min(fee, plugin.settings().marketFeeMaxCents);
            }
            if (!plugin.economy().has(player, fee)) return "market.cant-afford-fee";
        }
        if (!confirmed && needsPriceConfirm(player, unitPriceCents)) return "market.price-confirm";
        String data = com.trademarket.util.Items.serialize(hand.asQuantity(amount));
        if (data == null) return "common.internal-error";
        String provider = plugin.settings().customItemDetect
                ? com.trademarket.item.ItemProviderRegistry.resolve(hand)
                : com.trademarket.item.ItemProviderRegistry.VANILLA;

        ItemStack snapshot = hand.asQuantity(amount);
        int remaining = hand.getAmount() - amount;
        if (remaining > 0) hand.setAmount(remaining);
        else player.getInventory().setItemInMainHand(null);

        if (fee > 0) plugin.economy().withdraw(player, fee);

        Listing l = new Listing(nextId++, player.getUniqueId(), player.getName(), data,
                snapshot, amount, unitPriceCents, System.currentTimeMillis(), provider);
        try {
            plugin.database().insertListing(l);
        } catch (Exception e) {
            // 入库失败：把物品还给玩家，钱不扣
            ItemStack give = com.trademarket.util.Items.deserialize(data);
            if (give != null) {
                var leftover = player.getInventory().addItem(give);
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            if (fee > 0) plugin.economy().deposit(player, fee);
            plugin.getLogger().severe("上架入库失败，已退还物品: " + e.getMessage());
            return "common.internal-error";
        }
        listings.put(l.id, l);
        plugin.database().tx(player.getUniqueId(), "MARKET_LIST",
                "id=" + l.id + " price=" + unitPriceCents + " amount=" + amount + " fee=" + fee);
        if (fee > 0) {
            player.sendMessage(plugin.lang().msg("market.list-fee-charged",
                    "{fee}", com.trademarket.util.Money.format(fee),
                    "{symbol}", plugin.settings().currencySymbol));
        }
        player.sendMessage(plugin.lang().msg("market.listed",
                "{item}", com.trademarket.util.Items.displayName(snapshot),
                "{amount}", String.valueOf(amount),
                "{symbol}", plugin.settings().currencySymbol,
                "{total}", com.trademarket.util.Money.format(unitPriceCents)));
        return null;
    }

    // ==================== 购买 ====================

    /**
     * 购买整个在售批次。两阶段：先把数据库行占用为 SOLD（行级条件保证只卖一次），
     * 再走"扣款 -> 卖家入账 -> 邮箱发货"，任一步失败全额回退。
     */
    public String buy(Player player, long listingId) {
        if (plugin.isTradeBlocked(player)) return "common.world-disabled";
        if (!plugin.economy().available()) return "common.economy-unavailable";
        Listing l = listings.get(listingId);
        if (l == null) return "market.list-gone";
        if (l.sellerId.equals(player.getUniqueId())) return "market.buy-own";
        long total = l.totalCents();
        if (!plugin.economy().has(player, total)) return "common.not-enough-money";

        // 阶段1：数据库占用（记录买家；崩溃/重复点击下保证只成交一次）
        if (!plugin.database().flipListingStatus(listingId, "SOLD", player.getUniqueId())) {
            return "market.list-gone";
        }
        listings.remove(listingId);

        var seller = org.bukkit.Bukkit.getOfflinePlayer(l.sellerId);
        long tax = Math.round(total * plugin.settings().marketSaleTaxPercent / 100.0);
        long sellerIncome = total - tax;

        // 阶段2：资金
        if (!plugin.economy().withdraw(player, total)) {
            rollbackListing(l);
            return "common.not-enough-money";
        }
        ItemStack stack = com.trademarket.util.Items.deserialize(l.itemData);
        if (stack == null) {
            plugin.economy().deposit(player, total);
            plugin.database().forceListingStatus(listingId, "BROKEN");
            plugin.getLogger().severe("商品 #" + listingId + " 物品数据损坏，交易已退款");
            return "common.internal-error";
        }
        if (!plugin.economy().deposit(seller, sellerIncome)) {
            plugin.economy().deposit(player, total);
            rollbackListing(l);
            plugin.getLogger().severe("卖家入账失败，购买已回滚: listing=" + listingId);
            return "common.internal-error";
        }
        plugin.database().markListingPaid(listingId);

        // 阶段3：发货（邮箱必达；失败则整体回退）
        try {
            plugin.mail().sendItem(player.getUniqueId(), player.getName(), l.sellerName,
                    langKey("market.buy.subject"), langKey("market.buy.body"), stack);
        } catch (Exception e) {
            plugin.economy().deposit(player, total);
            if (!plugin.economy().withdraw(seller, sellerIncome)) {
                plugin.getLogger().severe("回退卖家收入失败! listing=" + listingId
                        + " amount=" + sellerIncome + " seller=" + l.sellerId);
                plugin.database().tx(l.sellerId, "MARKET_REVERT_FAILED",
                        "listing=" + listingId + " amount=" + sellerIncome);
            }
            plugin.database().markListingUnpaid(listingId);
            rollbackListing(l);
            plugin.getLogger().severe("发货邮件失败，购买已全额退款: " + e.getMessage());
            return "common.internal-error";
        }
        plugin.database().markListingDelivered(listingId);
        plugin.history().record(l.item.getType().name(), l.unitPriceCents);
        plugin.database().insertSale(l.item.getType().name(), l.unitPriceCents, l.amount,
                System.currentTimeMillis());
        plugin.database().tx(player.getUniqueId(), "MARKET_BUY",
                "listing=" + listingId + " total=" + total + " tax=" + tax + " seller=" + l.sellerId);

        player.sendMessage(plugin.lang().msg("market.buy-success"));
        sound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        Player sellerOnline = org.bukkit.Bukkit.getPlayer(l.sellerId);
        if (sellerOnline != null) {
            sellerOnline.sendMessage(plugin.lang().msg("market.sold-notify-seller",
                    "{buyer}", player.getName(),
                    "{income}", com.trademarket.util.Money.format(sellerIncome),
                    "{tax}", com.trademarket.util.Money.format(tax)));
        } else {
            plugin.mail().sendNotice(l.sellerId, l.sellerName, "系统",
                    langKey("market.offline-sold-notice-subject"),
                    langKey("market.offline-sold-notice-body")
                            .replace("{buyer}", player.getName())
                            .replace("{symbol}", plugin.settings().currencySymbol)
                            .replace("{income}", com.trademarket.util.Money.format(sellerIncome))
                            .replace("{tax}", com.trademarket.util.Money.format(tax))
                            .replace("{total}", com.trademarket.util.Money.format(total)));
        }
        return null;
    }

    private void rollbackListing(Listing l) {
        plugin.database().revertListingStatus(l.id);
        listings.put(l.id, l);
    }

    private String langKey(String key) {
        return plugin.lang().raw(key);
    }

    // ==================== 下架 / 过期 ====================

    /** 玩家或管理员下架，物品经邮箱退回卖家。返回 null=成功。 */
    public String cancel(Player player, long listingId, boolean admin) {
        Listing l = listings.get(listingId);
        if (l == null) return "market.list-gone";
        if (!admin && !l.sellerId.equals(player.getUniqueId())) return "market.cancel-not-owner";
        if (!plugin.database().flipListingStatus(listingId, admin ? "ADMIN_CANCELLED" : "CANCELLED", null)) {
            return "market.list-gone";
        }
        listings.remove(listingId);
        returnItemToSeller(l);
        plugin.database().tx(l.sellerId, admin ? "MARKET_ADMIN_CANCEL" : "MARKET_CANCEL",
                "listing=" + listingId);
        player.sendMessage(plugin.lang().msg("market.cancel-success"));
        return null;
    }

    private void returnItemToSeller(Listing l) {
        ItemStack stack = com.trademarket.util.Items.deserialize(l.itemData);
        if (stack == null) {
            plugin.getLogger().severe("商品 #" + l.id + " 退回时物品数据损坏，可用 /market admin redeliver listing "
                    + l.id + " 重试");
            return;
        }
        try {
            plugin.mail().sendItem(l.sellerId, l.sellerName, "系统",
                    langKey("market.expired-returned-subject"), "", stack);
            plugin.database().markListingDelivered(l.id);
        } catch (Exception e) {
            plugin.getLogger().severe("商品 #" + l.id + " 退回邮件失败（启动对账会自动补发）: " + e.getMessage());
        }
    }

    /** 周期任务：过期商品自动退回卖家。 */
    public void tick() {
        long expireBefore = System.currentTimeMillis()
                - plugin.settings().listingExpireDays * 86_400_000L;
        for (Listing l : new ArrayList<>(listings.values())) {
            if (l.createdAt < expireBefore) {
                if (!plugin.database().flipListingStatus(l.id, "EXPIRED", null)) continue;
                listings.remove(l.id);
                returnItemToSeller(l);
                plugin.database().tx(l.sellerId, "MARKET_EXPIRED", "listing=" + l.id);
            }
        }
    }

    private void sound(Player p, Sound s) {
        if (plugin.settings().soundsEnabled) p.playSound(p.getLocation(), s, 1f, 1.2f);
    }
}

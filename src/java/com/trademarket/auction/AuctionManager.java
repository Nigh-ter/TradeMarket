package com.trademarket.auction;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.model.Auction;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 竞价拍卖：出价即托管（立即扣款），被超越/流拍/取消自动原路退款。
 * 全部主线程串行；结算由周期任务驱动；交割走邮箱。
 */
public final class AuctionManager {

    private final TradeMarketPlugin plugin;
    /** 进行中的拍卖（id -> auction）。 */
    private final Map<Long, Auction> active = new java.util.LinkedHashMap<>();
    /** 已发过倒计时提醒的拍卖（防重复广播）；防狙击延长时移除以允许再次提醒。 */
    /** 每个拍卖已提醒过的阈值集合（拍卖id -> 已提醒秒数）。 */
    private final Map<Long, java.util.Set<Integer>> countdownNotified = new java.util.HashMap<>();
    private long nextId = 1;

    public AuctionManager(TradeMarketPlugin plugin) {
        this.plugin = plugin;
        for (Auction a : plugin.database().loadActiveAuctions()) {
            active.put(a.id, a);
            nextId = Math.max(nextId, a.id + 1);
        }
    }

    public int count() {
        return active.size();
    }

    public int countBy(UUID seller) {
        int n = 0;
        for (Auction a : active.values()) if (a.sellerId.equals(seller)) n++;
        return n;
    }

    public Auction get(long id) {
        return active.get(id);
    }

    /** 按结束时间升序（快结束的排前面）。 */
    public List<Auction> all() {
        List<Auction> out = new ArrayList<>(active.values());
        out.sort((x, y) -> Long.compare(x.endAt, y.endAt));
        return out;
    }

    // ==================== 发起 ====================

    /** 手持物品发起拍卖。返回 null=成功。 */
    public String create(Player player, long startCents, long buyoutCents, double durationMinutes) {
        if (plugin.isTradeBlocked(player)) return "common.world-disabled";
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return "common.hand-empty";
        if (plugin.settings().blacklist.contains(hand.getType())) return "auction.blacklisted";
        if (startCents < plugin.settings().auctionMinStartCents) return "auction.start-price-too-low";
        if (startCents > plugin.settings().auctionMaxPriceCents
                || (buyoutCents > 0 && buyoutCents > plugin.settings().auctionMaxPriceCents)) {
            return "auction.price-too-high";
        }
        if (buyoutCents > 0 && buyoutCents < startCents) return "auction.start-price-too-low";
        if (durationMinutes <= 0) durationMinutes = plugin.settings().defaultDurationMin;
        if (durationMinutes > plugin.settings().maxDurationMin) return "auction.duration-limit";
        if (!plugin.economy().available()) return "common.economy-unavailable";
        if (!player.isOp() && countBy(player.getUniqueId()) >= plugin.settings().maxAuctionsPerPlayer) {
            return "auction.list-limit";
        }
        long fee = Math.round(startCents * plugin.settings().auctionFeePercent / 100.0);
        if (fee > 0 && !plugin.economy().has(player, fee)) return "auction.cant-afford-fee";

        String data = com.trademarket.util.Items.serialize(hand.asQuantity(hand.getAmount()));
        if (data == null) return "common.internal-error";
        ItemStack snapshot = hand.asQuantity(hand.getAmount());
        player.getInventory().setItemInMainHand(null);
        if (fee > 0) plugin.economy().withdraw(player, fee);

        long now = System.currentTimeMillis();
        String provider = plugin.settings().customItemDetect
                ? com.trademarket.item.ItemProviderRegistry.resolve(snapshot)
                : com.trademarket.item.ItemProviderRegistry.VANILLA;
        Auction a = new Auction(nextId++, player.getUniqueId(), player.getName(), data, snapshot,
                startCents, Math.max(0, buyoutCents), now, now + (long) (durationMinutes * 60_000L), provider);
        try {
            plugin.database().insertAuction(a);
        } catch (Exception e) {
            ItemStack give = com.trademarket.util.Items.deserialize(data);
            if (give != null) {
                var leftover = player.getInventory().addItem(give);
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            if (fee > 0) plugin.economy().deposit(player, fee);
            plugin.getLogger().severe("拍卖入库失败，已退还物品: " + e.getMessage());
            return "common.internal-error";
        }
        active.put(a.id, a);
        plugin.database().tx(player.getUniqueId(), "AUCTION_CREATE",
                "id=" + a.id + " start=" + startCents + " buyout=" + a.buyoutCents
                        + " minutes=" + durationMinutes + " fee=" + fee);
        player.sendMessage(plugin.lang().msg("auction.created",
                "{item}", com.trademarket.util.Items.displayName(snapshot),
                "{symbol}", plugin.settings().currencySymbol,
                "{start}", com.trademarket.util.Money.format(startCents)));
        if (plugin.settings().broadcastAuction) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(plugin.lang().msg("auction.created-broadcast",
                        "{player}", player.getName(),
                        "{item}", com.trademarket.util.Items.displayName(snapshot),
                        "{symbol}", plugin.settings().currencySymbol,
                        "{start}", com.trademarket.util.Money.format(startCents))
                        .append(plugin.lang().action("gui.click.open-auction", "/tk auction")));
            }
        }
        return null;
    }

    // ==================== 出价 ====================

    /** 出价（全额托管）。返回 null=成功。金额达到一口价时提示改用一口价。 */
    public String bid(Player player, long auctionId, long amountCents) {
        if (plugin.isTradeBlocked(player)) return "common.world-disabled";
        Auction a = active.get(auctionId);
        if (a == null || !a.isActive()) return "auction.gone";
        if (a.sellerId.equals(player.getUniqueId())) return "auction.bid-own";
        if (a.buyoutCents > 0 && amountCents >= a.buyoutCents) {
            // 一口价是出价上限：达到/超过即按一口价直接拿下
            if (!plugin.economy().has(player, a.buyoutCents)) return "auction.bid-cant-afford";
            return buyout(player, auctionId);
        }
        long minBid = a.currentBidCents == 0
                ? a.startPriceCents
                : a.currentBidCents + plugin.settings().auctionMinIncrementCents;
        if (amountCents < minBid) {
            return "auction.bid-too-low";
        }
        if (!plugin.economy().has(player, amountCents)) return "auction.bid-cant-afford";

        UUID oldBidder = a.topBidderId;
        String oldName = a.topBidderName;
        long oldBid = a.currentBidCents;
        long oldEndAt = a.endAt;

        if (!plugin.economy().withdraw(player, amountCents)) return "auction.bid-cant-afford";
        if (oldBidder != null && oldBid > 0) {
            var old = Bukkit.getOfflinePlayer(oldBidder);
            if (!plugin.economy().deposit(old, oldBid)) {
                // 退款失败：保留托管记录并告警（管理员可通过流水补偿）
                plugin.getLogger().severe("前任出价者退款失败 auction=" + a.id
                        + " player=" + oldBidder + " amount=" + oldBid);
                plugin.database().tx(oldBidder, "AUCTION_REFUND_FAILED",
                        "auction=" + a.id + " amount=" + oldBid);
            }
        }

        countdownNotified.remove(a.id);
        a.currentBidCents = amountCents;
        a.topBidderId = player.getUniqueId();
        a.topBidderName = player.getName();
        long now = System.currentTimeMillis();
        boolean sniped = false;
        int anti = plugin.settings().antiSnipeSeconds;
        if (anti > 0 && a.endAt - now < anti * 1000L) {
            a.endAt = now + plugin.settings().antiSnipeExtendSeconds * 1000L;
            sniped = true;
        }
        if (!plugin.database().updateAuction(a)) {
            // 写库失败：全额回退内存与资金
            a.currentBidCents = oldBid;
            a.topBidderId = oldBidder;
            a.topBidderName = oldName;
            a.endAt = oldEndAt;
            plugin.economy().deposit(player, amountCents);
            if (oldBidder != null && oldBid > 0) {
                var old = Bukkit.getOfflinePlayer(oldBidder);
                if (!plugin.economy().withdraw(old, oldBid)) {
                    plugin.getLogger().severe("出价回退重新托管失败! auction=" + a.id
                            + " player=" + oldBidder + " amount=" + oldBid);
                    plugin.database().tx(oldBidder, "AUCTION_REESCROW_FAILED",
                            "auction=" + a.id + " amount=" + oldBid);
                }
            }
            return "common.internal-error";
        }
        plugin.database().insertBid(a.id, player.getName(), amountCents, System.currentTimeMillis());
        plugin.database().tx(player.getUniqueId(), "AUCTION_BID",
                "auction=" + a.id + " bid=" + amountCents);

        player.sendMessage(plugin.lang().msg("auction.bid-success",
                "{bid}", com.trademarket.util.Money.format(amountCents),
                "{symbol}", plugin.settings().currencySymbol));
        if (sniped) {
            player.sendMessage(plugin.lang().msg("auction.snipe-extended"));
            Player oldOnline = oldBidder == null ? null : Bukkit.getPlayer(oldBidder);
            if (oldOnline != null && !oldBidder.equals(player.getUniqueId())) {
                oldOnline.sendMessage(plugin.lang().msg("auction.snipe-extended"));
            }
        }
        sound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
        if (oldBidder != null && !oldBidder.equals(player.getUniqueId())) {
            Player oldOnline = Bukkit.getPlayer(oldBidder);
            if (oldOnline != null) {
                oldOnline.sendMessage(plugin.lang().msg("auction.outbid-notify",
                        "{bidder}", player.getName(),
                        "{bid}", com.trademarket.util.Money.format(amountCents))
                        .append(plugin.lang().action("gui.click.open-auction", "/tk auction")));
            }
        }
        return null;
    }

    // ==================== 一口价 ====================

    /** 一口价直接成交。两阶段：先占用为 SOLD，再走资金与发货，失败全额回退。 */
    public String buyout(Player player, long auctionId) {
        if (plugin.isTradeBlocked(player)) return "common.world-disabled";
        Auction a = active.get(auctionId);
        if (a == null || !a.isActive()) return "auction.gone";
        if (a.buyoutCents <= 0) return "auction.gone";
        if (a.sellerId.equals(player.getUniqueId())) return "auction.bid-own";
        if (!plugin.economy().available()) return "common.economy-unavailable";
        if (!plugin.economy().has(player, a.buyoutCents)) return "auction.bid-cant-afford";

        if (!plugin.database().flipAuctionStatus(auctionId, "SOLD")) return "auction.gone";
        active.remove(auctionId);

        UUID oldBidder = a.topBidderId;
        long oldBid = a.currentBidCents;

        if (!plugin.economy().withdraw(player, a.buyoutCents)) {
            revertAuction(a);
            return "auction.bid-cant-afford";
        }
        if (oldBidder != null && oldBid > 0) {
            var old = Bukkit.getOfflinePlayer(oldBidder);
            if (!plugin.economy().deposit(old, oldBid)) {
                plugin.getLogger().severe("一口价退还托管失败 auction=" + a.id
                        + " player=" + oldBidder + " amount=" + oldBid);
                plugin.database().tx(oldBidder, "AUCTION_REFUND_FAILED",
                        "auction=" + a.id + " amount=" + oldBid);
            }
        }

        var seller = Bukkit.getOfflinePlayer(a.sellerId);
        long tax = Math.round(a.buyoutCents * plugin.settings().auctionSaleTaxPercent / 100.0);
        if (!plugin.economy().deposit(seller, a.buyoutCents - tax)) {
            plugin.economy().deposit(player, a.buyoutCents);
            revertAuction(a);
            plugin.getLogger().severe("一口价卖家入账失败，已回滚 auction=" + a.id);
            return "common.internal-error";
        }
        plugin.database().markAuctionPaid(a.id);
        ItemStack stack = com.trademarket.util.Items.deserialize(a.itemData);
        if (stack == null) {
            plugin.getLogger().severe("拍卖 #" + a.id + " 中标物品数据损坏！买家已付款、卖家已入账，"
                    + "请通过流水补偿买家");
            plugin.database().tx(player.getUniqueId(), "AUCTION_ITEM_LOST",
                    "auction=" + a.id);
            player.sendMessage(plugin.lang().msg("common.internal-error"));
            return null;
        }
        try {
            plugin.mail().sendItem(player.getUniqueId(), player.getName(), a.sellerName,
                    plugin.lang().raw("auction.won-subject"), "", stack);
            plugin.database().markAuctionDelivered(a.id);
        } catch (Exception e) {
            plugin.getLogger().severe("拍卖 #" + a.id + " 一口价发货失败（启动对账会自动补发）: " + e.getMessage());
            plugin.database().tx(player.getUniqueId(), "AUCTION_DELIVER_FAILED", "auction=" + a.id);
            player.sendMessage(plugin.lang().msg("common.internal-error"));
            return null;
        }
        Player sellerOnline = Bukkit.getPlayer(a.sellerId);
        if (sellerOnline != null) {
            sellerOnline.sendMessage(plugin.lang().msg("auction.sold-notify-seller",
                    "{buyer}", player.getName(),
                    "{income}", com.trademarket.util.Money.format(a.buyoutCents - tax),
                    "{tax}", com.trademarket.util.Money.format(tax)));
        }
        plugin.database().tx(player.getUniqueId(), "AUCTION_BUYOUT",
                "auction=" + a.id + " price=" + a.buyoutCents + " tax=" + tax + " seller=" + a.sellerId);

        player.sendMessage(plugin.lang().msg("auction.buyout-success"));
        sound(player, Sound.ENTITY_PLAYER_LEVELUP);
        broadcastWin(a, player.getName(), a.buyoutCents);
        return null;
    }

    /** 成交失败回退：恢复 ACTIVE 状态并放回内存。 */
    private void revertAuction(Auction a) {
        plugin.database().revertAuctionStatus(a.id);
        active.put(a.id, a);
    }

    // ==================== 取消 / 结算 ====================

    /** 取消拍卖（仅无出价时）。返回 null=成功。 */
    public String cancel(Player player, long auctionId, boolean admin) {
        Auction a = active.get(auctionId);
        if (a == null || !a.isActive()) return "auction.gone";
        if (!admin && !a.sellerId.equals(player.getUniqueId())) return "auction.cancel-not-owner";
        if (a.topBidderId != null && a.currentBidCents > 0) return "auction.cancel-has-bids";
        if (!plugin.database().flipAuctionStatus(auctionId, "CANCELLED")) return "auction.gone";
        active.remove(auctionId);
        returnToSeller(a);
        plugin.database().tx(a.sellerId, admin ? "AUCTION_ADMIN_CANCEL" : "AUCTION_CANCEL",
                "auction=" + a.id);
        player.sendMessage(plugin.lang().msg("auction.cancel-success"));
        return null;
    }

    /** 周期任务：结算到期拍卖（成交 / 流拍）。先占用状态，再动钱发货。 */
    public void tick() {
        long now = System.currentTimeMillis();
        tickCountdown(now);
        for (Auction a : new ArrayList<>(active.values())) {
            if (!a.isActive() || a.endAt > now) continue;
            if (a.topBidderId != null && a.currentBidCents > 0) {
                if (!plugin.database().flipAuctionStatus(a.id, "SOLD")) continue;
                active.remove(a.id);
                var seller = Bukkit.getOfflinePlayer(a.sellerId);
                long tax = Math.round(a.currentBidCents * plugin.settings().auctionSaleTaxPercent / 100.0);
                if (!plugin.economy().deposit(seller, a.currentBidCents - tax)) {
                    plugin.getLogger().severe("结算卖家入账失败，请管理员处理 auction=" + a.id
                            + " amount=" + (a.currentBidCents - tax));
                    plugin.database().tx(a.sellerId, "AUCTION_SETTLE_FAILED",
                            "auction=" + a.id + " amount=" + (a.currentBidCents - tax));
                } else {
                    plugin.database().markAuctionPaid(a.id);
                }
                UUID winner = a.topBidderId;
                String winnerName = a.topBidderName;
                long price = a.currentBidCents;
                deliverToWinner(a, winnerName, winner);
                plugin.database().tx(winner, "AUCTION_SETTLE",
                        "auction=" + a.id + " price=" + price + " tax=" + tax + " seller=" + a.sellerId);
                Player sellerOnline = Bukkit.getPlayer(a.sellerId);
                if (sellerOnline != null) {
                    sellerOnline.sendMessage(plugin.lang().msg("auction.sold-notify-seller",
                            "{buyer}", winnerName,
                            "{income}", com.trademarket.util.Money.format(price - tax),
                            "{tax}", com.trademarket.util.Money.format(tax)));
                }
                broadcastWin(a, winnerName, price);
            } else {
                if (!plugin.database().flipAuctionStatus(a.id, "EXPIRED")) continue;
                active.remove(a.id);
                returnToSeller(a);
                plugin.database().tx(a.sellerId, "AUCTION_EXPIRED", "auction=" + a.id);
                Player sellerOnline = Bukkit.getPlayer(a.sellerId);
                if (sellerOnline != null) {
                    sellerOnline.sendMessage(plugin.lang().msg("auction.expired-notify"));
                }
            }
        }
    }

    /** 结束前倒计时提醒（秒数可配，0 = 关闭）；防狙击延长后允许再次提醒。 */
    private void tickCountdown(long now) {
        List<Integer> thresholds = plugin.settings().countdownSecondsList;
        if (thresholds.isEmpty()) return;
        for (Auction a : active.values()) {
            if (!a.isActive()) continue;
            long remain = a.endAt - now;
            if (remain <= 0) continue;
            java.util.Set<Integer> done = countdownNotified.computeIfAbsent(a.id, k -> new java.util.HashSet<>());
            for (int secs : thresholds) {
                if (done.contains(secs) || remain > secs * 1000L) continue;
                done.add(secs);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(plugin.lang().msg("auction.countdown-broadcast",
                            "{item}", com.trademarket.util.Items.displayName(a.item),
                            "{time}", com.trademarket.util.Items.formatDuration(remain),
                            "{id}", String.valueOf(a.id))
                            .append(plugin.lang().action("gui.click.open-auction", "/tk auction")));
                }
                break; // 每秒只播报匹配到的最高一个阈值
            }
        }
    }

    private void deliverToWinner(Auction a, String winnerName, UUID winnerId) {
        ItemStack stack = com.trademarket.util.Items.deserialize(a.itemData);
        if (stack == null) {
            plugin.getLogger().severe("拍卖 #" + a.id + " 中标物品数据损坏！中标者=" + winnerName
                    + " 已付款，请通过流水补偿");
            plugin.database().tx(winnerId, "AUCTION_ITEM_LOST", "auction=" + a.id);
            return;
        }
        try {
            plugin.mail().sendItem(winnerId, winnerName, a.sellerName,
                    plugin.lang().raw("auction.won-subject"), "", stack);
            plugin.database().markAuctionDelivered(a.id);
        } catch (Exception e) {
            plugin.getLogger().severe("拍卖 #" + a.id + " 中标发货失败（启动对账会自动补发）: " + e.getMessage());
            plugin.database().tx(winnerId, "AUCTION_DELIVER_FAILED", "auction=" + a.id);
        }
    }

    private void returnToSeller(Auction a) {
        ItemStack stack = com.trademarket.util.Items.deserialize(a.itemData);
        if (stack == null) {
            plugin.getLogger().severe("拍卖 #" + a.id + " 退回物品数据损坏，可用 /market admin redeliver auction "
                    + a.id + " 重试");
            return;
        }
        try {
            plugin.mail().sendItem(a.sellerId, a.sellerName, "系统",
                    plugin.lang().raw("auction.return-subject"), "", stack);
            plugin.database().markAuctionDelivered(a.id);
        } catch (Exception e) {
            plugin.getLogger().severe("拍卖 #" + a.id + " 退回失败（启动对账会自动补发）: " + e.getMessage());
        }
    }

    private void broadcastWin(Auction a, String winnerName, long price) {
        if (!plugin.settings().broadcastAuction) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(plugin.lang().msg("auction.won-broadcast",
                    "{player}", winnerName,
                    "{item}", com.trademarket.util.Items.displayName(a.item),
                    "{symbol}", plugin.settings().currencySymbol,
                    "{price}", com.trademarket.util.Money.format(price))
                    .append(plugin.lang().action("gui.click.open-auction", "/tk auction")));
        }
    }

    private void sound(Player p, Sound s) {
        if (plugin.settings().soundsEnabled) p.playSound(p.getLocation(), s, 1f, 1f);
    }
}

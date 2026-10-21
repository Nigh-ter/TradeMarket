package com.trademarket.mail;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.model.Mail;
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
 * 智能邮箱：所有市场/拍卖的物品交割唯一通道 —— 物品永远先进邮箱，背包满也不丢。
 * 状态只有 未领取/已领取 两态；主线程串行操作，无竞态。
 */
public final class MailManager {

    private final TradeMarketPlugin plugin;
    /** 只缓存未领取邮件，按收件人分组。 */
    private final Map<UUID, List<Mail>> unclaimed = new HashMap<>();
    /** 发件批次（撤销用）：管理员 -> 最近几次发送的邮件 ID。 */
    private final Map<UUID, java.util.ArrayDeque<MailBatch>> batches = new HashMap<>();
    private long nextId = 1;

    public record MailBatch(java.util.List<Long> mailIds, long createdAt) {
    }

    /** 记录一次发送批次（30 分钟内可 /tk admin mailundo 撤销未领取部分）。 */
    public void markBatch(UUID actor, List<Long> mailIds) {
        if (mailIds == null || mailIds.isEmpty()) return;
        var dq = batches.computeIfAbsent(actor, k -> new java.util.ArrayDeque<>());
        dq.addLast(new MailBatch(List.copyOf(mailIds), System.currentTimeMillis()));
        while (dq.size() > 3) dq.removeFirst();
    }

    /** 撤销最近一次发送批次中仍未领取的邮件，返回撤销数；无批次返回 -1。 */
    public int recallLastBatch(UUID actor) {
        var dq = batches.get(actor);
        if (dq == null || dq.isEmpty()) return -1;
        MailBatch batch = dq.removeLast();
        if (System.currentTimeMillis() - batch.createdAt() > 30 * 60_000L) return -1;
        int n = 0;
        java.util.Set<UUID> refreshed = new java.util.HashSet<>();
        for (Long id : batch.mailIds()) {
            Mail found = null;
            for (List<Mail> list : unclaimed.values()) {
                for (Mail m : list) {
                    if (m.id == id) { found = m; break; }
                }
                if (found != null) break;
            }
            if (found != null && !found.claimed && plugin.database().deleteMailIfUnclaimed(id)) {
                List<Mail> list = unclaimed.get(found.ownerId);
                if (list != null) {
                    list.remove(found);
                    if (list.isEmpty()) unclaimed.remove(found.ownerId);
                }
                n++;
                // 收件人正开着邮箱界面 → 立即刷新，防止点旧槽位
                if (refreshed.add(found.ownerId)) {
                    Player op = Bukkit.getPlayer(found.ownerId);
                    if (op != null && op.getOpenInventory().getTopInventory().getHolder()
                            instanceof com.trademarket.mail.MailboxGui) {
                        new com.trademarket.mail.MailboxGui(plugin, op).open();
                    }
                }
            }
        }
        return n;
    }

    public MailManager(TradeMarketPlugin plugin) {
        this.plugin = plugin;
        for (Mail m : plugin.database().loadUnclaimedMails()) {
            unclaimed.computeIfAbsent(m.ownerId, k -> new ArrayList<>()).add(m);
            nextId = Math.max(nextId, m.id + 1);
        }
    }

    public int unclaimedCount() {
        int n = 0;
        for (List<Mail> list : unclaimed.values()) n += list.size();
        return n;
    }

    public List<Mail> mailboxOf(UUID player) {
        return unclaimed.getOrDefault(player, List.of());
    }

    public int unread(UUID player) {
        return mailboxOf(player).size();
    }

    /** 投递物品邮件；序列化失败会抛出，由调用方决定回滚 —— 物品绝不允许无声消失。 */
    public Mail sendItem(UUID to, String toName, String sender, String subject, String body, ItemStack item) {
        String data = com.trademarket.util.Items.serialize(item);
        if (data == null) {
            throw new IllegalStateException("物品序列化失败，邮件未投递: " + subject);
        }
        return sendItemData(to, toName, sender, subject, body, data);
    }

    /** 已有序列化数据的投递（群发补偿用，避免同一物品重复序列化 N 次）。 */
    public Mail sendItemData(UUID to, String toName, String sender, String subject, String body, String itemData) {
        Mail m = new Mail(nextId++, to, toName, sender, subject, body, itemData, 0,
                null, System.currentTimeMillis(), false);
        try {
            plugin.database().insertMail(m);
        } catch (Exception e) {
            nextId--;
            throw new IllegalStateException("邮件入库失败: " + subject, e);
        }
        unclaimed.computeIfAbsent(to, k -> new ArrayList<>()).add(m);
        Player online = Bukkit.getPlayer(to);
        if (online != null) {
            online.sendMessage(plugin.lang().msg("mailbox.notify-join", "{count}", String.valueOf(unread(to))).append(plugin.lang().action("gui.click.open-mailbox", "/tk mailbox")));
            if (plugin.settings().soundsEnabled) {
                online.playSound(online.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.4f);
            }
        }
        return m;
    }

    /** 投递金币邮件。经济不可用时抛出，调用方回滚。 */
    public Mail sendMoney(UUID to, String toName, String sender, String subject, String body, long cents) {
        Mail m = new Mail(nextId++, to, toName, sender, subject, body, null, cents,
                null, System.currentTimeMillis(), false);
        try {
            plugin.database().insertMail(m);
        } catch (Exception e) {
            nextId--;
            throw new IllegalStateException("邮件入库失败: " + subject, e);
        }
        unclaimed.computeIfAbsent(to, k -> new ArrayList<>()).add(m);
        Player online = Bukkit.getPlayer(to);
        if (online != null) {
            online.sendMessage(plugin.lang().msg("mailbox.notify-join", "{count}", String.valueOf(unread(to))).append(plugin.lang().action("gui.click.open-mailbox", "/tk mailbox")));
        }
        return m;
    }

    /** 命令邮件：领取时以控制台身份执行，%player% 替换为领取者（联动称号/抽奖箱等插件的通用通道）。 */
    public Mail sendCommand(UUID to, String toName, String sender, String subject, String body, String command) {
        String cmd = command.trim();
        if (cmd.length() > 256) {
            throw new IllegalArgumentException("命令过长（>256 字符）");
        }
        Mail m = new Mail(nextId++, to, toName, sender, subject, body, null, 0,
                cmd, System.currentTimeMillis(), false);
        try {
            plugin.database().insertMail(m);
        } catch (Exception e) {
            nextId--;
            throw new IllegalStateException("邮件入库失败: " + subject, e);
        }
        unclaimed.computeIfAbsent(to, k -> new ArrayList<>()).add(m);
        Player online = Bukkit.getPlayer(to);
        if (online != null) {
            online.sendMessage(plugin.lang().msg("mailbox.notify-join", "{count}", String.valueOf(unread(to))).append(plugin.lang().action("gui.click.open-mailbox", "/tk mailbox")));
        }
        return m;
    }

    /** 求购申请通知邮件：refId = 申请记录编号（唯一），物品唯一存于托管记录，邮件仅作提醒。 */
    public Mail sendOffer(UUID to, String toName, UUID sellerId, String sellerName, String body, long offerId) {
        String subject = plugin.lang().raw("mailbox.offer-subject");
        Mail m = new Mail(nextId++, to, toName, sellerName, subject, body, null, 0,
                null, System.currentTimeMillis(), false);
        m.kind = "OFFER";
        m.refId = offerId;
        m.senderId = sellerId;
        try {
            plugin.database().insertMail(m);
        } catch (Exception e) {
            nextId--;
            throw new IllegalStateException("求购申请入库失败", e);
        }
        unclaimed.computeIfAbsent(to, k -> new ArrayList<>()).add(m);
        Player online = Bukkit.getPlayer(to);
        if (online != null) {
            online.sendMessage(plugin.lang().msg("mailbox.offer-notify",
                    "{seller}", sellerName)
                    .append(plugin.lang().action("gui.click.open-mailbox", "/tk mailbox")));
        }
        return m;
    }

    /** 按邮件精确取托管申请记录（refId = 记录编号）；已处理或不存在返回 null。 */
    public com.trademarket.model.Offer pendingOfferFor(Mail mail) {
        com.trademarket.model.Offer rec = plugin.database().getOffer(mail.refId);
        return (rec != null && "PENDING".equals(rec.status)) ? rec : null;
    }

    private void markOfferMailClaimed(Mail offer) {
        if (plugin.database().markMailClaimedGuarded(offer.id)) {
            offer.claimed = true;
            List<Mail> list = unclaimed.get(offer.ownerId);
            if (list != null) {
                list.remove(offer);
                if (list.isEmpty()) unclaimed.remove(offer.ownerId);
            }
        }
    }

    /** 纯通知邮件（无附件）。 */
    public Mail sendNotice(UUID to, String toName, String sender, String subject, String body) {
        Mail m = new Mail(nextId++, to, toName, sender, subject, body, null, 0,
                null, System.currentTimeMillis(), false);
        try {
            plugin.database().insertMail(m);
        } catch (Exception e) {
            plugin.getLogger().warning("通知邮件入库失败（忽略）: " + subject);
            return m;
        }
        unclaimed.computeIfAbsent(to, k -> new ArrayList<>()).add(m);
        return m;
    }

    /**
     * 领取一封邮件。返回 null=成功，否则为失败原因的语言键。
     * 先在数据库占位（行级条件防重复领取），发放失败则释放占位 —— 物品绝不吞件。
     */
    public String claim(Player player, Mail mail) {
        if (mail.claimed) return "mailbox.gone";
        if (mail.icon() == null && mail.itemData != null) {
            return "common.internal-error"; // 物品损坏：保留邮件并提示，不自动删除
        }
        if (mail.itemData != null && player.getInventory().firstEmpty() == -1) {
            return "common.inventory-full";
        }
        if (mail.moneyCents > 0 && !plugin.economy().available()) {
            return "common.economy-unavailable";
        }
        if (!plugin.database().markMailClaimedGuarded(mail.id)) {
            return "mailbox.gone";
        }
        if (mail.itemData != null) {
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(mail.icon());
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        if (mail.moneyCents > 0 && !plugin.economy().deposit(player, mail.moneyCents)) {
            plugin.database().markMailUnclaimed(mail.id);
            return "common.economy-unavailable";
        }
        if (mail.command != null && !mail.command.isEmpty()) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        mail.command.replace("%player%", player.getName()));
            } catch (Throwable t) {
                // 命令执行失败不影响已领取状态（对方插件问题），仅记录
                plugin.getLogger().warning("命令邮件执行失败 mail=" + mail.id + " cmd=" + mail.command
                        + " : " + t.getMessage());
            }
        }
        mail.claimed = true;
        List<Mail> list = unclaimed.get(mail.ownerId);
        if (list != null) {
            list.remove(mail);
            if (list.isEmpty()) unclaimed.remove(mail.ownerId);
        }
        plugin.database().tx(player.getUniqueId(), "MAIL_CLAIM",
                "mail=" + mail.id + " money=" + mail.moneyCents);
        return null;
    }

    /** 一键领取；返回 [成功数, 失败数]。 */
    public int[] claimAll(Player player) {
        List<Mail> list = new ArrayList<>(mailboxOf(player.getUniqueId()));
        int ok = 0, fail = 0;
        for (Mail m : list) {
            if ("OFFER".equals(m.kind)) continue; // 求购申请需买家单独达成/拒绝
            if (claim(player, m) == null) ok++;
            else fail++;
        }
        return new int[]{ok, fail};
    }

    public void notifyJoin(Player player) {
        int n = unread(player.getUniqueId());
        if (n > 0 && plugin.settings().mailNotifyOnJoin) {
            player.sendMessage(plugin.lang().msg("mailbox.notify-join", "{count}", String.valueOf(n))
                    .append(plugin.lang().action("gui.click.open-mailbox", "/tk mailbox")));
        }
    }

    public String acceptOffer(Player buyer, Mail offer) {
        if (offer.claimed) return "mailbox.gone";
        com.trademarket.model.Offer rec = pendingOfferFor(offer);
        com.trademarket.model.BuyOrder o = rec == null ? null : plugin.order().get(rec.orderId);
        // 死邮件：记录已处理 / 求购单已关闭 —— 标记后不可再操作
        if (rec == null || o == null || !o.isOpen() || rec.item() == null) {
            markOfferMailClaimed(offer);
            return "order.offer-order-gone";
        }
        if (!plugin.economy().available()) return "common.economy-unavailable";
        ItemStack full = rec.item();
        int n = Math.min(full.getAmount(), o.remaining);
        if (n < 1) { markOfferMailClaimed(offer); return "order.offer-order-gone"; }
        long gross = o.unitPriceCents * n;
        long tax = Math.round(gross * plugin.settings().orderSaleTaxPercent / 100.0);
        long payout = gross - tax;
        java.util.UUID sellerId = rec.sellerId;
        String sellerName2 = rec.sellerName;

        // 先付卖家（托管金），失败则整体中止、物品未动
        var seller = Bukkit.getOfflinePlayer(sellerId);
        if (!plugin.economy().deposit(seller, payout)) {
            plugin.getLogger().severe("求购达成付款失败 order=" + rec.orderId
                    + " seller=" + sellerName2 + " amount=" + payout);
            plugin.database().tx(buyer.getUniqueId(), "ORDER_PAY_FAILED",
                    "order=" + rec.orderId + " amount=" + payout);
            buyer.sendMessage(plugin.lang().msg("common.internal-error"));
            return "common.internal-error";
        }

        // 超出求购剩余数量的部分退回卖家（只按 n 件成交，防复制）
        if (full.getAmount() > n) {
            ItemStack excess = full.asQuantity(full.getAmount() - n);
            try {
                sendItem(sellerId, sellerName2, "系统",
                        plugin.lang().raw("order.offer-rejected-subject"),
                        plugin.lang().raw("order.offer-partial-note"), excess);
            } catch (Exception e) {
                plugin.getLogger().severe("求购申请超额部分退回失败 offer=" + rec.id);
            }
        }

        // 物品交给买家（放不下落到脚下）
        ItemStack buyerItem = full.asQuantity(n);
        var leftover = buyer.getInventory().addItem(buyerItem);
        for (ItemStack rest : leftover.values()) {
            buyer.getWorld().dropItemNaturally(buyer.getLocation(), rest);
        }

        // 托管记录翻转 + 邮件标记 + 订单核减
        plugin.database().flipOfferStatus(rec.id, "ACCEPTED");
        markOfferMailClaimed(offer);
        o.remaining -= n;
        if (o.remaining <= 0) {
            o.remaining = 0;
            o.status = "CLOSED";
            plugin.order().removeClosed(o);
            plugin.order().returnPendingOffersForOrder(o.id);
        }
        plugin.database().updateOrder(o);
        if (o.material != null) {
            plugin.history().record(o.material.name(), o.unitPriceCents);
            plugin.database().insertSale(o.material.name(), o.unitPriceCents, n, System.currentTimeMillis());
        }
        plugin.database().tx(buyer.getUniqueId(), "ORDER_ACCEPT",
                "offer=" + rec.id + " order=" + rec.orderId + " amount=" + n + " gross=" + gross
                        + " tax=" + tax + " payout=" + payout + " seller=" + sellerName2);
        buyer.sendMessage(plugin.lang().msg("order.offer-accepted-buyer",
                "{amount}", String.valueOf(n),
                "{item}", com.trademarket.util.Items.displayName(buyerItem),
                "{unit}", com.trademarket.util.Money.format(o.unitPriceCents),
                "{total}", com.trademarket.util.Money.format(gross),
                "{symbol}", plugin.settings().currencySymbol));
        Player sellerOnline = Bukkit.getPlayer(sellerId);
        if (sellerOnline != null) {
            sellerOnline.sendMessage(plugin.lang().msg("order.offer-accepted-notify",
                    "{buyer}", buyer.getName(),
                    "{amount}", String.valueOf(n),
                    "{income}", com.trademarket.util.Money.format(payout),
                    "{tax}", com.trademarket.util.Money.format(tax)));
        }
        return null;
    }

    public void rejectOffer(Player buyer, Mail offer) {
        if (offer.claimed) return;
        com.trademarket.model.Offer rec = pendingOfferFor(offer);
        if (rec != null && rec.item() != null && plugin.database().flipOfferStatus(rec.id, "REJECTED")) {
            try {
                sendItem(rec.sellerId, rec.sellerName, "系统",
                        plugin.lang().raw("order.offer-rejected-subject"), "", rec.item());
            } catch (Exception e) {
                plugin.getLogger().severe("求购拒绝退回失败 offer=" + rec.id + ": " + e.getMessage());
            }
        }
        markOfferMailClaimed(offer);
        plugin.database().tx(offer.ownerId, "ORDER_OFFER_REJECTED", "mail=" + offer.id);
    }

    public void purge() {
        long cutoff = System.currentTimeMillis()
                - plugin.settings().mailPurgeClaimedDays * 86_400_000L;
        plugin.database().purgeClaimedOlderThan(cutoff);
    }
}

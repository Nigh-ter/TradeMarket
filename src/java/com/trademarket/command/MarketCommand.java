package com.trademarket.command;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.auction.AuctionGui;
import com.trademarket.mail.MailboxGui;
import com.trademarket.market.ConfirmListGui;
import com.trademarket.market.MarketGui;
import com.trademarket.market.OrdersGui;
import com.trademarket.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /market（/tk）主命令分发 + Tab 补全。 */
public final class MarketCommand implements TabExecutor {

    private final TradeMarketPlugin plugin;

    /** 群发确认：管理员 -> 待执行任务（GUI 确认，5 分钟内有效）。 */
    private final Map<UUID, PendingMass> pendingMass = new java.util.HashMap<>();

    private record PendingMass(String type, String spec, String note,
                               List<org.bukkit.OfflinePlayer> targets, long expireAt) {
    }

    public MarketCommand(TradeMarketPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.lang().msg("common.player-only"));
                return true;
            }
            if (!player.hasPermission("trademarket.use")) {
                player.sendMessage(plugin.lang().msg("common.no-permission"));
                return true;
            }
            new MarketGui(plugin, player).open();
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        // 世界限制：交易类子命令在禁用世界里拦截（help/reload/admin 不受限）
        if (java.util.List.of("market", "open", "sell", "order", "orders", "offers",
                "auction", "ah", "mailbox", "mail", "my", "send").contains(sub)
                && sender instanceof Player pGate
                && !plugin.isTradeCommandAllowed(pGate, sub)) {
            pGate.sendMessage(plugin.lang().msg("common.world-disabled"));
            return true;
        }
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "market", "open" -> {
                if (!checkPlayer(sender, "trademarket.use")) return true;
                if (plugin.isTradeBlocked((Player) sender)) {
                    sender.sendMessage(plugin.lang().msg("common.world-disabled"));
                    return true;
                }
                new MarketGui(plugin, (Player) sender).open();
            }
            case "sell" -> {
                if (!checkPlayer(sender, "trademarket.sell")) return true;
                Player p = (Player) sender;
                if (args.length < 2) {
                    p.sendMessage(plugin.lang().msg("help.line-sell"));
                    return true;
                }
                Long price = Money.parse(args[1]);
                if (price == null) {
                    p.sendMessage(plugin.lang().msg("common.invalid-number"));
                    return true;
                }
                int amount = args.length >= 3 ? parseInt(args[2], -1) : -1;
                // 定价与近期成交均价偏离过大时先弹确认，防手滑
                if (plugin.market().needsPriceConfirm(p, price)) {
                    long avg = plugin.market().recentAverage(
                            p.getInventory().getItemInMainHand().getType());
                    new ConfirmListGui(plugin, p, price, amount, avg).open();
                    return true;
                }
                String error = plugin.market().sell(p, price, amount, true);
                if (error != null) p.sendMessage(plugin.lang().msg(error));
            }
            case "order" -> {
                if (!checkPlayer(sender, "trademarket.sell")) return true;
                Player p = (Player) sender;
                // 打开发布向导；手上拿着物品则自动识别为候选求购物品（界面中可一键清除）
                var draft = plugin.order().draft(p.getUniqueId());
                // /tk order <单价> [数量]：参数预填（单价模式），省去手动设置
                if (args.length >= 2) {
                    Long price = Money.parse(args[1]);
                    if (price == null) {
                        p.sendMessage(plugin.lang().msg("common.invalid-number"));
                        return true;
                    }
                    draft.priceCents = price;
                    draft.unitMode = true;
                    if (args.length >= 3) {
                        int amt = parseInt(args[2], -1);
                        if (amt < 1 || amt > plugin.settings().orderMaxAmount) {
                            p.sendMessage(plugin.lang().msg("common.invalid-amount"));
                            return true;
                        }
                        draft.amount = amt;
                    }
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!hand.getType().isAir()) {
                    draft.material = hand.getType();
                    draft.sample = hand.clone();
                    draft.provider = plugin.settings().customItemDetect
                            ? com.trademarket.item.ItemProviderRegistry.resolve(hand)
                            : com.trademarket.item.ItemProviderRegistry.VANILLA;
                    draft.strictName = false; // 手持实物发布：接受同类实物
                }
                new com.trademarket.market.OrderCreateGui(plugin, p).open();
            }
            case "myorders" -> {
                if (!checkPlayer(sender, "trademarket.sell")) return true;
                new com.trademarket.market.MyOrdersGui(plugin, (Player) sender).open();
            }
            case "offers" -> {
                if (!checkPlayer(sender, "trademarket.sell")) return true;
                new com.trademarket.market.MyOffersGui(plugin, (Player) sender).open();
            }
            case "orders" -> {
                if (!checkPlayer(sender, "trademarket.use")) return true;
                new OrdersGui(plugin, (Player) sender).open();
            }
            case "auction", "ah" -> {
                if (!checkPlayer(sender, "trademarket.auction")) return true;
                Player p = (Player) sender;
                if (args.length >= 2 && args[1].equalsIgnoreCase("sell")) {
                    if (args.length < 3) {
                        p.sendMessage(plugin.lang().msg("help.line-auction-sell"));
                        return true;
                    }
                    Long start = Money.parse(args[2]);
                    Long buyout = args.length >= 4 ? Money.parse(args[3]) : 0L;
                    double minutes = args.length >= 5 ? parseDouble(args[4], 0) : 0;
                    if (start == null || buyout == null) {
                        p.sendMessage(plugin.lang().msg("common.invalid-number"));
                        return true;
                    }
                    String error = plugin.auction().create(p, start, buyout, minutes);
                    if (error != null) p.sendMessage(plugin.lang().msg(error));
                } else {
                    new AuctionGui(plugin, p).open();
                }
            }
            case "mailbox", "mail" -> {
                if (checkPlayer(sender, "trademarket.mailbox")) {
                    new MailboxGui(plugin, (Player) sender).open();
                }
            }
            case "my" -> {
                if (checkPlayer(sender, "trademarket.sell")) {
                    new com.trademarket.market.MyListingsGui(plugin, (Player) sender).open();
                }
            }
            case "send" -> handleSend(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("trademarket.admin")) {
                    sender.sendMessage(plugin.lang().msg("common.no-permission"));
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage(plugin.lang().msg("common.reloaded"));
            }
            case "admin" -> handleAdmin(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    // ==================== 玩家互发邮件 ====================

    /**
     * /tk send <玩家> item [descr 备注]  —— 发送手上物品（真实扣除）+ 可选备注
     * /tk send <玩家> descr 文字...       —— 发送一段话
     * 弹确认 GUI；手续费按 config（LP 权限 trademarket.mail.nofee 豁免）。
     */
    private void handleSend(CommandSender sender, String[] args) {
        if (!checkPlayer(sender, "trademarket.sell")) return;
        Player p = (Player) sender;
        if (args.length < 3) {
            p.sendMessage(plugin.lang().msg("send.usage"));
            return;
        }
        // /tk send <玩家> item|descr [descr 备注]：玩家=args[1]，模式=args[2]
        org.bukkit.OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                if (off.getName() != null && off.getName().equalsIgnoreCase(args[1])) {
                    target = off;
                    break;
                }
            }
        }
        if (target == null) {
            p.sendMessage(plugin.lang().msg("admin.player-not-found"));
            return;
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(plugin.lang().msg("send.self"));
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        String[] spec = parseMailSpec(args, 3);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (mode.equals("item") && hand.getType().isAir()) {
            p.sendMessage(plugin.lang().msg("send.hand-empty"));
            return;
        }
        if (!mode.equals("item") && !mode.equals("descr")) {
            p.sendMessage(plugin.lang().msg("send.usage"));
            return;
        }
        long fee = p.hasPermission("trademarket.mail.nofee")
                ? 0 : plugin.settings().mailFeeCents;
        ItemStack display = mode.equals("item")
                ? hand.clone() : new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        // descr 模式：留言文字在 spec[0]；item 模式：备注在 spec[1] —— 界面与发送共用同一值
        final String fNote = mode.equals("descr")
                ? (spec[1].isEmpty() ? spec[0] : spec[1])
                : spec[1];
        var info = plugin.lang().lore("gui.send.info",
                "{target}", offName(target),
                "{type}", plugin.lang().raw(mode.equals("item")
                        ? "send.type-item" : "send.type-message"),
                "{note}", fNote.isEmpty() ? plugin.lang().raw("gui.mailconfirm.note-none") : fNote,
                "{fee}", fee > 0 ? Money.format(fee) : "0",
                "{symbol}", plugin.settings().currencySymbol);
        final org.bukkit.OfflinePlayer fTarget = target;
        final ItemStack fHand = hand.clone();
        final String fTName = offName(target);
        final String fMode = mode;
        final long fFee = fee;
        new com.trademarket.gui.ConfirmActionGui(plugin, p,
                plugin.lang().title("gui.send.title"), display, info,
                () -> {
                    if (fFee > 0 && !plugin.economy().withdraw(p, fFee)) {
                        p.sendMessage(plugin.lang().msg("send.cant-afford-fee",
                                "{fee}", Money.format(fFee), "{symbol}", plugin.settings().currencySymbol));
                        return;
                    }
                    try {
                        java.util.List<Long> ids = new ArrayList<>();
                        if (fMode.equals("item")) {
                            // 确认时从主手真实扣除（防止确认前换物品）
                            ItemStack current = p.getInventory().getItemInMainHand();
                            if (current.getType().isAir()) {
                                if (fFee > 0) plugin.economy().deposit(p, fFee);
                                p.sendMessage(plugin.lang().msg("send.hand-empty"));
                                return;
                            }
                            p.getInventory().setItemInMainHand(null);
                            try {
                                ids.add(plugin.mail().sendItem(fTarget.getUniqueId(), fTName, p.getName(),
                                        plugin.lang().raw("send.item-subject"), fNote, current).id);
                            } catch (Exception e2) {
                                p.getInventory().setItemInMainHand(current);
                                throw e2;
                            }
                        } else {
                            ids.add(plugin.mail().sendNotice(fTarget.getUniqueId(), fTName, p.getName(),
                                    plugin.lang().raw("send.message-subject"), fNote).id);
                        }
                        plugin.mail().markBatch(p.getUniqueId(), ids);
                        plugin.database().tx(p.getUniqueId(), "PLAYER_SEND",
                                "to=" + fTarget.getUniqueId() + " mode=" + fMode + " fee=" + fFee);
                        p.sendMessage(plugin.lang().msg("send.done"));
                    } catch (Exception e) {
                        if (fFee > 0) plugin.economy().deposit(p, fFee);
                        p.sendMessage(plugin.lang().msg("common.internal-error"));
                    }
                },
                () -> { }).open();
    }

    // ==================== 管理员命令 ====================

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("trademarket.admin")) {
            sender.sendMessage(plugin.lang().msg("common.no-permission"));
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("help")) {
            sendAdminHelp(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "mail", "mailonline", "mailall" -> {
                // 三通道：单发(在线/离线均可) / 仅在线 / 全体；类型参数后可用 descr 追加备注
                boolean single = args[1].equalsIgnoreCase("mail");
                int typeIdx = single ? 3 : 2;
                if (args.length <= typeIdx) {
                    sender.sendMessage(plugin.lang().msg(single ? "admin.usage-mail"
                            : args[1].equalsIgnoreCase("mailonline")
                            ? "admin.usage-mailonline" : "admin.usage-mailall"));
                    return;
                }
                String type = args[typeIdx].toLowerCase(Locale.ROOT);
                String[] spec = parseMailSpec(args, typeIdx + 1);
                List<org.bukkit.OfflinePlayer> targets = new ArrayList<>();
                if (single) {
                    // 在线优先，其次按名字精确匹配历史玩家（离线也可发）
                    org.bukkit.OfflinePlayer found = Bukkit.getPlayerExact(args[2]);
                    if (found == null) {
                        for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                            if (off.getName() != null && off.getName().equalsIgnoreCase(args[2])) {
                                found = off;
                                break;
                            }
                        }
                    }
                    if (found == null) {
                        sender.sendMessage(plugin.lang().msg("admin.player-not-found"));
                        return;
                    }
                    targets.add(found);
                } else if (args[1].equalsIgnoreCase("mailonline")) {
                    targets.addAll(Bukkit.getOnlinePlayers());
                } else {
                    // 全体 = 历史玩家 ∪ 当前在线（用户缓存可能滞后，确保在线玩家必达）
                    java.util.HashSet<UUID> seen = new java.util.HashSet<>();
                    for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                        if (off.hasPlayedBefore()) {
                            targets.add(off);
                            seen.add(off.getUniqueId());
                        }
                    }
                    for (Player pl : Bukkit.getOnlinePlayers()) {
                        if (seen.add(pl.getUniqueId())) targets.add(pl);
                    }
                }
                if (targets.isEmpty()) {
                    sender.sendMessage(plugin.lang().msg("admin.no-targets"));
                    return;
                }
                // 全部弹确认 GUI（单发/群发一致）
                if (!(sender instanceof Player pmPlayer)) {
                    sender.sendMessage(plugin.lang().msg("common.player-only"));
                    return;
                }
                org.bukkit.inventory.ItemStack display = switch (type) {
                    case "money" -> new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_INGOT);
                    case "command" -> new org.bukkit.inventory.ItemStack(org.bukkit.Material.COMMAND_BLOCK);
                    default -> pmPlayer.getInventory().getItemInMainHand().getType().isAir()
                            ? new org.bukkit.inventory.ItemStack(org.bukkit.Material.CHEST)
                            : pmPlayer.getInventory().getItemInMainHand().clone();
                };
                String typeLabel = plugin.lang().raw(switch (type) {
                    case "money" -> "gui.mailconfirm.type-money";
                    case "command" -> "gui.mailconfirm.type-command";
                    default -> "gui.mailconfirm.type-item";
                });
                String detailText = switch (type) {
                    case "money", "command" -> spec[0];
                    default -> plugin.lang().raw("gui.mailconfirm.detail-item");
                };
                String scopeText = single
                        ? offName(targets.get(0))
                        : plugin.lang().raw(args[1].equalsIgnoreCase("mailonline")
                                ? "gui.mailconfirm.scope-online"
                                : "gui.mailconfirm.scope-all");
                String noteText = spec[1].isEmpty()
                        ? plugin.lang().raw("gui.mailconfirm.note-none") : spec[1];
                var info = plugin.lang().lore("gui.mailconfirm.info",
                        "{type}", typeLabel,
                        "{detail}", detailText,
                        "{targets}", String.valueOf(targets.size()),
                        "{scope}", scopeText,
                        "{note}", noteText);
                if (single) {
                    var target = targets.get(0);
                    new com.trademarket.gui.ConfirmActionGui(plugin, pmPlayer,
                            plugin.lang().title("gui.mailconfirm.title"), display, info,
                            () -> deliverMail(pmPlayer, type, spec[0], spec[1], targets, true),
                            () -> { }).open();
                } else {
                    pendingMass.put(pmPlayer.getUniqueId(), new PendingMass(type, spec[0], spec[1],
                            targets, System.currentTimeMillis() + 300_000L));
                    new com.trademarket.gui.ConfirmActionGui(plugin, pmPlayer,
                            plugin.lang().title("gui.mailconfirm.title"), display, info,
                            () -> executePendingMass(pmPlayer),
                            () -> pendingMass.remove(pmPlayer.getUniqueId())).open();
                }
            }
            case "mailundo" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.lang().msg("common.player-only"));
                    return;
                }
                int n = plugin.mail().recallLastBatch(p.getUniqueId());
                if (n < 0) sender.sendMessage(plugin.lang().msg("admin.mailundo-none"));
                else sender.sendMessage(plugin.lang().msg("admin.mailundo-done",
                        "{count}", String.valueOf(n)));
            }
            case "refund" -> {
                if (args.length < 4) {
                    sender.sendMessage(plugin.lang().msg("admin.usage-refund"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    sender.sendMessage(plugin.lang().msg("admin.player-not-found"));
                    return;
                }
                Long cents = Money.parse(args[3]);
                if (cents == null) {
                    sender.sendMessage(plugin.lang().msg("common.invalid-number"));
                    return;
                }
                if (!plugin.economy().available()) {
                    sender.sendMessage(plugin.lang().msg("common.economy-unavailable"));
                    return;
                }
                plugin.economy().deposit(target, cents);
                plugin.database().tx(target.getUniqueId(), "ADMIN_REFUND",
                        "amount=" + cents + " by=" + sender.getName());
                sender.sendMessage(plugin.lang().msg("admin.refund-done",
                        "{amount}", Money.format(cents), "{symbol}", plugin.settings().currencySymbol));
            }
            case "redeliver" -> {
                // 无参数 = 打开卡住交易管理界面；带参数 = 按编号补发
                if (args.length < 3) {
                    if (!(sender instanceof Player p0)) {
                        sender.sendMessage(plugin.lang().msg("common.player-only"));
                        return;
                    }
                    new com.trademarket.gui.ReconcileGui(plugin, p0).open();
                    return;
                }
                if (args.length < 4 || !(sender instanceof Player p)) {
                    sender.sendMessage(plugin.lang().msg("admin.usage-redeliver"));
                    return;
                }
                long id;
                try {
                    id = Long.parseLong(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(plugin.lang().msg("admin.usage-redeliver"));
                    return;
                }
                if (args[2].equalsIgnoreCase("auction")) {
                    var a = plugin.database().rawAuction(id);
                    if (a == null || a.item == null) {
                        sender.sendMessage(plugin.lang().msg("admin.no-entry"));
                        return;
                    }
                    java.util.UUID to = a.topBidderId != null ? a.topBidderId : a.sellerId;
                    String toName = a.topBidderName != null ? a.topBidderName : a.sellerName;
                    plugin.mail().sendItem(to, toName, "管理员",
                            plugin.lang().raw("auction.won-subject"), "", a.item);
                    plugin.database().tx(p.getUniqueId(), "ADMIN_REDELIVER", "auction=" + id);
                    sender.sendMessage(plugin.lang().msg("admin.mail-sent"));
                } else if (args[2].equalsIgnoreCase("listing")) {
                    var lst = plugin.database().rawListing(id);
                    if (lst == null || lst.item == null) {
                        sender.sendMessage(plugin.lang().msg("admin.no-entry"));
                        return;
                    }
                    plugin.mail().sendItem(lst.sellerId, lst.sellerName, "管理员",
                            plugin.lang().raw("market.expired-returned-subject"), "", lst.item);
                    plugin.database().tx(p.getUniqueId(), "ADMIN_REDELIVER", "listing=" + id);
                    sender.sendMessage(plugin.lang().msg("admin.mail-sent"));
                } else {
                    sender.sendMessage(plugin.lang().msg("admin.usage-redeliver"));
                }
            }
            case "stats" -> {
                sender.sendMessage(plugin.lang().msg("admin.stats"));
                sender.sendMessage(plugin.lang().msg("admin.stats-listings",
                        "{count}", String.valueOf(plugin.market().count())));
                sender.sendMessage(plugin.lang().msg("admin.stats-auctions",
                        "{count}", String.valueOf(plugin.auction().count())));
                sender.sendMessage(plugin.lang().msg("admin.stats-orders",
                        "{count}", String.valueOf(plugin.order().count())));
                sender.sendMessage(plugin.lang().msg("admin.stats-mails",
                        "{count}", String.valueOf(plugin.mail().unclaimedCount())));
                long todayStart = java.time.LocalDate.now()
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                long[] today = plugin.database().salesStats(todayStart);
                long[] total = plugin.database().salesStats(0);
                sender.sendMessage(plugin.lang().msg("admin.stats-trade-today",
                        "{count}", String.valueOf(today[0]),
                        "{total}", Money.format(today[1])));
                sender.sendMessage(plugin.lang().msg("admin.stats-trade-total",
                        "{count}", String.valueOf(total[0]),
                        "{total}", Money.format(total[1])));
                sender.sendMessage(plugin.lang().msg("admin.stats-escrow-auction",
                        "{total}", Money.format(plugin.database().auctionEscrowTotal())));
                sender.sendMessage(plugin.lang().msg("admin.stats-escrow-order",
                        "{total}", Money.format(plugin.database().orderEscrowTotal())));
            }
            default -> sendAdminHelp(sender);
        }
    }

    /** 确认回调：执行待发送的群发任务（5 分钟内有效）。 */
    public void executePendingMass(Player player) {
        PendingMass pm = pendingMass.remove(player.getUniqueId());
        if (pm == null) {
            player.sendMessage(plugin.lang().msg("admin.mailconfirm-none"));
            return;
        }
        if (System.currentTimeMillis() > pm.expireAt()) {
            player.sendMessage(plugin.lang().msg("admin.mailconfirm-expired"));
            return;
        }
        deliverMail(player, pm.type(), pm.spec(), pm.note(), pm.targets(), false);
    }

    /** 解析 "类型参数... descr 备注"，返回 {参数串, 备注}。 */
    private String[] parseMailSpec(String[] args, int start) {
        String joined = start >= args.length ? ""
                : String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
        String[] out = new String[]{"", ""};
        String[] parts = joined.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if ("descr".equals(parts[i])) {
                out[0] = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, i)).trim();
                out[1] = String.join(" ", java.util.Arrays.copyOfRange(parts, i + 1, parts.length)).trim();
                return out;
            }
        }
        out[0] = joined.trim();
        return out;
    }

    /** 按 item/money/command 类型向目标玩家批量投递邮件；备注写入邮件正文。 */
    private void deliverMail(CommandSender sender, String type, String spec, String note,
                             List<org.bukkit.OfflinePlayer> targets, boolean single) {
        int sent = 0;
        List<Long> batchIds = new ArrayList<>();
        switch (type) {
            case "money" -> {
                Long cents = spec.isEmpty() ? null : Money.parse(spec.split("\\s+")[0]);
                if (cents == null) {
                    sender.sendMessage(plugin.lang().msg("common.invalid-number"));
                    return;
                }
                if (!plugin.economy().available()) {
                    sender.sendMessage(plugin.lang().msg("common.economy-unavailable"));
                    return;
                }
                String subject = plugin.lang().raw("mailbox.money-subject");
                for (org.bukkit.OfflinePlayer off : targets) {
                    try {
                        batchIds.add(plugin.mail().sendMoney(off.getUniqueId(), offName(off),
                                "管理员", subject, note, cents).id);
                        sent++;
                    } catch (Exception e) {
                        plugin.getLogger().warning("邮件投递失败 " + off.getName() + ": " + e.getMessage());
                    }
                }
            }
            case "item" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.lang().msg("common.player-only"));
                    return;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    p.sendMessage(plugin.lang().msg("common.hand-empty"));
                    return;
                }
                String data = com.trademarket.util.Items.serialize(hand.clone());
                if (data == null) {
                    sender.sendMessage(plugin.lang().msg("common.internal-error"));
                    return;
                }
                String subject = plugin.lang().raw("mailbox.item-subject");
                for (org.bukkit.OfflinePlayer off : targets) {
                    try {
                        batchIds.add(plugin.mail().sendItemData(off.getUniqueId(), offName(off),
                                "管理员", subject, note, data).id);
                        sent++;
                    } catch (Exception e) {
                        plugin.getLogger().warning("邮件投递失败 " + off.getName() + ": " + e.getMessage());
                    }
                }
            }
            case "command" -> {
                if (spec.isEmpty()) {
                    sender.sendMessage(plugin.lang().msg(single ? "admin.usage-mail" : "admin.usage-mailall"));
                    return;
                }
                String subject = plugin.lang().raw("mailbox.command-subject");
                for (org.bukkit.OfflinePlayer off : targets) {
                    try {
                        batchIds.add(plugin.mail().sendCommand(off.getUniqueId(), offName(off),
                                "管理员", subject, note, spec).id);
                        sent++;
                    } catch (Exception e) {
                        plugin.getLogger().warning("邮件投递失败 " + off.getName() + ": " + e.getMessage());
                    }
                }
            }
            default -> {
                sender.sendMessage(plugin.lang().msg(single ? "admin.usage-mail" : "admin.usage-mailall"));
                return;
            }
        }
        if (sender instanceof Player p) {
            plugin.mail().markBatch(p.getUniqueId(), batchIds);
        }
        if (single) sender.sendMessage(plugin.lang().msg("admin.mail-sent"));
        else sender.sendMessage(plugin.lang().msg("admin.mailall-sent",
                "{count}", String.valueOf(sent)));
    }

    private String offName(org.bukkit.OfflinePlayer off) {
        return off.getName() == null ? "?" : off.getName();
    }

    // ==================== 帮助 ====================

    /** 玩家帮助；管理区段只在 /tk admin help 中展示。 */
    private void sendHelp(CommandSender sender) {
        for (String key : new String[]{
                "help.header", "help.line-market", "help.line-sell", "help.line-order",
                "help.line-orders", "help.line-auction", "help.line-auction-sell",
                "help.line-mailbox", "help.line-my", "help.line-send", "help.line-offers", "help.line-myorders"}) {
            sender.sendMessage(plugin.lang().plain(key));
        }
    }

    /** 管理员帮助：/tk admin 或 /tk admin help。 */
    private void sendAdminHelp(CommandSender sender) {
        for (String key : new String[]{
                "help.admin-header", "help.admin-line-mail", "help.admin-line-mailonline",
                "help.admin-line-mailall", "help.admin-line-refund",
                "help.admin-line-redeliver", "help.admin-line-redeliver-id",
                "help.admin-line-mailundo", "help.admin-line-stats", "help.admin-line-reload"}) {
            sender.sendMessage(plugin.lang().plain(key));
        }
    }

    private boolean checkPlayer(CommandSender sender, String permission) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().msg("common.player-only"));
            return false;
        }
        if (!player.hasPermission(permission)) {
            player.sendMessage(plugin.lang().msg("common.no-permission"));
            return false;
        }
        return true;
    }

    private double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        if (args.length == 1) {
            // 按权限给提示：玩家只看到自己能用的，admin/reload 仅管理员可见
            List<String> subs = new ArrayList<>();
            subs.add("market");
            subs.add("help");
            if (sender.hasPermission("trademarket.sell")) {
                subs.add("sell");
                subs.add("my");
                subs.add("order");
                subs.add("orders");
                subs.add("myorders");
                subs.add("offers");
                subs.add("send");
            }
            if (sender.hasPermission("trademarket.auction")) subs.add("auction");
            if (sender.hasPermission("trademarket.mailbox")) subs.add("mailbox");
            if (sender.hasPermission("trademarket.admin")) {
                subs.add("reload");
                subs.add("admin");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auction")) {
            return filter(List.of("sell"), args[1]);
        }
        // /tk send：Tab 仅显示在线玩家；离线玩家需手动输入完整名字
        if (args[0].equalsIgnoreCase("send") && sender.hasPermission("trademarket.sell")) {
            if (args.length == 2) {
                String pl = args[1].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(pl))
                        .toList();
            }
            if (args.length == 3) {
                return filter(List.of("item", "descr"), args[2]);
            }
            // 备注关键字 descr 尚未出现时持续提示
            boolean hasDescr = false;
            for (int i = 3; i < args.length; i++) {
                if ("descr".equals(args[i])) hasDescr = true;
            }
            if (!hasDescr) return List.of("descr");
        }
        if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("trademarket.admin")) {
            return tabAdmin(sender, args);
        }
        return List.of();
    }

    /** /tk admin ... 的分级补全：先子命令，再玩家名，再类型，最后金额/descr。 */
    private List<String> tabAdmin(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (args.length) {
            case 2:
                return filter(List.of("mail", "mailonline", "mailall", "mailundo",
                        "refund", "redeliver", "stats", "help"), args[1]);
            case 3:
                return switch (sub) {
                    case "mail", "refund" -> allPlayerNames(args[2]);
                    case "redeliver" -> filter(List.of("auction", "listing"), args[2]);
                    case "mailonline", "mailall" -> filter(List.of("item", "money", "command"), args[2]);
                    default -> List.of();
                };
            case 4:
                return switch (sub) {
                    case "mail" -> filter(List.of("item", "money", "command"), args[3]);
                    case "mailonline", "mailall" -> typeParamOptions(args[2], args[3]);
                    case "refund" -> filter(List.of("100", "1k", "10k", "100k"), args[3]);
                    default -> List.of();
                };
            case 5:
                return "mail".equals(sub) ? filter(typeParamOptions(args[3], args[4]), args[4]) : List.of();
            default:
                // 备注关键字 descr：出现前持续 Tab 提示
                if ((sub.equals("mail") && args.length > 5)
                        || ((sub.equals("mailonline") || sub.equals("mailall")) && args.length > 4)) {
                    int from = sub.equals("mail") ? 4 : 3;
                    boolean hasDescr = false;
                    for (int i = from; i < args.length; i++) {
                        if ("descr".equals(args[i])) hasDescr = true;
                    }
                    if (!hasDescr) return List.of("descr");
                }
                return List.of();
        }
    }

    /** mail 类命令的类型参数提示：money 给金额示例，其余给 descr。 */
    private List<String> typeParamOptions(String type, String prefix) {
        return filter("money".equals(type)
                ? List.of("descr", "100", "1k", "10k")
                : List.of("descr"), prefix);
    }

    /** 全部玩家名（在线 + 进过服的离线玩家，去重）。 */
    private List<String> allPlayerNames(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            names.add(pl.getName());
        }
        for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
            if (off.getName() != null) names.add(off.getName());
        }
        return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}

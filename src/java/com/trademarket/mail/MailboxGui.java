package com.trademarket.mail;

import com.trademarket.TradeMarketPlugin;
import com.trademarket.gui.GuiBase;
import com.trademarket.model.BuyOrder;
import com.trademarket.model.Mail;
import com.trademarket.util.Items;
import com.trademarket.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 邮箱界面：点击邮件领取附件；46 一键领取。 */
public final class MailboxGui extends GuiBase {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private int page;

    public MailboxGui(TradeMarketPlugin plugin, Player player) {
        super(plugin, player, 6, plugin.lang().title("gui.mailbox.title"));
    }

    private List<Mail> mails() {
        return new ArrayList<>(((TradeMarketPlugin) plugin).mail().mailboxOf(player.getUniqueId()));
    }

    @Override
    protected void build() {
        List<Mail> list = mails();
        // 最新在前
        list.sort((a, b) -> Long.compare(b.id, a.id));
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (page >= maxPage) page = maxPage - 1;
        fillBorder();
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = page * CONTENT_SLOTS.length + i;
            if (index >= list.size()) break;
            place(CONTENT_SLOTS[i], mailIcon(list.get(index)));
        }
        placePageNav(page, maxPage);
        place(46, Items.guiIcon(Material.HOPPER, lang.line("gui.mailbox.claim-all"), List.of()));
        if (list.isEmpty()) {
            place(22, Items.guiIcon(Material.BARRIER, lang.line("gui.mailbox.empty"), List.of()));
        }
    }

    private ItemStack mailIcon(Mail m) {
        boolean isOffer = "OFFER".equals(m.kind);
        com.trademarket.TradeMarketPlugin tmp = (com.trademarket.TradeMarketPlugin) plugin;
        com.trademarket.model.Offer rec = isOffer ? tmp.mail().pendingOfferFor(m) : null;

        List<String> rawLore = new ArrayList<>(lang.yamlList("gui.mailbox.mail-lore"));
        if (m.itemData != null) rawLore.addAll(lang.yamlList("gui.mailbox.mail-lore-item"));
        if (m.moneyCents > 0) rawLore.addAll(lang.yamlList("gui.mailbox.mail-lore-money"));
        if (m.command != null) rawLore.addAll(lang.yamlList("gui.mailbox.mail-lore-command"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        long days = Math.max(0, (System.currentTimeMillis() - m.createdAt) / 86_400_000L);
        for (String line : rawLore) {
            lore.add(Items.loreLine(line
                    .replace("{sender}", m.sender)
                    .replace("{time}", FMT.format(new Date(m.createdAt)))
                    .replace("{days}", String.valueOf(days))
                    .replace("{money}", Money.format(m.moneyCents))
                    .replace("{symbol}", settings.currencySymbol)));
        }
        if (m.body != null && !m.body.isEmpty()) {
            java.util.List<String> wrapped = Items.wrap(
                    m.body.replaceAll("&[0-9a-fk-orA-FK-OR]", ""), 20);
            for (int wi = 0; wi < wrapped.size(); wi++) {
                lore.add(Items.loreLine(lang.raw(wi == 0 ? "gui.mailbox.note-label" : "gui.mailbox.note-continue")
                        .replace("{body}", wrapped.get(wi))));
            }
        }
        if (isOffer) {
            // {want} = 求购单要求的物品名；{item} = 实际提交的物品名，供买家对照
            com.trademarket.model.BuyOrder order = rec == null ? null : tmp.order().get(rec.orderId);
            String want = "?";
            if (order != null) {
                if (order.customName != null) want = order.customName;
                else want = Items.plainName(order.icon() == null
                        ? new ItemStack(order.material) : order.icon());
            }
            lore.addAll(lang.lore("gui.mailbox.offer-lore",
                    "{item}", rec == null || rec.item() == null ? "?"
                            : com.trademarket.util.Items.plainName(rec.item()),
                    "{want}", want,
                    "{unit}", rec == null ? "?" : Money.format(rec.unitPriceCents),
                    "{symbol}", settings.currencySymbol));
        } else {
            boolean hasAttachment = m.itemData != null || m.moneyCents > 0 || m.command != null;
            if (!hasAttachment) {
                lore.addAll(lang.lore("gui.mailbox.mail-lore-claimed"));
            } else {
                lore.addAll(lang.lore("gui.mailbox.mail-lore-claim-hint"));
            }
        }
        ItemStack icon = m.icon();
        if (icon == null && isOffer && rec != null) icon = rec.item();
        if (icon == null) {
            icon = Items.guiIcon(m.itemData != null ? Material.BARRIER : Material.PAPER,
                    Items.loreLine(m.subject), lore);
            return icon;
        }
        return Items.named(icon, Items.loreLine(m.subject), lore);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        TradeMarketPlugin tm = (TradeMarketPlugin) plugin;
        List<Mail> list = mails();
        list.sort((a, b) -> Long.compare(b.id, a.id));
        int maxPage = Math.max(1, (list.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        if (event.getRawSlot() == 45 && page > 0) { page--; open(); return; }
        if (event.getRawSlot() == 53 && page < maxPage - 1) { page++; open(); return; }
        if (event.getRawSlot() == 46) {
            int[] r = tm.mail().claimAll(player);
            if (r[0] == 0 && r[1] == 0) player.sendMessage(tm.lang().msg("mailbox.claim-none"));
            else player.sendMessage(tm.lang().msg("mailbox.claim-all-done",
                    "{ok}", String.valueOf(r[0]), "{fail}", String.valueOf(r[1])));
            if (r[0] > 0) sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
            open();
            return;
        }
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == event.getRawSlot()) { contentIndex = i; break; }
        }
        if (contentIndex < 0) return;
        int index = page * CONTENT_SLOTS.length + contentIndex;
        if (index >= list.size()) return;
        Mail m = list.get(index);
        if ("OFFER".equals(m.kind)) {
            if (event.isRightClick()) {
                tm.mail().rejectOffer(player, m);
                sound(Sound.ENTITY_ITEM_PICKUP);
                open();
                return;
            }
            String error = tm.mail().acceptOffer(player, m);
            if (error == null) {
                sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
            } else {
                player.sendMessage(tm.lang().msg(error));
                sound(Sound.ENTITY_VILLAGER_NO);
            }
            open();
            return;
        }
        String error = tm.mail().claim(player, m);
        if (error == null) {
            String key = m.itemData != null && m.moneyCents > 0 ? "mailbox.claimed-both"
                    : m.moneyCents > 0 ? "mailbox.claimed-money"
                    : m.itemData == null && m.command == null ? "mailbox.claimed-notice"
                    : "mailbox.claimed-item";
            player.sendMessage(tm.lang().msg(key,
                    "{money}", Money.format(m.moneyCents),
                    "{symbol}", settings.currencySymbol));
            sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        } else {
            player.sendMessage(tm.lang().msg(error));
            sound(Sound.ENTITY_VILLAGER_NO);
        }
        open();
    }
}

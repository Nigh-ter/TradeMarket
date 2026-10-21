package com.trademarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 物品序列化与展示工具。
 * 失败语义统一为"返回 null"并由调用方处理，绝不让单条坏数据炸掉整个界面。
 */
public final class Items {

    private Items() {
    }

    private static final int MAX_SERIALIZED_BYTES = 256 * 1024;

    /** 序列化为 Base64；失败返回 null（调用方应中止入库而不是吞掉物品）。 */
    public static String serialize(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            try (org.bukkit.util.io.BukkitObjectOutputStream out =
                     new org.bukkit.util.io.BukkitObjectOutputStream(buf)) {
                out.writeObject(item);
            }
            byte[] data = buf.toByteArray();
            if (data.length > MAX_SERIALIZED_BYTES) return null;
            return Base64.getEncoder().encodeToString(data);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反序列化；任何异常返回 null，绝不向上抛。 */
    public static ItemStack deserialize(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] data = Base64.getDecoder().decode(base64);
            try (ByteArrayInputStream in = new ByteArrayInputStream(data);
                 org.bukkit.util.io.BukkitObjectInputStream ois =
                     new org.bukkit.util.io.BukkitObjectInputStream(in)) {
                Object obj = ois.readObject();
                return obj instanceof ItemStack is ? is : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 物品展示名：自定义名优先，否则用原版翻译键（客户端本地化）。 */
    public static Component displayName(ItemStack item) {
        if (item == null) return Component.empty();
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) return name;
        }
        return Component.translatable(item.getType().translationKey());
    }

    /** 用于搜索的纯文本：自定义名 + 中文名 + 英文材质名。 */
    public static String searchableName(ItemStack item) {
        String plain = PlainTextComponentSerializer.plainText().serialize(displayName(item));
        String material = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String zh = ItemNamesZh.zh(item.getType());
        return (plain + " " + (zh == null ? "" : zh) + " " + material).toLowerCase(Locale.ROOT);
    }

    /** 聊天/日志用的纯文本物品名：原版物品用中文名，自定义名原样保留。 */
    public static String plainName(ItemStack item) {
        if (item == null) return "?";
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(displayName(item));
        }
        String zh = ItemNamesZh.zh(item.getType());
        return zh != null ? zh : PlainTextComponentSerializer.plainText().serialize(displayName(item));
    }

    /** 物品图标：展示副本，附加 lore。 */
    public static ItemStack icon(ItemStack item, List<Component> loreLines) {
        ItemStack copy = item.asQuantity(Math.max(1, item.getAmount()));
        var meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            for (Component c : meta.lore() == null ? List.<Component>of() : meta.lore()) lore.add(c);
            if (!lore.isEmpty()) lore.add(Component.empty());
            lore.addAll(loreLines);
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    public static Component loreLine(String legacyText) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
    }

    /** 是否为潜影盒类容器。 */
    public static boolean isShulker(ItemStack item) {
        if (item == null) return false;
        String n = item.getType().name();
        return n.equals("SHULKER_BOX") || n.endsWith("_SHULKER_BOX");
    }

    /** 读取潜影盒内容；非潜影盒或读取失败返回 null。 */
    public static ItemStack[] shulkerContents(ItemStack item) {
        if (!isShulker(item)) return null;
        try {
            var meta = item.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                    && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
                return box.getInventory().getContents();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 文本按显示宽度折行（中文/全角按 2 列、其余按 1 列，每行 width 列），
     * 用于 lore 里的长备注 —— 避免文字一直向右叠出界面。
     */
    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int cols = 0;
        StringBuilder line = new StringBuilder();
        int lastSafe = -1; // 行内最后一个"安全断点"（标点后 / 字母数字串边界前）
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int w = cp > 0x2E80 ? 2 : 1;
            if (cols + w > width && line.length() > 0) {
                // 回退到安全断点，避免把 100.00 / Diamond 这类词切断
                if (lastSafe >= Math.max(0, line.length() / 2)) {
                    String tail = line.substring(lastSafe + 1);
                    out.add(line.substring(0, lastSafe + 1));
                    line.setLength(0);
                    line.append(tail.trim());
                    cols = tail.trim().codePoints().map(c -> c > 0x2E80 ? 2 : 1).sum();
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    cols = 0;
                }
                lastSafe = -1;
            }
            line.append(ch);
            cols += w;
            // 断点判定：标点后，或"字母数字串"开始前
            boolean isPunct = "，。、；：！？,.;:!?)]）】".indexOf(ch) >= 0;
            boolean alnumNext = i + ch.length() < text.length()
                    && Character.isLetterOrDigit(text.codePointAt(i + ch.length()));
            boolean alnumCur = Character.isLetterOrDigit(cp);
            if (isPunct) lastSafe = line.length() - 1;
            else if (alnumNext && !alnumCur) lastSafe = line.length() - 1; // 词串开始前
            i += ch.length();
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    public static ItemStack guiIcon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 在已有物品副本上设置显示名并追加 lore（不改动原物品）。 */
    public static ItemStack named(ItemStack base, Component name, List<Component> lore) {
        ItemStack copy = base.asQuantity(Math.max(1, base.getAmount()));
        var meta = copy.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                List<Component> merged = new ArrayList<>(meta.lore() == null ? List.<Component>of() : meta.lore());
                merged.addAll(lore);
                meta.lore(merged);
            }
            copy.setItemMeta(meta);
        }
        return copy;
    }

    /** 剩余时间格式化：1d 2h / 3h 4m / 2m 30s / 45s / 已结束 */
    public static String formatDuration(long millis) {
        if (millis <= 0) return "0s";
        long s = millis / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 && d == 0) sb.append(m).append("m ");
        if (d == 0 && h == 0 && s > 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}

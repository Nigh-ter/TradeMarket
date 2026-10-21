package com.trademarket.command;

import com.trademarket.TradeMarketPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 聊天输入会话：搜索关键词 / 自定义出价金额。
 * 回调保证在主线程执行；30 秒超时自动取消；等待期间玩家的聊天被吞掉（不外发）。
 */
public final class ChatInputService implements Listener {

    private record Pending(Consumer<String> onMain, BukkitTask timeout) {
    }

    private final TradeMarketPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ChatInputService(TradeMarketPlugin plugin) {
        this.plugin = plugin;
    }

    /** 发起一次输入请求；prompt 为提示语言键，输入完成后在主线程回调。 */
    public void request(Player player, String promptKey, Consumer<String> onMain) {
        cancel(player, false);
        player.sendMessage(plugin.lang().msg(promptKey));
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) != null) {
                player.sendMessage(plugin.lang().msg("common.input-timeout"));
            }
        }, 20L * 30);
        pending.put(player.getUniqueId(), new Pending(onMain, timeout));
    }

    private void cancel(Player player, boolean notify) {
        Pending p = pending.remove(player.getUniqueId());
        if (p != null) {
            p.timeout().cancel();
            if (notify) player.sendMessage(plugin.lang().msg("common.input-cancelled"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        pending.remove(event.getPlayer().getUniqueId());
        p.timeout().cancel();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message())
                .replace("&", "").replace("§", "").trim(); // 剥离色码，防玩家注入 lore/消息格式
        // 空文本也回调（市场搜索用空串清除关键词）
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 回调执行时玩家可能已退出
            if (event.getPlayer().isOnline()) p.onMain().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }
}

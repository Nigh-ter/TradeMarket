package com.trademarket.listener;

import com.trademarket.TradeMarketPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 入服邮箱提醒：延迟 1 秒发送 —— 避免被 AuthMe 等登录插件
 * 在登录完成前屏蔽聊天消息。
 */
public final class JoinListener implements Listener {

    private final TradeMarketPlugin plugin;

    public JoinListener(TradeMarketPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> {
                    if (event.getPlayer().isOnline()) {
                        plugin.mail().notifyJoin(event.getPlayer());
                    }
                },
                20L);
    }
}

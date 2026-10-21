package com.trademarket.economy;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * LuckPerms 软依赖：读取玩家主组（求购单上限等分组差异化配置用）。
 * 未安装 LuckPerms 或用户未加载时返回 null，回退全局默认值。
 */
public final class LuckPermsHook {

    private LuckPerms api;

    public void init() {
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
                api = LuckPermsProvider.get();
            }
        } catch (Throwable t) {
            api = null;
        }
    }

    public boolean available() {
        return api != null;
    }

    /** 用户全部继承组名（含 default 与叠加组）；不可用返回空列表。 */
    public java.util.List<String> groups(UUID uuid) {
        if (api == null) return java.util.List.of();
        try {
            User user = api.getUserManager().getUser(uuid);
            if (user == null) return java.util.List.of();
            java.util.List<String> out = new java.util.ArrayList<>();
            for (net.luckperms.api.model.group.Group g : user.getInheritedGroups(user.getQueryOptions())) {
                out.add(g.getName());
            }
            return out;
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /** 用户主组；未加载/不可用返回 null。 */
    public String primaryGroup(UUID uuid) {
        if (api == null) return null;
        try {
            User user = api.getUserManager().getUser(uuid);
            return user == null ? null : user.getPrimaryGroup();
        } catch (Throwable t) {
            return null;
        }
    }
}

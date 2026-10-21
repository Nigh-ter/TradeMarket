package com.trademarket.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Vault 经济对接。所有调用必须在主线程（多数经济插件不保证线程安全）。
 * 内部金额为"分"，仅在 Vault 边界转换为 double。
 */
public final class EconomyHook {

    private Economy econ;
    private String backendName = "N/A";
    public final LuckPermsHook luckPerms = new LuckPermsHook();

    public void init() {
        luckPerms.init();
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            econ = rsp.getProvider();
            backendName = econ.getName();
        }
    }

    public boolean available() {
        return econ != null;
    }

    public String backendName() {
        return backendName;
    }

    public long balanceCents(OfflinePlayer p) {
        if (econ == null) return 0;
        return com.trademarket.util.Money.fromVault(econ.getBalance(p));
    }

    public boolean has(OfflinePlayer p, long cents) {
        if (econ == null) return false;
        return econ.has(p, com.trademarket.util.Money.toVault(cents));
    }

    /** 扣款；失败返回 false（调用方负责中止流程并提示）。 */
    public boolean withdraw(OfflinePlayer p, long cents) {
        if (econ == null || cents <= 0) return econ != null;
        EconomyResponse r = econ.withdrawPlayer(p, com.trademarket.util.Money.toVault(cents));
        return r.transactionSuccess();
    }

    /** 入账；失败返回 false。 */
    public boolean deposit(OfflinePlayer p, long cents) {
        if (econ == null || cents <= 0) return econ != null;
        EconomyResponse r = econ.depositPlayer(p, com.trademarket.util.Money.toVault(cents));
        return r.transactionSuccess();
    }
}

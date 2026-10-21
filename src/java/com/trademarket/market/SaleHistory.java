package com.trademarket.market;

import com.trademarket.data.Database;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 成交历史（内存）：每个材质保留最近 50 次成交单价，用于
 * 市场定价参考（lore 显示近期均价）与上架防手滑确认。
 */
public final class SaleHistory {

    private static final int KEEP = 50;

    private final Map<String, ArrayDeque<Long>> byMaterial = new HashMap<>();

    /** 启动时从数据库装载最近成交。 */
    public void load(Database db, int limit) {
        for (Database.SaleRow row : db.loadRecentSales(limit)) {
            record(row.material(), row.unitPrice());
        }
    }

    public void record(String material, long unitPriceCents) {
        ArrayDeque<Long> deque = byMaterial.computeIfAbsent(material, k -> new ArrayDeque<>());
        deque.addFirst(unitPriceCents);
        while (deque.size() > KEEP) deque.removeLast();
    }

    /** 近期成交均价；无记录返回 -1。 */
    public long average(String material) {
        ArrayDeque<Long> deque = byMaterial.get(material);
        if (deque == null || deque.isEmpty()) return -1;
        long sum = 0;
        for (long p : deque) sum += p;
        return sum / deque.size();
    }
}

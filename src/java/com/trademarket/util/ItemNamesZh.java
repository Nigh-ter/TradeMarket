package com.trademarket.util;

import org.bukkit.Material;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 材质中文名映射（数据来自 Minecraft 1.21.11 官方 zh_cn 语言文件，构建期烘焙进 jar）。
 * 用途：中文关键词搜索市场商品。
 */
public final class ItemNamesZh {

    private static volatile Map<String, String> map;

    private ItemNamesZh() {
    }

    private static Map<String, String> load() {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ItemNamesZh.class.getResourceAsStream("/itemnames/zh_CN.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                m.put(line.substring(0, i), line.substring(i + 1));
            }
        } catch (Exception e) {
            // 资源缺失时中文搜索退化为英文匹配，不影响其他功能
        }
        return m;
    }

    /** 中文名反查材质（精确匹配）；未找到返回 null。 */
    public static Material fromZh(String zhName) {
        Map<String, String> m = map;
        if (m == null) {
            synchronized (ItemNamesZh.class) {
                if (map == null) map = load();
                m = map;
            }
        }
        for (var en : m.entrySet()) {
            if (en.getValue().equals(zhName)) {
                try {
                    return Material.matchMaterial(en.getKey());
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** 材质中文名；映射缺失返回 null。 */
    public static String zh(Material material) {
        Map<String, String> m = map;
        if (m == null) {
            synchronized (ItemNamesZh.class) {
                if (map == null) map = load();
                m = map;
            }
        }
        return m.get(material.name());
    }
}

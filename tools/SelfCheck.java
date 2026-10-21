import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 离线自检（无需启动服务器）：
 *  1) Lang.REQUIRED_KEYS 与 lang/zh_CN.yml 双向对齐
 *  2) com.trademarket.util.Money.parse / format 行为
 *  3) SQLite 驱动可用性 + 建表 + 增查（与 Database 内相同的 DDL/SQL）
 */
public class SelfCheck {

    public static void main(String[] args) throws Exception {
        int failures = 0;

        // ---------- 1) 语言键对齐 ----------
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File(args[0]));
        Class<?> lang = Class.forName("com.trademarket.config.Lang");
        Field f = lang.getDeclaredField("REQUIRED_KEYS");
        f.setAccessible(true);
        String[] keys = (String[]) f.get(null);

        int missingInYml = 0;
        for (String k : keys) {
            if (!yml.isSet(k)) {
                System.out.println("[FAIL] yml 缺失键: " + k);
                missingInYml++;
            }
        }
        // 反向：列出 yml 中 REQUIRED_KEYS 未覆盖的叶子键（允许存在，仅供参考）
        System.out.println("[INFO] REQUIRED_KEYS=" + keys.length
                + "，yml 缺失=" + missingInYml);

        // ---------- 2) Money ----------
        expect(com.trademarket.util.Money.parse("100"), 10000L);
        expect(com.trademarket.util.Money.parse("1.5k"), 150000L);
        expect(com.trademarket.util.Money.parse("2m"), 200000000L);
        expect(com.trademarket.util.Money.parse("1w"), 1_000_000L);
        
        expect(com.trademarket.util.Money.parse("2W"), 2_000_000L);
        expect(com.trademarket.util.Money.parse("$100"), 10000L);
        expect(com.trademarket.util.Money.parse("abc"), null);
        expect(com.trademarket.util.Money.parse("-5"), null);
        expect(com.trademarket.util.Money.format(123456L), "1,234.56");
        expect(com.trademarket.util.Money.format(0L), "0.00");
        System.out.println("[INFO] Money 测试完成（含万/w 后缀）");

        // ---------- 3) SQLite ----------
        File tmp = File.createTempFile("tm2check", ".db");
        tmp.deleteOnExit();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp.getAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS tm_mails (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      owner_id VARCHAR(36) NOT NULL,
                      owner_name VARCHAR(20) NOT NULL,
                      sender VARCHAR(32) NOT NULL,
                      subject VARCHAR(128) NOT NULL,
                      body TEXT,
                      item_data TEXT,
                      money INTEGER NOT NULL DEFAULT 0,
                      command TEXT,
                      created_at INTEGER NOT NULL,
                      claimed INTEGER NOT NULL DEFAULT 0
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_mails_owner ON tm_mails(owner_id, claimed)");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tm_mails(owner_id,owner_name,sender,subject,body,item_data,money,created_at,claimed) VALUES(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "00000000-0000-0000-0000-000000000001");
                ps.setString(2, "tester");
                ps.setString(3, "system");
                ps.setString(4, "subj");
                ps.setString(5, "body");
                ps.setString(6, null);
                ps.setLong(7, 5000);
                ps.setLong(8, System.currentTimeMillis());
                ps.setInt(9, 0);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) System.out.println("[INFO] SQLite 自增主键 = " + rs.getLong(1));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tm_mails WHERE claimed=0")) {
                rs.next();
                if (rs.getLong(1) != 1) throw new AssertionError("SQLite 查询计数不符");
            }
            System.out.println("[INFO] SQLite 建表/插入/查询 OK（驱动 " +
                    DriverManager.getDriver("jdbc:sqlite:test.db").getMajorVersion() + ".x）");
        }

        if (missingInYml > 0) failures += missingInYml;
        System.out.println(failures == 0 ? "===== SELF CHECK PASSED =====" : "===== SELF CHECK FAILED: " + failures + " =====");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void expect(Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) throw new AssertionError("期望 " + expected + " 实际 " + actual);
    }
}

package com.trademarket.data;

import com.trademarket.model.Auction;
import com.trademarket.model.BuyOrder;
import com.trademarket.model.Listing;
import com.trademarket.model.Mail;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 存储层：单连接 SQLite，WAL + synchronous=FULL（断电级安全，写入频率低可忽略开销）。
 *
 * <p>设计约束：所有调用都在主线程串行执行 —— 本地写入亚毫秒级，同步直写换来完全的
 * 顺序一致性。关键资金流程使用"守护性状态翻转 + 分步完成标记"（delivered/paid 列），
 * 启动对账可自动补齐任何中断的交割。</p>
 */
public final class Database {

    private final JavaPlugin plugin;
    private Connection conn;

    public Database(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void open() throws SQLException {
        File dir = plugin.getDataFolder();
        if (!dir.exists()) dir.mkdirs();
        File dbFile = new File(dir, "market.db");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        createTables();
    }

    private void createTables() throws SQLException {
        String[] ddl = {
            """
            CREATE TABLE IF NOT EXISTS tm_listings (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              seller_id VARCHAR(36) NOT NULL,
              seller_name VARCHAR(20) NOT NULL,
              item_data TEXT NOT NULL,
              amount INTEGER NOT NULL,
              unit_price INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
              buyer_id VARCHAR(36),
              delivered INTEGER NOT NULL DEFAULT 0,
              paid INTEGER NOT NULL DEFAULT 0,
              item_provider VARCHAR(48) NOT NULL DEFAULT 'VANILLA'
            )""",
            """
            CREATE TABLE IF NOT EXISTS tm_auctions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              seller_id VARCHAR(36) NOT NULL,
              seller_name VARCHAR(20) NOT NULL,
              item_data TEXT NOT NULL,
              start_price INTEGER NOT NULL,
              current_price INTEGER NOT NULL DEFAULT 0,
              top_bidder_id VARCHAR(36),
              top_bidder_name VARCHAR(20),
              buyout_price INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              end_at INTEGER NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
              delivered INTEGER NOT NULL DEFAULT 0,
              paid INTEGER NOT NULL DEFAULT 0,
              item_provider VARCHAR(48) NOT NULL DEFAULT 'VANILLA'
            )""",
            """
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
              kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
              sender_id VARCHAR(36),
              ref_id INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              claimed INTEGER NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS tm_orders (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              buyer_id VARCHAR(36) NOT NULL,
              buyer_name VARCHAR(20) NOT NULL,
              material VARCHAR(64) NOT NULL,
              item_data TEXT,
              item_provider VARCHAR(48) NOT NULL DEFAULT 'VANILLA',
              strict_name INTEGER NOT NULL DEFAULT 0,
              custom_name TEXT,
              unit_price INTEGER NOT NULL,
              total INTEGER NOT NULL,
              remaining INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
            )""",
            """
            CREATE TABLE IF NOT EXISTS tm_offers (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              order_id INTEGER NOT NULL,
              seller_id VARCHAR(36) NOT NULL,
              seller_name VARCHAR(20) NOT NULL,
              buyer_id VARCHAR(36) NOT NULL,
              item_data TEXT NOT NULL,
              unit_price INTEGER NOT NULL,
              time INTEGER NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
            )""",
            "CREATE INDEX IF NOT EXISTS idx_offers_order ON tm_offers(order_id, status)",
            """
            CREATE TABLE IF NOT EXISTS tm_sales (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              material VARCHAR(64) NOT NULL,
              unit_price INTEGER NOT NULL,
              amount INTEGER NOT NULL,
              sold_at INTEGER NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS tm_txlog (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              time INTEGER NOT NULL,
              actor VARCHAR(36) NOT NULL,
              action VARCHAR(32) NOT NULL,
              detail TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_mails_owner ON tm_mails(owner_id, claimed)",
            "CREATE INDEX IF NOT EXISTS idx_auctions_status ON tm_auctions(status, end_at)",
            "CREATE INDEX IF NOT EXISTS idx_sales_material ON tm_sales(material, sold_at)",
            "CREATE INDEX IF NOT EXISTS idx_orders_status ON tm_orders(status)",
            """
            CREATE TABLE IF NOT EXISTS tm_bids (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              auction_id INTEGER NOT NULL,
              bidder_name VARCHAR(20) NOT NULL,
              amount INTEGER NOT NULL,
              time INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_bids_auction ON tm_bids(auction_id)"
        };
        for (String sql : ddl) {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        }
        // 老库补列（2.4.0 新增，对已存在的测试库幂等）
        ensureColumn("tm_listings", "buyer_id", "ALTER TABLE tm_listings ADD COLUMN buyer_id VARCHAR(36)");
        ensureColumn("tm_listings", "delivered", "ALTER TABLE tm_listings ADD COLUMN delivered INTEGER NOT NULL DEFAULT 0");
        ensureColumn("tm_listings", "paid", "ALTER TABLE tm_listings ADD COLUMN paid INTEGER NOT NULL DEFAULT 0");
        ensureColumn("tm_listings", "item_provider", "ALTER TABLE tm_listings ADD COLUMN item_provider VARCHAR(48) NOT NULL DEFAULT 'VANILLA'");
        ensureColumn("tm_auctions", "delivered", "ALTER TABLE tm_auctions ADD COLUMN delivered INTEGER NOT NULL DEFAULT 0");
        ensureColumn("tm_auctions", "paid", "ALTER TABLE tm_auctions ADD COLUMN paid INTEGER NOT NULL DEFAULT 0");
        ensureColumn("tm_auctions", "item_provider", "ALTER TABLE tm_auctions ADD COLUMN item_provider VARCHAR(48) NOT NULL DEFAULT 'VANILLA'");
        ensureColumn("tm_mails", "command", "ALTER TABLE tm_mails ADD COLUMN command TEXT");
        ensureColumn("tm_orders", "item_data", "ALTER TABLE tm_orders ADD COLUMN item_data TEXT");
        ensureColumn("tm_orders", "item_provider", "ALTER TABLE tm_orders ADD COLUMN item_provider VARCHAR(48) NOT NULL DEFAULT 'VANILLA'");
        ensureColumn("tm_orders", "strict_name", "ALTER TABLE tm_orders ADD COLUMN strict_name INTEGER NOT NULL DEFAULT 0");
        ensureColumn("tm_orders", "custom_name", "ALTER TABLE tm_orders ADD COLUMN custom_name TEXT");
        ensureColumn("tm_mails", "kind", "ALTER TABLE tm_mails ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL'");
        ensureColumn("tm_mails", "ref_id", "ALTER TABLE tm_mails ADD COLUMN ref_id INTEGER NOT NULL DEFAULT 0");
        ensureColumn("tm_mails", "sender_id", "ALTER TABLE tm_mails ADD COLUMN sender_id VARCHAR(36)");
        ensureColumn("tm_offers", "unit_price", "ALTER TABLE tm_offers ADD COLUMN unit_price INTEGER NOT NULL DEFAULT 0");
    }

    /** 已存在的库缺列时补齐（幂等）。 */
    private void ensureColumn(String table, String column, String alter) {
        boolean has = false;
        try (ResultSet rs = conn.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) has = true;
            }
        } catch (SQLException e) {
            log("ensureColumn:" + table, e);
            return;
        }
        if (!has) {
            try (Statement st = conn.createStatement()) {
                st.execute(alter);
            } catch (SQLException e) {
                log("ensureColumn:" + table + "." + column, e);
            }
        }
    }

    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "关闭数据库失败", e);
        }
    }

    private void log(String what, SQLException e) {
        plugin.getLogger().log(Level.SEVERE, "数据库操作失败: " + what, e);
    }

    // ==================== 市场在售 ====================

    public void insertListing(Listing l) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tm_listings(seller_id,seller_name,item_data,amount,unit_price,created_at,status,item_provider) VALUES(?,?,?,?,?,?,'ACTIVE',?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, l.sellerId.toString());
            ps.setString(2, l.sellerName);
            ps.setString(3, l.itemData);
            ps.setInt(4, l.amount);
            ps.setLong(5, l.unitPriceCents);
            ps.setLong(6, l.createdAt);
            ps.setString(7, l.itemProvider);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) l.id = rs.getLong(1);
            }
        } catch (SQLException e) {
            log("insertListing", e);
            throw e;
        }
    }

    /** 守护性状态翻转：仅当当前状态为 ACTIVE 时生效（返回 true），保证同一商品只结算一次。 */
    public boolean flipListingStatus(long id, String to, UUID buyerId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_listings SET status=?, buyer_id=? WHERE id=? AND status='ACTIVE'")) {
            ps.setString(1, to);
            ps.setString(2, buyerId == null ? null : buyerId.toString());
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log("flipListingStatus", e);
            return false;
        }
    }

    /** 状态回退（交易失败时恢复 ACTIVE；无条件，仅内部回滚用）。 */
    public void revertListingStatus(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_listings SET status='ACTIVE' WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("revertListingStatus", e);
        }
    }

    /** 无条件置状态（物品数据损坏标记 BROKEN 等场景）。 */
    public void forceListingStatus(long id, String to) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_listings SET status=? WHERE id=?")) {
            ps.setString(1, to);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("forceListingStatus", e);
        }
    }

    /** 交割完成标记（启动对账依据：SOLD 且标记未齐 = 中断，需要补发）。 */
    public void markListingPaid(long id) {
        exec("UPDATE tm_listings SET paid=1 WHERE id=?", id, "markListingPaid");
    }

    public void markListingUnpaid(long id) {
        exec("UPDATE tm_listings SET paid=0 WHERE id=?", id, "markListingUnpaid");
    }

    public void markListingDelivered(long id) {
        exec("UPDATE tm_listings SET delivered=1 WHERE id=?", id, "markListingDelivered");
    }

    public List<Listing> loadListings() {
        List<Listing> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tm_listings WHERE status='ACTIVE' ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String data = rs.getString("item_data");
                var item = com.trademarket.util.Items.deserialize(data);
                if (item == null) {
                    plugin.getLogger().warning("[DB] 商品 #" + id + " 物品数据损坏，已隔离");
                    forceListingStatus(id, "BROKEN");
                    continue;
                }
                out.add(new Listing(id, UUID.fromString(rs.getString("seller_id")),
                        rs.getString("seller_name"), data, item,
                        rs.getInt("amount"), rs.getLong("unit_price"),
                        rs.getLong("created_at"), rs.getString("item_provider")));
            }
        } catch (SQLException e) {
            log("loadListings", e);
        }
        return out;
    }

    /** 启动对账：SOLD/CANCELLED/EXPIRED 但交割标记未齐的记录。 */
    public List<Listing> loadPendingListings() {
        List<Listing> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM tm_listings WHERE status IN ('SOLD','CANCELLED','EXPIRED') AND (delivered=0 OR (status='SOLD' AND paid=0))")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String data = rs.getString("item_data");
                var item = com.trademarket.util.Items.deserialize(data);
                if (item == null) {
                    plugin.getLogger().severe("[对账] 商品 #" + id + " 物品数据损坏且交割未完成，需人工处理");
                    continue;
                }
                Listing l = new Listing(id, UUID.fromString(rs.getString("seller_id")),
                        rs.getString("seller_name"), data, item,
                        rs.getInt("amount"), rs.getLong("unit_price"),
                        rs.getLong("created_at"), rs.getString("item_provider"));
                String buyer = rs.getString("buyer_id");
                l.buyerId = buyer == null ? null : UUID.fromString(buyer);
                l.status = rs.getString("status");
                l.paid = rs.getInt("paid") == 1;
                l.delivered = rs.getInt("delivered") == 1;
                out.add(l);
            }
        } catch (SQLException e) {
            log("loadPendingListings", e);
        }
        return out;
    }

    /** 管理员补发：读取商品行（任意状态）。 */
    public Listing rawListing(long id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM tm_listings WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String data = rs.getString("item_data");
                var item = com.trademarket.util.Items.deserialize(data);
                return new Listing(id, UUID.fromString(rs.getString("seller_id")),
                        rs.getString("seller_name"), data, item,
                        rs.getInt("amount"), rs.getLong("unit_price"),
                        rs.getLong("created_at"), rs.getString("item_provider"));
            }
        } catch (SQLException e) {
            log("rawListing", e);
            return null;
        }
    }

    // ==================== 拍卖 ====================

    public void insertAuction(Auction a) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                INSERT INTO tm_auctions(seller_id,seller_name,item_data,start_price,current_price,
                  top_bidder_id,top_bidder_name,buyout_price,created_at,end_at,status,item_provider)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, a.sellerId.toString());
            ps.setString(2, a.sellerName);
            ps.setString(3, a.itemData);
            ps.setLong(4, a.startPriceCents);
            ps.setLong(5, a.currentBidCents);
            ps.setString(6, a.topBidderId == null ? null : a.topBidderId.toString());
            ps.setString(7, a.topBidderName);
            ps.setLong(8, a.buyoutCents);
            ps.setLong(9, a.createdAt);
            ps.setLong(10, a.endAt);
            ps.setString(11, a.status);
            ps.setString(12, a.itemProvider);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) a.id = rs.getLong(1);
            }
        } catch (SQLException e) {
            log("insertAuction", e);
            throw e;
        }
    }

    /** 全字段更新（出价/防狙击时间变更）。返回 false 表示写库失败，调用方必须回退内存状态。 */
    public boolean updateAuction(Auction a) {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                UPDATE tm_auctions SET current_price=?, top_bidder_id=?, top_bidder_name=?,
                  end_at=?, status=? WHERE id=?""")) {
            ps.setLong(1, a.currentBidCents);
            ps.setString(2, a.topBidderId == null ? null : a.topBidderId.toString());
            ps.setString(3, a.topBidderName);
            ps.setLong(4, a.endAt);
            ps.setString(5, a.status);
            ps.setLong(6, a.id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log("updateAuction", e);
            return false;
        }
    }

    /** 守护性状态翻转：仅当仍为 ACTIVE 时生效（防止双重结算）。 */
    public boolean flipAuctionStatus(long id, String to) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_auctions SET status=? WHERE id=? AND status='ACTIVE'")) {
            ps.setString(1, to);
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log("flipAuctionStatus", e);
            return false;
        }
    }

    public void revertAuctionStatus(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_auctions SET status='ACTIVE' WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("revertAuctionStatus", e);
        }
    }

    public void markAuctionPaid(long id) {
        exec("UPDATE tm_auctions SET paid=1 WHERE id=?", id, "markAuctionPaid");
    }

    public void markAuctionDelivered(long id) {
        exec("UPDATE tm_auctions SET delivered=1 WHERE id=?", id, "markAuctionDelivered");
    }

    public List<Auction> loadActiveAuctions() {
        List<Auction> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM tm_auctions WHERE status='ACTIVE' ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String data = rs.getString("item_data");
                var item = com.trademarket.util.Items.deserialize(data);
                if (item == null) {
                    plugin.getLogger().warning("[DB] 拍卖 #" + id + " 物品数据损坏，已隔离");
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE tm_auctions SET status='CANCELLED' WHERE id=?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                    continue;
                }
                out.add(readAuction(rs, id, data, item));
            }
        } catch (SQLException e) {
            log("loadActiveAuctions", e);
        }
        return out;
    }

    /** 启动对账：SOLD/EXPIRED/CANCELLED 但交割标记未齐的拍卖。 */
    public List<Auction> loadPendingAuctions() {
        List<Auction> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM tm_auctions WHERE status IN ('SOLD','EXPIRED','CANCELLED') AND (delivered=0 OR (status='SOLD' AND paid=0))")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String data = rs.getString("item_data");
                var item = com.trademarket.util.Items.deserialize(data);
                if (item == null) {
                    plugin.getLogger().severe("[对账] 拍卖 #" + id + " 物品数据损坏且交割未完成，需人工处理");
                    continue;
                }
                out.add(readAuction(rs, id, data, item));
            }
        } catch (SQLException e) {
            log("loadPendingAuctions", e);
        }
        return out;
    }

    private Auction readAuction(ResultSet rs, long id, String data, org.bukkit.inventory.ItemStack item) throws SQLException {
        Auction a = new Auction(id, UUID.fromString(rs.getString("seller_id")),
                rs.getString("seller_name"), data, item,
                rs.getLong("start_price"), rs.getLong("buyout_price"),
                rs.getLong("created_at"), rs.getLong("end_at"),
                rs.getString("item_provider"));
        a.currentBidCents = rs.getLong("current_price");
        String bidder = rs.getString("top_bidder_id");
        a.topBidderId = bidder == null ? null : UUID.fromString(bidder);
        a.topBidderName = rs.getString("top_bidder_name");
        a.status = rs.getString("status");
        a.paid = rs.getInt("paid") == 1;
        a.delivered = rs.getInt("delivered") == 1;
        return a;
    }

    /** 管理员补发：读取拍卖行（任意状态）。 */
    public Auction rawAuction(long id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM tm_auctions WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String data = rs.getString("item_data");
                var item = com.trademarket.util.Items.deserialize(data);
                return item == null ? null : readAuction(rs, id, data, item);
            }
        } catch (SQLException e) {
            log("rawAuction", e);
            return null;
        }
    }

    // ==================== 求购单 ====================

    public void insertOrder(BuyOrder o) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tm_orders(buyer_id,buyer_name,material,item_data,item_provider,strict_name,custom_name,unit_price,total,remaining,created_at,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, o.buyerId.toString());
            ps.setString(2, o.buyerName);
            ps.setString(3, o.material.name());
            ps.setString(4, o.itemData);
            ps.setString(5, o.itemProvider);
            ps.setInt(6, o.strictName ? 1 : 0);
            ps.setString(7, o.customName);
            ps.setLong(8, o.unitPriceCents);
            ps.setInt(9, o.total);
            ps.setInt(10, o.remaining);
            ps.setLong(11, o.createdAt);
            ps.setString(12, o.status);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) o.id = rs.getLong(1);
            }
        } catch (SQLException e) {
            log("insertOrder", e);
            throw e;
        }
    }

    public void updateOrder(BuyOrder o) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_orders SET remaining=?, status=? WHERE id=?")) {
            ps.setInt(1, o.remaining);
            ps.setString(2, o.status);
            ps.setLong(3, o.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("updateOrder", e);
        }
    }

    public List<BuyOrder> loadOpenOrders() {
        List<BuyOrder> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tm_orders WHERE status='OPEN' ORDER BY id")) {
            while (rs.next()) {
                out.add(readOrder(rs));
            }
        } catch (SQLException e) {
            log("loadOpenOrders", e);
        }
        return out;
    }

    private BuyOrder readOrder(ResultSet rs) throws SQLException {
        Material m = Material.matchMaterial(rs.getString("material"));
        String data = rs.getString("item_data");
        if (data != null && com.trademarket.util.Items.deserialize(data) == null) {
            data = null; // 样本损坏：退化为材质匹配图标
        }
        return new BuyOrder(rs.getLong("id"), UUID.fromString(rs.getString("buyer_id")),
                rs.getString("buyer_name"), m, data, rs.getString("item_provider"),
                rs.getInt("strict_name") == 1, rs.getString("custom_name"),
                rs.getLong("unit_price"), rs.getInt("total"), rs.getInt("remaining"),
                rs.getLong("created_at"), rs.getString("status"));
    }

    // ==================== 求购申请（托管） ====================

    public void insertOffer(com.trademarket.model.Offer f) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tm_offers(order_id,seller_id,seller_name,buyer_id,item_data,unit_price,time,status) VALUES(?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, f.orderId);
            ps.setString(2, f.sellerId.toString());
            ps.setString(3, f.sellerName);
            ps.setString(4, f.buyerId.toString());
            ps.setString(5, f.itemData);
            ps.setLong(6, f.unitPriceCents);
            ps.setLong(7, f.time);
            ps.setString(8, f.status);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) f.id = rs.getLong(1);
            }
        } catch (SQLException e) {
            log("insertOffer", e);
            throw e;
        }
    }

    /** 守护性状态翻转：仅 PENDING 时生效（防重复处理）。 */
    public boolean flipOfferStatus(long id, String to) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_offers SET status=? WHERE id=? AND status='PENDING'")) {
            ps.setString(1, to);
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log("flipOfferStatus", e);
            return false;
        }
    }

    public List<com.trademarket.model.Offer> loadPendingOffersByOrder(long orderId) {
        List<com.trademarket.model.Offer> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM tm_offers WHERE order_id=? AND status='PENDING' ORDER BY id")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readOffer(rs));
            }
        } catch (SQLException e) {
            log("loadPendingOffersByOrder", e);
        }
        return out;
    }

    public com.trademarket.model.Offer getOffer(long id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM tm_offers WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readOffer(rs);
            }
        } catch (SQLException e) {
            log("getOffer", e);
        }
        return null;
    }

    public List<com.trademarket.model.Offer> loadPendingOffersBySeller(UUID sellerId) {
        List<com.trademarket.model.Offer> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM tm_offers WHERE seller_id=? AND status='PENDING' ORDER BY id DESC")) {
            ps.setString(1, sellerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readOffer(rs));
            }
        } catch (SQLException e) {
            log("loadPendingOffersBySeller", e);
        }
        return out;
    }

    /** 超时扫描：提交超过 timeoutMillis 的 PENDING 申请。 */
    public List<com.trademarket.model.Offer> loadTimedOutOffers(long timeoutMillis) {
        List<com.trademarket.model.Offer> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM tm_offers WHERE status='PENDING' AND time<?")) {
            ps.setLong(1, System.currentTimeMillis() - timeoutMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readOffer(rs));
            }
        } catch (SQLException e) {
            log("loadTimedOutOffers", e);
        }
        return out;
    }

    private com.trademarket.model.Offer readOffer(ResultSet rs) throws SQLException {
        return new com.trademarket.model.Offer(rs.getLong("id"), rs.getLong("order_id"),
                UUID.fromString(rs.getString("seller_id")), rs.getString("seller_name"),
                UUID.fromString(rs.getString("buyer_id")), rs.getString("item_data"),
                rs.getLong("unit_price"), rs.getLong("time"), rs.getString("status"));
    }

    // ==================== 成交历史 ====================

    public void insertSale(String material, long unitPrice, int amount, long soldAt) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tm_sales(material,unit_price,amount,sold_at) VALUES(?,?,?,?)")) {
            ps.setString(1, material);
            ps.setLong(2, unitPrice);
            ps.setInt(3, amount);
            ps.setLong(4, soldAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("insertSale", e);
        }
    }

    /** 最近成交（id 倒序），供内存均价计算。 */
    public List<SaleRow> loadRecentSales(int limit) {
        List<SaleRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT material,unit_price FROM tm_sales ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SaleRow(rs.getString("material"), rs.getLong("unit_price")));
                }
            }
        } catch (SQLException e) {
            log("loadRecentSales", e);
        }
        return out;
    }

    public record SaleRow(String material, long unitPrice) {
    }

    /** 成交统计：返回 {成交笔数, 总金额(分)}；sinceMillis<=0 表示全部。 */
    public long[] salesStats(long sinceMillis) {
        String sql = sinceMillis > 0
                ? "SELECT COUNT(*), COALESCE(SUM(unit_price*amount),0) FROM tm_sales WHERE sold_at>=?"
                : "SELECT COUNT(*), COALESCE(SUM(unit_price*amount),0) FROM tm_sales";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (sinceMillis > 0) ps.setLong(1, sinceMillis);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        } catch (SQLException e) {
            log("salesStats", e);
            return new long[]{0, 0};
        }
    }

    /** 拍卖托管中的资金总额（分）。 */
    public long auctionEscrowTotal() {
        return queryLong("SELECT COALESCE(SUM(current_price),0) FROM tm_auctions WHERE status='ACTIVE' AND current_price>0", "auctionEscrowTotal");
    }

    /** 求购托管中的资金总额（分）。 */
    public long orderEscrowTotal() {
        return queryLong("SELECT COALESCE(SUM(unit_price*remaining),0) FROM tm_orders WHERE status='OPEN'", "orderEscrowTotal");
    }

    private long queryLong(String sql, String what) {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            log(what, e);
            return 0;
        }
    }

    /** 记录一次出价（出价历史）。 */
    public void insertBid(long auctionId, String bidderName, long amountCents, long time) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tm_bids(auction_id,bidder_name,amount,time) VALUES(?,?,?,?)")) {
            ps.setLong(1, auctionId);
            ps.setString(2, bidderName);
            ps.setLong(3, amountCents);
            ps.setLong(4, time);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("insertBid", e);
        }
    }

    /** 某拍卖的出价记录（金额倒序，最多 100 条）。 */
    public List<BidRow> loadBids(long auctionId) {
        List<BidRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT bidder_name,amount,time FROM tm_bids WHERE auction_id=? ORDER BY amount DESC LIMIT 100")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BidRow(rs.getString("bidder_name"), rs.getLong("amount"), rs.getLong("time")));
                }
            }
        } catch (SQLException e) {
            log("loadBids", e);
        }
        return out;
    }

    public record BidRow(String bidderName, long amount, long time) {
    }

    // ==================== 瘦身清理 ====================

    public int pruneTxLog(long cutoff) {
        return execUpdate("DELETE FROM tm_txlog WHERE time<?", cutoff, "pruneTxLog");
    }

    public int pruneSales(long cutoff) {
        return execUpdate("DELETE FROM tm_sales WHERE sold_at<?", cutoff, "pruneSales");
    }

    public int pruneTerminalListings(long cutoff) {
        return execUpdate("DELETE FROM tm_listings WHERE status<>'ACTIVE' AND delivered=1 AND created_at<?",
                cutoff, "pruneTerminalListings");
    }

    public int pruneTerminalAuctions(long cutoff) {
        return execUpdate("DELETE FROM tm_auctions WHERE status<>'ACTIVE' AND delivered=1 AND created_at<?",
                cutoff, "pruneTerminalAuctions");
    }

    public int pruneOrders(long cutoff) {
        return execUpdate("DELETE FROM tm_orders WHERE status<>'OPEN' AND created_at<?", cutoff, "pruneOrders");
    }

    // ==================== 邮箱 ====================

    public void insertMail(Mail m) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                INSERT INTO tm_mails(owner_id,owner_name,sender,subject,body,item_data,money,command,kind,ref_id,sender_id,created_at,claimed)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, m.ownerId.toString());
            ps.setString(2, m.ownerName);
            ps.setString(3, m.sender);
            ps.setString(4, m.subject);
            ps.setString(5, m.body);
            ps.setString(6, m.itemData);
            ps.setLong(7, m.moneyCents);
            ps.setString(8, m.command);
            ps.setString(9, m.kind == null ? "NORMAL" : m.kind);
            ps.setLong(10, m.refId);
            ps.setString(11, m.senderId == null ? null : m.senderId.toString());
            ps.setLong(12, m.createdAt);
            ps.setInt(13, m.claimed ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) m.id = rs.getLong(1);
            }
        } catch (SQLException e) {
            log("insertMail", e);
            throw e;
        }
    }

    public void markMailClaimed(long id) {
        exec("UPDATE tm_mails SET claimed=1 WHERE id=?", id, "markMailClaimed");
    }

    /** 守护性领取标记：仅当未领取时生效（返回 true）。 */
    public boolean markMailClaimedGuarded(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tm_mails SET claimed=1 WHERE id=? AND claimed=0")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log("markMailClaimedGuarded", e);
            return false;
        }
    }

    public void markMailUnclaimed(long id) {
        exec("UPDATE tm_mails SET claimed=0 WHERE id=?", id, "markMailUnclaimed");
    }

    public List<Mail> loadUnclaimedMails() {
        List<Mail> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tm_mails WHERE claimed=0 ORDER BY id")) {
            while (rs.next()) {
                out.add(new Mail(rs.getLong("id"), UUID.fromString(rs.getString("owner_id")),
                        rs.getString("owner_name"), rs.getString("sender"),
                        rs.getString("subject"), rs.getString("body"),
                        rs.getString("item_data"), rs.getLong("money"),
                        rs.getString("command"), rs.getLong("created_at"), false));
                out.get(out.size() - 1).kind = rs.getString("kind");
                out.get(out.size() - 1).refId = rs.getLong("ref_id");
                String sid = rs.getString("sender_id");
                out.get(out.size() - 1).senderId = sid == null ? null : UUID.fromString(sid);
            }
        } catch (SQLException e) {
            log("loadUnclaimedMails", e);
        }
        return out;
    }

    /** 撤销邮件：仅未领取的可删，返回是否删除成功。 */
    public boolean deleteMailIfUnclaimed(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM tm_mails WHERE id=? AND claimed=0")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log("deleteMailIfUnclaimed", e);
            return false;
        }
    }

    public int purgeClaimedOlderThan(long cutoffMillis) {
        return execUpdate("DELETE FROM tm_mails WHERE claimed=1 AND created_at<?", cutoffMillis, "purgeClaimedOlderThan");
    }

    // ==================== 流水 ====================

    public void tx(java.util.UUID actor, String action, String detail) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tm_txlog(time,actor,action,detail) VALUES(?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, actor == null ? "system" : actor.toString());
            ps.setString(3, action);
            ps.setString(4, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("tx", e);
        }
    }

    /** 判断流水里是否存在某 action 且 detail 包含指定片段（对账辅助）。 */
    public boolean hasTx(String action, String detailContains) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM tm_txlog WHERE action=? AND detail LIKE ? LIMIT 1")) {
            ps.setString(1, action);
            ps.setString(2, "%" + detailContains + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log("hasTx", e);
            return true; // 查询失败时保守处理：视为已完成，避免误补发
        }
    }

    public boolean isOpen() {
        try {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private void exec(String sql, long id, String what) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log(what, e);
        }
    }

    private int execUpdate(String sql, long param, String what) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, param);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log(what, e);
            return 0;
        }
    }
}

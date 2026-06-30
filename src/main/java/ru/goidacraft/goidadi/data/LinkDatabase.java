package ru.goidacraft.goidadi.data;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GoidaDI's own H2 store for Discord links and pending link codes. Mirrors the design of GoidaAuth's
 * {@code DatabaseManager}: an H2 file inside the world folder ({@code <world>/goidadi/links}), a single
 * daemon executor that serialises every read/write (so concurrent joins/links never corrupt data),
 * and {@link CompletableFuture}-returning methods.
 *
 * <p>Compound, must-be-atomic operations (link confirmation, override, transfer) go through
 * {@link #compute(SqlFunction)} so the whole check-then-write runs as one task on the io thread —
 * there is no TOCTOU window between "are both sides free?" and the insert.
 */
public final class LinkDatabase {
    private static final Logger LOG = LoggerFactory.getLogger(LinkDatabase.class);
    private static final LevelResource ROOT = new LevelResource("goidadi");

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "GoidaDI-DB");
        t.setDaemon(true);
        return t;
    });

    private volatile Connection connection;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized void start(MinecraftServer server) throws Exception {
        Path dir = server.getWorldPath(ROOT);
        Files.createDirectories(dir);
        Path db = dir.resolve("links");

        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 driver missing — jarJar packaging broken", e);
        }

        String url = "jdbc:h2:file:" + db.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        connection = DriverManager.getConnection(url, "sa", "");
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS links (
                    mc_uuid       VARCHAR(36) PRIMARY KEY,
                    mc_name       VARCHAR(32) NOT NULL,
                    discord_id    VARCHAR(32),
                    discord_name  VARCHAR(64),
                    first_seen    TIMESTAMP   NOT NULL,
                    deadline      TIMESTAMP   NOT NULL,
                    linked_at     TIMESTAMP
                )
            """);
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_links_discord ON links(discord_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_links_name ON links(mc_name)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pending_links (
                    code        INT PRIMARY KEY,
                    mc_uuid     VARCHAR(36) NOT NULL,
                    mc_name     VARCHAR(32) NOT NULL,
                    created_at  TIMESTAMP   NOT NULL,
                    is_override BOOLEAN     NOT NULL DEFAULT FALSE
                )
            """);
        }
        LOG.info("GoidaDI DB ready at {}", db);
    }

    public synchronized void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            LOG.warn("Error closing DB", e);
        }
        io.shutdown();
    }

    public boolean isReady() {
        return connection != null;
    }

    // ------------------------------------------------------------------
    // Generic task submission (atomic compound ops live here)
    // ------------------------------------------------------------------

    public <T> CompletableFuture<T> compute(SqlFunction<T> fn) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fn.apply(connection);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, io);
    }

    public CompletableFuture<Void> run(SqlConsumer fn) {
        return CompletableFuture.runAsync(() -> {
            try {
                fn.accept(connection);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, io);
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public CompletableFuture<Optional<LinkRecord>> findByUuid(UUID uuid) {
        return compute(c -> findByUuidSync(c, uuid));
    }

    public CompletableFuture<Optional<LinkRecord>> findByName(String name) {
        return compute(c -> findByNameSync(c, name));
    }

    public CompletableFuture<Optional<LinkRecord>> findByDiscordId(String discordId) {
        return compute(c -> findByDiscordIdSync(c, discordId));
    }

    public static Optional<LinkRecord> findByUuidSync(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM links WHERE mc_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public static Optional<LinkRecord> findByNameSync(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM links WHERE LOWER(mc_name) = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public static Optional<LinkRecord> findByDiscordIdSync(Connection c, String discordId) throws SQLException {
        if (discordId == null) return Optional.empty();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM links WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public CompletableFuture<List<LinkRecord>> listAll(int offset, int limit) {
        return compute(c -> {
            List<LinkRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM links ORDER BY LOWER(mc_name) LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(map(rs));
                }
            }
            return out;
        });
    }

    public CompletableFuture<List<LinkRecord>> listAll() {
        return compute(c -> {
            List<LinkRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM links ORDER BY LOWER(mc_name)");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        });
    }

    public CompletableFuture<Integer> count() {
        return compute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM links");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    public CompletableFuture<List<LinkRecord>> search(String query) {
        return compute(c -> {
            String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
            List<LinkRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM links WHERE LOWER(mc_name) LIKE ? OR LOWER(discord_name) LIKE ? " +
                            "OR discord_id = ? ORDER BY LOWER(mc_name) LIMIT 50")) {
                ps.setString(1, like);
                ps.setString(2, like);
                ps.setString(3, query);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(map(rs));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Inserts an unlinked tracking row if none exists; returns the (existing or new) record. */
    public static LinkRecord ensureRowSync(Connection c, UUID uuid, String name,
                                           Instant firstSeen, Instant deadline) throws SQLException {
        Optional<LinkRecord> existing = findByUuidSync(c, uuid);
        if (existing.isPresent()) {
            // keep the name fresh
            if (!existing.get().mcName().equals(name)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE links SET mc_name = ? WHERE mc_uuid = ?")) {
                    ps.setString(1, name);
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                }
                return findByUuidSync(c, uuid).orElse(existing.get());
            }
            return existing.get();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO links (mc_uuid, mc_name, first_seen, deadline) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setTimestamp(3, Timestamp.from(firstSeen));
            ps.setTimestamp(4, Timestamp.from(deadline));
            ps.executeUpdate();
        }
        return findByUuidSync(c, uuid).orElseThrow();
    }

    /** Attaches (or replaces) the Discord side of an existing or new row. */
    public static void setLinkSync(Connection c, UUID uuid, String name, String discordId,
                                   String discordName, Instant firstSeen, Instant deadline) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO links (mc_uuid, mc_name, discord_id, discord_name, first_seen, deadline, linked_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE mc_name = VALUES(mc_name), discord_id = VALUES(discord_id), " +
                        "discord_name = VALUES(discord_name), linked_at = VALUES(linked_at)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, discordId);
            ps.setString(4, discordName);
            ps.setTimestamp(5, Timestamp.from(firstSeen));
            ps.setTimestamp(6, Timestamp.from(deadline));
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    /** Removes only the Discord side, keeping the deadline tracking row. */
    public static boolean clearLinkSync(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE links SET discord_id = NULL, discord_name = NULL, linked_at = NULL WHERE mc_uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public static boolean deleteByUuidSync(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM links WHERE mc_uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public static void setDeadlineSync(Connection c, UUID uuid, Instant deadline) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE links SET deadline = ? WHERE mc_uuid = ?")) {
            ps.setTimestamp(1, Timestamp.from(deadline));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public static void resetDeadlineSync(Connection c, UUID uuid, Instant firstSeen, Instant deadline)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE links SET first_seen = ?, deadline = ? WHERE mc_uuid = ?")) {
            ps.setTimestamp(1, Timestamp.from(firstSeen));
            ps.setTimestamp(2, Timestamp.from(deadline));
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
    }

    public CompletableFuture<Boolean> deleteByUuid(UUID uuid) {
        return compute(c -> deleteByUuidSync(c, uuid));
    }

    // ------------------------------------------------------------------
    // Pending link codes
    // ------------------------------------------------------------------

    public static void savePendingSync(Connection c, PendingLink p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pending_links (code, mc_uuid, mc_name, created_at, is_override) VALUES (?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE mc_uuid = VALUES(mc_uuid), mc_name = VALUES(mc_name), " +
                        "created_at = VALUES(created_at), is_override = VALUES(is_override)")) {
            ps.setInt(1, p.code());
            ps.setString(2, p.mcUuid().toString());
            ps.setString(3, p.mcName());
            ps.setTimestamp(4, Timestamp.from(p.createdAt()));
            ps.setBoolean(5, p.override());
            ps.executeUpdate();
        }
    }

    /** Removes any existing pending codes for this player (one active code per player). */
    public static void deletePendingByUuidSync(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM pending_links WHERE mc_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    public static Optional<PendingLink> findPendingByCodeSync(Connection c, int code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pending_links WHERE code = ?")) {
            ps.setInt(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new PendingLink(
                        rs.getInt("code"),
                        UUID.fromString(rs.getString("mc_uuid")),
                        rs.getString("mc_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("is_override")));
            }
        }
    }

    public static boolean codeExistsSync(Connection c, int code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pending_links WHERE code = ?")) {
            ps.setInt(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void deletePendingByCodeSync(Connection c, int code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM pending_links WHERE code = ?")) {
            ps.setInt(1, code);
            ps.executeUpdate();
        }
    }

    public static int purgeExpiredPendingSync(Connection c, Instant cutoff) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM pending_links WHERE created_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        }
    }

    public CompletableFuture<Integer> purgeExpiredPending(Instant cutoff) {
        return compute(c -> purgeExpiredPendingSync(c, cutoff));
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private static LinkRecord map(ResultSet rs) throws SQLException {
        Timestamp linkedAt = rs.getTimestamp("linked_at");
        return new LinkRecord(
                UUID.fromString(rs.getString("mc_uuid")),
                rs.getString("mc_name"),
                rs.getString("discord_id"),
                rs.getString("discord_name"),
                rs.getTimestamp("first_seen").toInstant(),
                rs.getTimestamp("deadline").toInstant(),
                linkedAt == null ? null : linkedAt.toInstant());
    }
}

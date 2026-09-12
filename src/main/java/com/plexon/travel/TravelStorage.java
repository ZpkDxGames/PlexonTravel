package com.plexon.travel;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class TravelStorage implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Path databasePath;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PlexonTravel-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;

    TravelStorage(JavaPlugin plugin, Path databasePath) {
        this.plugin = plugin;
        this.databasePath = databasePath;
    }

    Path path() { return databasePath; }

    synchronized void open() throws Exception {
        Files.createDirectories(databasePath.getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS destinations(id TEXT PRIMARY KEY, world_uuid TEXT NOT NULL, world_name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, updated_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS warps(id TEXT PRIMARY KEY, display_name TEXT NOT NULL, world_uuid TEXT NOT NULL, world_name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, enabled INTEGER NOT NULL, permission TEXT NOT NULL, permission_required INTEGER NOT NULL, sort_order INTEGER NOT NULL, icon TEXT NOT NULL, category TEXT NOT NULL, revision INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS back_locations(player_uuid TEXT PRIMARY KEY, world_uuid TEXT NOT NULL, world_name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, source TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS migration_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    synchronized Map<String, Destination> loadDestinations() throws SQLException {
        Map<String, Destination> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM destinations")) {
            while (rs.next()) result.put(rs.getString("id"), destination(rs));
        }
        return result;
    }

    synchronized Map<String, Warp> loadWarps() throws SQLException {
        Map<String, Warp> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM warps")) {
            while (rs.next()) {
                Destination destination = destination(rs);
                Warp warp = new Warp(rs.getString("id"), rs.getString("display_name"), destination,
                    rs.getInt("enabled") != 0, rs.getString("permission"), rs.getInt("permission_required") != 0,
                    rs.getInt("sort_order"), rs.getString("icon"), rs.getString("category"), rs.getLong("revision"));
                result.put(warp.id(), warp);
            }
        }
        return result;
    }

    synchronized Map<UUID, BackEntry> loadBack() throws SQLException {
        Map<UUID, BackEntry> result = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM back_locations")) {
            while (rs.next()) {
                result.put(UUID.fromString(rs.getString("player_uuid")),
                    new BackEntry(destination(rs), rs.getString("source"), rs.getLong("updated_at")));
            }
        }
        return result;
    }

    synchronized void backup(Path target) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(FULL)");
        }
        Files.copy(databasePath, target, StandardCopyOption.REPLACE_EXISTING);
    }

    void saveDestinationAsync(String id, Destination destination) {
        submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO destinations(id,world_uuid,world_name,x,y,z,yaw,pitch,updated_at) VALUES(?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,updated_at=excluded.updated_at")) {
                ps.setString(1, id);
                bindDestination(ps, destination, 2);
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    void saveWarpAsync(Warp warp) {
        submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO warps(id,display_name,world_uuid,world_name,x,y,z,yaw,pitch,enabled,permission,permission_required,sort_order,icon,category,revision,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,enabled=excluded.enabled,permission=excluded.permission,permission_required=excluded.permission_required,sort_order=excluded.sort_order,icon=excluded.icon,category=excluded.category,revision=excluded.revision,updated_at=excluded.updated_at")) {
                ps.setString(1, warp.id());
                ps.setString(2, warp.displayName());
                bindDestination(ps, warp.destination(), 3);
                ps.setInt(10, warp.enabled() ? 1 : 0);
                ps.setString(11, warp.permission());
                ps.setInt(12, warp.permissionRequired() ? 1 : 0);
                ps.setInt(13, warp.sortOrder());
                ps.setString(14, warp.icon());
                ps.setString(15, warp.category());
                ps.setLong(16, warp.revision());
                ps.setLong(17, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    void deleteWarpAsync(String id) {
        submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM warps WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        });
    }

    void saveBackAsync(UUID playerId, BackEntry entry) {
        submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO back_locations(player_uuid,world_uuid,world_name,x,y,z,yaw,pitch,source,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,source=excluded.source,updated_at=excluded.updated_at")) {
                ps.setString(1, playerId.toString());
                bindDestination(ps, entry.destination(), 2);
                ps.setString(9, entry.source());
                ps.setLong(10, entry.updatedAt());
                ps.executeUpdate();
            }
        });
    }

    private Destination destination(ResultSet rs) throws SQLException {
        return new Destination(UUID.fromString(rs.getString("world_uuid")), rs.getString("world_name"),
            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"));
    }

    private void bindDestination(PreparedStatement ps, Destination destination, int index) throws SQLException {
        ps.setString(index, destination.worldId().toString());
        ps.setString(index + 1, destination.worldName());
        ps.setDouble(index + 2, destination.x());
        ps.setDouble(index + 3, destination.y());
        ps.setDouble(index + 4, destination.z());
        ps.setFloat(index + 5, destination.yaw());
        ps.setFloat(index + 6, destination.pitch());
    }

    private void submit(SqlWork work) {
        io.execute(() -> {
            try {
                synchronized (this) { work.run(); }
            } catch (Exception failure) {
                plugin.getLogger().log(Level.SEVERE, "Travel persistence write failed", failure);
            }
        });
    }

    @Override
    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) { }
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork { void run() throws Exception; }
}

package com.emre.linewatch.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class EventRepository {

    private final Database db;

    public EventRepository(Database db) {
        this.db = db;
    }

    public synchronized LineEvent insert(LineEvent.Type type, String detail, long durationSec) throws SQLException {
        long ts = System.currentTimeMillis();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO events(ts,type,detail,duration_sec) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ts);
            ps.setString(2, type.name());
            ps.setString(3, detail);
            ps.setLong(4, durationSec);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new LineEvent(id, ts, type, detail, durationSec);
            }
        }
    }

    public synchronized int countSince(long ts, LineEvent.Type type) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT COUNT(*) FROM events WHERE ts >= ? AND type = ?")) {
            ps.setLong(1, ts);
            ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Isi haritasi icin: verilen tipteki olaylarin zaman damgalari. */
    public synchronized List<Long> timesSince(long ts, LineEvent.Type type) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT ts FROM events WHERE ts >= ? AND type = ? ORDER BY ts")) {
            ps.setLong(1, ts);
            ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        }
        return out;
    }

    public synchronized List<LineEvent> findRecent(int limit) throws SQLException {
        List<LineEvent> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT id,ts,type,detail,duration_sec FROM events ORDER BY ts DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LineEvent.Type type;
                    try { type = LineEvent.Type.valueOf(rs.getString(3)); }
                    catch (IllegalArgumentException e) { type = LineEvent.Type.COLLECTOR_ERROR; }
                    out.add(new LineEvent(rs.getLong(1), rs.getLong(2), type,
                            rs.getString(4), rs.getLong(5)));
                }
            }
        }
        return out;
    }
}

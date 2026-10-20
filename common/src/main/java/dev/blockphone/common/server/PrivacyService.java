package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.PrivacyData.Audience;
import dev.blockphone.common.protocol.data.PrivacyData.Kind;
import dev.blockphone.common.protocol.data.PrivacyData.Privacy;
import dev.blockphone.common.protocol.data.PrivacyData;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.PrivacyPackets;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Приватность игроков: кто может писать в личку, слать голосовые и фото, приглашать в группы и звонить.
 * Таблицы {@code <prefix>privacy} (настройки) и {@code <prefix>privacy_lists} (kind 0 — разрешённые, 1 — заблокированные).
 * Проверку {@link #allows} вызывают мессенджер и звонки; работает в потоке бэкенда.
 */
public final class PrivacyService {
    private static final int LIST_ALLOWED = 0;
    private static final int LIST_BLOCKED = 1;

    private final Backend backend;
    private final String privacy;
    private final String lists;
    private final String follows;

    PrivacyService(Backend backend) {
        this.backend = backend;
        Database db = backend.database();
        this.privacy = db.table("privacy");
        this.lists = db.table("privacy_lists");
        this.follows = db.table("follows");
    }

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case PrivacyPackets.Request p -> sendState(player);
            case PrivacyPackets.Update p -> {
                Privacy v = p.privacy();
                update("INSERT INTO " + privacy + " (uuid, messages, voice, photos, invites, calls) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE messages = VALUES(messages), voice = VALUES(voice), photos = VALUES(photos), "
                                + "invites = VALUES(invites), calls = VALUES(calls)",
                        player, v.messages().ordinal(), v.voice().ordinal(), v.photos().ordinal(), v.invites().ordinal(),
                        v.calls().ordinal());
                sendState(player);
            }
            case PrivacyPackets.ListAction p -> {
                if (p.user().equals(player)) {
                    return;
                }
                int kind = p.blockList() ? LIST_BLOCKED : LIST_ALLOWED;
                if (p.add()) {
                    if (count(player, kind) >= PrivacyData.MAX_LIST || !backend.users().userExists(p.user())) {
                        return;
                    }
                    // Игрок не может быть сразу и разрешённым, и заблокированным.
                    update("DELETE FROM " + lists + " WHERE owner = ? AND target = ?", player, p.user());
                    update("INSERT INTO " + lists + " (owner, target, kind, created_at) VALUES (?, ?, ?, ?)", player, p.user(), kind,
                            System.currentTimeMillis());
                } else {
                    update("DELETE FROM " + lists + " WHERE owner = ? AND target = ? AND kind = ?", player, p.user(), kind);
                }
                sendState(player);
                if (backend.social() != null) {
                    backend.social().refreshProfile(player, p.user());
                }
            }
            default -> {
            }
        }
    }

    /** Разрешает ли {@code owner} игроку {@code other} это действие. */
    public boolean allows(UUID owner, UUID other, Kind kind) throws SQLException {
        if (owner.equals(other)) {
            return true;
        }
        Integer listed = listKind(owner, other);
        if (listed != null) {
            return listed == LIST_ALLOWED;
        }
        return switch (get(owner).get(kind)) {
            case EVERYONE -> true;
            case NOBODY -> false;
            case FOLLOWING -> following(owner, other);
            case MUTUAL -> following(owner, other) && following(other, owner);
        };
    }

    public boolean blocked(UUID owner, UUID other) throws SQLException {
        Integer listed = listKind(owner, other);
        return listed != null && listed == LIST_BLOCKED;
    }

    public Privacy get(UUID player) throws SQLException {
        try (PreparedStatement ps = backend.database().connection().prepareStatement(
                "SELECT messages, voice, photos, invites, calls FROM " + privacy + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Privacy.DEFAULT;
                }
                Audience[] a = Audience.values();
                return new Privacy(a[clamp(rs.getInt(1))], a[clamp(rs.getInt(2))], a[clamp(rs.getInt(3))], a[clamp(rs.getInt(4))],
                        a[clamp(rs.getInt(5))]);
            }
        }
    }

    private void sendState(UUID player) throws SQLException {
        List<UUID> allowed = new ArrayList<>();
        List<UUID> blocked = new ArrayList<>();
        try (PreparedStatement ps = backend.database().connection().prepareStatement(
                "SELECT target, kind FROM " + lists + " WHERE owner = ? ORDER BY created_at")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    (rs.getInt(2) == LIST_BLOCKED ? blocked : allowed).add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        List<UUID> all = new ArrayList<>(allowed);
        all.addAll(blocked);
        Map<UUID, String> names = backend.users().userNames(all);
        backend.send(player, new PrivacyPackets.State(get(player), users(allowed, names), users(blocked, names)));
    }

    private List<UserInfo> users(List<UUID> ids, Map<UUID, String> names) {
        List<UserInfo> result = new ArrayList<>();
        for (UUID id : ids) {
            result.add(backend.user(id, names.get(id)));
        }
        return result;
    }

    /** Сообщить игроку, что действие запрещено приватностью другого. */
    void deny(UUID player, String key, String arg) {
        backend.send(player, new MessengerPackets.Failure("messenger.error." + key, arg));
    }

    private Integer listKind(UUID owner, UUID other) throws SQLException {
        try (PreparedStatement ps = backend.database().connection().prepareStatement(
                "SELECT kind FROM " + lists + " WHERE owner = ? AND target = ?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, other.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private boolean following(UUID follower, UUID target) throws SQLException {
        try (PreparedStatement ps = backend.database().connection().prepareStatement(
                "SELECT 1 FROM " + follows + " WHERE follower = ? AND target = ?")) {
            ps.setString(1, follower.toString());
            ps.setString(2, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int count(UUID owner, int kind) throws SQLException {
        try (PreparedStatement ps = backend.database().connection().prepareStatement(
                "SELECT COUNT(*) FROM " + lists + " WHERE owner = ? AND kind = ?")) {
            ps.setString(1, owner.toString());
            ps.setInt(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = backend.database().connection().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else if (params[i] instanceof Long l) {
                    ps.setLong(i + 1, l);
                } else {
                    ps.setString(i + 1, params[i].toString());
                }
            }
            ps.executeUpdate();
        }
    }

    private static int clamp(int ordinal) {
        return Math.max(0, Math.min(Audience.values().length - 1, ordinal));
    }
}

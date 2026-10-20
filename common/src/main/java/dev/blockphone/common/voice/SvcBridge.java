package dev.blockphone.common.voice;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Мост к Simple Voice Chat: скрытые группы с паролем для звонков. Создаётся, когда сервер SVC запустился
 * ({@link SvcPlugin}), и кладётся в {@link VoiceBridges}. Действия с группами — в главном потоке сервера.
 */
public final class SvcBridge implements VoiceBridge {
    private final VoicechatServerApi api;
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private SvcBridge(VoicechatServerApi api) {
        this.api = api;
    }

    static void started(VoicechatServerApi api) {
        VoiceBridges.set(new SvcBridge(api));
    }

    static void stopped() {
        VoiceBridges.set(null);
    }

    static void playerLeftGroup(VoicechatConnection connection, Group group) {
        if (VoiceBridges.current() instanceof SvcBridge bridge && connection != null && group != null
                && bridge.groups.containsKey(group.getId())) {
            VoiceBridges.playerLeft(connection.getPlayer().getUuid(), group.getId());
        }
    }

    @Override
    public boolean connected(UUID player) {
        VoicechatConnection connection = api.getConnectionOf(player);
        return connection != null && connection.isInstalled() && connection.isConnected() && !connection.isDisabled();
    }

    @Override
    public UUID createGroup(String name, boolean isolated) {
        String safe = name.strip().replaceAll("[\\r\\n\\t]", " ");
        if (safe.isEmpty()) {
            safe = "BlockPhone";
        }
        if (safe.length() > 20) {
            safe = safe.substring(0, 20).strip();
        }
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        String password = java.util.HexFormat.of().formatHex(bytes);
        Group group = api.groupBuilder().setName(safe).setPassword(password).setHidden(true).setPersistent(false)
                .setType(isolated ? Group.Type.ISOLATED : Group.Type.NORMAL).build();
        groups.put(group.getId(), group);
        return group.getId();
    }

    @Override
    public void join(UUID groupId, UUID player) {
        Group group = groups.get(groupId);
        if (group == null) {
            return;
        }
        VoiceBridges.mainThread().execute(() -> {
            VoicechatConnection connection = api.getConnectionOf(player);
            if (connection != null) {
                connection.setGroup(group);
            }
        });
    }

    @Override
    public void leave(UUID groupId, UUID player) {
        VoiceBridges.mainThread().execute(() -> {
            VoicechatConnection connection = api.getConnectionOf(player);
            if (connection != null && connection.getGroup() != null && connection.getGroup().getId().equals(groupId)) {
                connection.setGroup(null);
            }
        });
    }

    @Override
    public void removeGroup(UUID groupId) {
        groups.remove(groupId);
        // Непостоянная группа исчезает сама, когда из неё все вышли; на всякий случай — убрать явно.
        VoiceBridges.mainThread().execute(() -> {
            try {
                api.removeGroup(groupId);
            } catch (RuntimeException ignored) {
            }
        });
    }
}

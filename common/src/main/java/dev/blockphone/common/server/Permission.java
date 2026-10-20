package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.SocialData.Rights;

/**
 * Права BlockPhone. Узлы работают с LuckPerms (Paper, Velocity, Fabric через fabric-permissions-api).
 * Без системы прав: обычные права есть у всех, модераторские — у операторов (op) и у игроков из списка
 * {@code moderators} в настройках. Узел {@link #MODERATOR} даёт все модераторские права сразу.
 *
 * @param defaultAllowed true — по умолчанию у всех; false — по умолчанию только у операторов
 */
public enum Permission {
    POST("blockphone.post", true, Rights.POST, "Create posts and comments"),
    PHOTO("blockphone.photo", true, Rights.PHOTO, "Attach photos to posts and messages"),
    CREATE_PUBLIC("blockphone.public.create", true, Rights.CREATE_PUBLIC, "Create publics"),
    MOD_POSTS("blockphone.moderate.posts", false, Rights.MOD_POSTS, "Delete any post or comment"),
    MOD_PUBLICS("blockphone.moderate.publics", false, Rights.MOD_PUBLICS, "Delete any public"),
    MOD_MUTE("blockphone.moderate.mute", false, Rights.MOD_MUTE, "Mute players in the feed"),
    MOD_REPORTS("blockphone.moderate.reports", false, Rights.MOD_REPORTS, "View and resolve reports"),
    MODERATOR("blockphone.moderator", false, 0, "All moderator permissions");

    private final String node;
    private final boolean defaultAllowed;
    private final int right;
    private final String description;

    Permission(String node, boolean defaultAllowed, int right, String description) {
        this.node = node;
        this.defaultAllowed = defaultAllowed;
        this.right = right;
        this.description = description;
    }

    public String node() {
        return node;
    }

    public boolean defaultAllowed() {
        return defaultAllowed;
    }

    /** Бит в {@link Rights}. */
    public int right() {
        return right;
    }

    public String description() {
        return description;
    }

    public boolean moderator() {
        return !defaultAllowed;
    }
}

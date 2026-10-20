package dev.blockphone.fabric.client.network;

import dev.blockphone.common.protocol.data.ImageRef.PhotoPolicy;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.fabric.client.photo.PhotoMeta;
import org.jetbrains.annotations.Nullable;

/**
 * Сведения о сессии от сервера ({@link SessionPacket}): id сервера, политика фото, права игрока.
 * Только поток рендера; сбрасывается при выходе с сервера.
 */
public final class ClientSession {
    private static @Nullable SessionPacket session;

    static void set(SessionPacket packet) {
        session = packet;
    }

    static void reset() {
        session = null;
    }

    public static @Nullable SessionPacket get() {
        return session;
    }

    /** Id сервера для сведений о снимке (null — нет сессии). */
    public static @Nullable String serverId() {
        return session != null && !session.serverId().isEmpty() ? session.serverId() : null;
    }

    public static boolean has(int right) {
        return session != null && Rights.has(session.rights(), right);
    }

    public static boolean moderator() {
        return session != null && (session.rights() & Rights.ANY_MOD) != 0;
    }

    public static PhotoPolicy photoPolicy() {
        return session != null ? session.photoPolicy() : PhotoPolicy.NONE;
    }

    /** Можно ли вообще прикреплять фото (политика сервера и право). */
    public static boolean photosAllowed() {
        return photoPolicy() != PhotoPolicy.NONE && has(Rights.PHOTO);
    }

    /** Подходит ли снимок под политику сервера (по сведениям о снимке). */
    public static boolean photoAllowed(@Nullable PhotoMeta meta) {
        return switch (photoPolicy()) {
            case ANY -> true;
            case NONE -> false;
            case SERVER -> meta != null && meta.serverId != null && meta.serverId.equals(serverId());
        };
    }

    /** До какого времени игрок заглушён в ленте (0 — нет). */
    public static long mutedUntil() {
        if (session == null || session.mutedUntil() <= System.currentTimeMillis()) {
            return 0;
        }
        return session.mutedUntil();
    }

    public static int maxPhotoBytes() {
        return session != null ? session.maxPhotoBytes() : 600 * 1024;
    }

    public static int maxPhotoSide() {
        return session != null ? session.maxPhotoSide() : 1280;
    }

    /** Предел длины голосового (0 — голосовые недоступны). */
    public static int maxVoiceSeconds() {
        return session != null ? session.maxVoiceSeconds() : 0;
    }

    /** На сервере есть Simple Voice Chat, звонки включены и разрешены игроку. */
    public static boolean callsAvailable() {
        return session != null && session.callsAvailable();
    }

    public static int openReports() {
        return session != null ? session.openReports() : 0;
    }

    /** Изменить число открытых жалоб (пришла новая / модератор обработал). */
    public static void setOpenReports(int count) {
        SessionPacket s = session;
        if (s != null) {
            session = new SessionPacket(s.serverId(), s.socialEnabled(), s.photoPolicy(), s.maxPhotoBytes(), s.maxPhotoSide(),
                    s.rights(), s.mutedUntil(), Math.max(0, count), s.maxVoiceSeconds(), s.callsAvailable());
        }
    }

    private ClientSession() {
    }
}

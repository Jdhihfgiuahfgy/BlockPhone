package dev.blockphone.common.protocol.data;

import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;

/**
 * Приватность игрока: кто может писать в личку, слать голосовые и фото, приглашать в группы и звонить.
 * Списки «разрешённые» и «заблокированные» важнее настроек: разрешённым можно всё, заблокированным — ничего.
 */
public final class PrivacyData {
    /** Кому можно. FOLLOWING — тем, на кого подписан я; MUTUAL — взаимные подписки; NOBODY — никому (кроме разрешённых). */
    public enum Audience { EVERYONE, FOLLOWING, MUTUAL, NOBODY }

    /** Что именно разрешается. */
    public enum Kind { MESSAGES, VOICE, PHOTOS, INVITES, CALLS }

    public static final int MAX_LIST = 200;

    public record Privacy(Audience messages, Audience voice, Audience photos, Audience invites, Audience calls) {
        public static final Privacy DEFAULT = new Privacy(Audience.EVERYONE, Audience.EVERYONE, Audience.EVERYONE, Audience.EVERYONE,
                Audience.EVERYONE);

        public Audience get(Kind kind) {
            return switch (kind) {
                case MESSAGES -> messages;
                case VOICE -> voice;
                case PHOTOS -> photos;
                case INVITES -> invites;
                case CALLS -> calls;
            };
        }

        public Privacy with(Kind kind, Audience value) {
            return new Privacy(kind == Kind.MESSAGES ? value : messages, kind == Kind.VOICE ? value : voice,
                    kind == Kind.PHOTOS ? value : photos, kind == Kind.INVITES ? value : invites, kind == Kind.CALLS ? value : calls);
        }

        public static Privacy read(PacketReader r) {
            return new Privacy(r.readEnum(Audience.values()), r.readEnum(Audience.values()), r.readEnum(Audience.values()),
                    r.readEnum(Audience.values()), r.readEnum(Audience.values()));
        }

        public static void write(PacketWriter w, Privacy p) {
            w.writeEnum(p.messages).writeEnum(p.voice).writeEnum(p.photos).writeEnum(p.invites).writeEnum(p.calls);
        }
    }

    private PrivacyData() {
    }
}

package dev.blockphone.common.protocol.data;

import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;

import java.util.List;

/** Данные ленты (посты, комментарии, профили, паблики, жалобы), которые передаются в пакетах. */
public final class SocialData {
    /** Ограничения (проверяются на сервере; клиент использует их для подсказок). */
    public static final int MAX_POST_LENGTH = 2000;
    public static final int MAX_COMMENT_LENGTH = 500;
    public static final int MAX_BIO_LENGTH = 200;
    public static final int MAX_PUBLIC_TITLE = 32;
    public static final int MAX_PUBLIC_DESCRIPTION = 300;
    public static final int MAX_REASON_LENGTH = 32;
    public static final int FEED_PAGE = 12;
    public static final int COMMENTS_PAGE = 30;
    public static final int MAX_LIST = 500;
    public static final int MAX_PUBLIC_ADMINS = 10;

    /** Права игрока (битовая маска). Сервер присылает их клиенту, чтобы показать нужные кнопки. */
    public static final class Rights {
        public static final int POST = 1;
        public static final int PHOTO = 1 << 1;
        public static final int CREATE_PUBLIC = 1 << 2;
        public static final int MOD_POSTS = 1 << 3;
        public static final int MOD_PUBLICS = 1 << 4;
        public static final int MOD_MUTE = 1 << 5;
        public static final int MOD_REPORTS = 1 << 6;
        /** Голосовые сообщения. */
        public static final int VOICE = 1 << 7;
        /** Звонки. */
        public static final int CALL = 1 << 8;
        /** Блокировать игроков в телефоне (бан). */
        public static final int MOD_BAN = 1 << 9;
        /** Читать личные и групповые чаты игроков. */
        public static final int MOD_CHATS = 1 << 10;
        /** Любое модераторское право. */
        public static final int ANY_MOD = MOD_POSTS | MOD_PUBLICS | MOD_MUTE | MOD_REPORTS | MOD_BAN | MOD_CHATS;

        public static boolean has(int mask, int right) {
            return (mask & right) != 0;
        }

        private Rights() {
        }
    }

    public enum FeedKind {
        /** Подписки: люди, на которых подписан, паблики и свои посты. */
        FOLLOWING,
        /** Все посты сервера. */
        ALL,
        /** Личные посты игрока (профиль). */
        USER,
        /** Посты паблика. */
        PUBLIC
    }

    public enum Role { NONE, ADMIN, OWNER }

    public enum ReportTarget { POST, COMMENT, PUBLIC, USER }

    /** Уведомление: LIKE/COMMENT — о вашем посте, FOLLOW — на вас подписались, NEW_POST — новый пост в подписках, REPORT — новая жалоба (модераторам). */
    public enum NoticeType { LIKE, COMMENT, FOLLOW, NEW_POST, REPORT }

    /** Краткая ссылка на паблик (в посте). */
    /** Ссылка на паблик в посте; avatar — своё фото паблика или null (тогда — цветной квадрат с буквой). */
    public record PublicRef(long id, String title, int color, ImageRef avatar) {
        public static PublicRef read(PacketReader r) {
            return new PublicRef(r.readLong(), r.readString(MAX_PUBLIC_TITLE * 2), r.readInt(), r.readNullable(ImageRef::read));
        }

        public static void write(PacketWriter w, PublicRef p) {
            w.writeLong(p.id).writeString(p.title).writeInt(p.color).writeNullable(p.avatar, ImageRef::write);
        }
    }

    /**
     * Пост. author — кто написал (для поста паблика — его администратор), pub — паблик или null (личный пост).
     *
     * @param canDelete получатель пакета может удалить пост (автор, администратор паблика, модератор)
     */
    public record PostInfo(long id, UserInfo author, PublicRef pub, String text, ImageRef image, long createdAt,
                           int likes, boolean liked, int comments, boolean canDelete) {
        public static PostInfo read(PacketReader r) {
            return new PostInfo(r.readLong(), UserInfo.read(r), r.readNullable(PublicRef::read), r.readString(MAX_POST_LENGTH * 2),
                    r.readNullable(ImageRef::read), r.readLong(), r.readVarInt(), r.readBoolean(), r.readVarInt(), r.readBoolean());
        }

        public static void write(PacketWriter w, PostInfo p) {
            w.writeLong(p.id);
            UserInfo.write(w, p.author);
            w.writeNullable(p.pub, PublicRef::write).writeString(p.text).writeNullable(p.image, ImageRef::write)
                    .writeLong(p.createdAt).writeVarInt(p.likes).writeBoolean(p.liked).writeVarInt(p.comments)
                    .writeBoolean(p.canDelete);
        }

        public PostInfo withLikes(int likes, boolean liked) {
            return new PostInfo(id, author, pub, text, image, createdAt, likes, liked, comments, canDelete);
        }

        public PostInfo withComments(int comments) {
            return new PostInfo(id, author, pub, text, image, createdAt, likes, liked, comments, canDelete);
        }
    }

    public record CommentInfo(long id, long postId, UserInfo author, String text, long createdAt, boolean canDelete) {
        public static CommentInfo read(PacketReader r) {
            return new CommentInfo(r.readLong(), r.readLong(), UserInfo.read(r), r.readString(MAX_COMMENT_LENGTH * 2), r.readLong(),
                    r.readBoolean());
        }

        public static void write(PacketWriter w, CommentInfo c) {
            w.writeLong(c.id).writeLong(c.postId);
            UserInfo.write(w, c.author);
            w.writeString(c.text).writeLong(c.createdAt).writeBoolean(c.canDelete);
        }
    }

    /**
     * Профиль игрока.
     *
     * @param followed   получатель подписан на этого игрока
     * @param followsYou этот игрок подписан на получателя
     * @param mutedUntil до какого времени игрок заглушён в ленте (видят только модераторы и он сам; 0 — не заглушён)
     */
    /**
     * Профиль. look — оформление; canMessage — получатель пакета может написать этому игроку в личку (по его приватности);
     * blocked — получатель заблокировал этого игрока.
     */
    public record ProfileInfo(UserInfo user, String bio, int posts, int followers, int following, boolean followed,
                              boolean followsYou, long lastSeen, long mutedUntil, ProfileLook look, boolean canMessage, boolean blocked) {
        public static ProfileInfo read(PacketReader r) {
            return new ProfileInfo(UserInfo.read(r), r.readString(MAX_BIO_LENGTH * 2), r.readVarInt(), r.readVarInt(), r.readVarInt(),
                    r.readBoolean(), r.readBoolean(), r.readLong(), r.readLong(), ProfileLook.read(r), r.readBoolean(), r.readBoolean());
        }

        public static void write(PacketWriter w, ProfileInfo p) {
            UserInfo.write(w, p.user);
            w.writeString(p.bio).writeVarInt(p.posts).writeVarInt(p.followers).writeVarInt(p.following)
                    .writeBoolean(p.followed).writeBoolean(p.followsYou).writeLong(p.lastSeen).writeLong(p.mutedUntil);
            ProfileLook.write(w, p.look);
            w.writeBoolean(p.canMessage).writeBoolean(p.blocked);
        }
    }

    /**
     * Оформление личной страницы. avatarType: {@link #AVATAR_HEAD} — голова, {@link #AVATAR_BODY} — фигура из скина в позе
     * {@code pose} (0..{@link #POSES}-1); background — фон аватара (0..{@link #BACKGROUNDS}-1); bannerStyle — градиент баннера
     * (0..{@link #BANNERS}-1); bannerImage — фото баннера (важнее градиента) или null; avatarImage — своё фото-аватар
     * (для {@link #AVATAR_PHOTO}) или null.
     */
    public record ProfileLook(int avatarType, int pose, int background, int bannerStyle, ImageRef bannerImage, ImageRef avatarImage) {
        public static final int AVATAR_HEAD = 0;
        public static final int AVATAR_BODY = 1;
        public static final int AVATAR_PHOTO = 2;
        public static final int POSES = 8;
        public static final int BACKGROUNDS = 12;
        public static final int BANNERS = 10;
        public static final ProfileLook DEFAULT = new ProfileLook(AVATAR_HEAD, 0, 0, 0, null, null);

        public ProfileLook {
            avatarType = Math.floorMod(avatarType, 3);
            pose = Math.floorMod(pose, POSES);
            background = Math.floorMod(background, BACKGROUNDS);
            bannerStyle = Math.floorMod(bannerStyle, BANNERS);
        }

        public static ProfileLook read(PacketReader r) {
            return new ProfileLook(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readNullable(ImageRef::read),
                    r.readNullable(ImageRef::read));
        }

        public static void write(PacketWriter w, ProfileLook l) {
            w.writeVarInt(l.avatarType).writeVarInt(l.pose).writeVarInt(l.background).writeVarInt(l.bannerStyle)
                    .writeNullable(l.bannerImage, ImageRef::write).writeNullable(l.avatarImage, ImageRef::write);
        }
    }

    /**
     * Паблик (сообщество). role — роль получателя пакета; admins — администраторы (без владельца).
     */
    public record PublicInfo(long id, String title, String description, int color, UserInfo owner, List<UserInfo> admins,
                             int subscribers, int posts, boolean subscribed, Role role, long createdAt, ImageRef avatar) {
        public static PublicInfo read(PacketReader r) {
            return new PublicInfo(r.readLong(), r.readString(MAX_PUBLIC_TITLE * 2), r.readString(MAX_PUBLIC_DESCRIPTION * 2), r.readInt(),
                    UserInfo.read(r), r.readList(MAX_PUBLIC_ADMINS + 1, UserInfo::read), r.readVarInt(), r.readVarInt(),
                    r.readBoolean(), r.readEnum(Role.values()), r.readLong(), r.readNullable(ImageRef::read));
        }

        public static void write(PacketWriter w, PublicInfo p) {
            w.writeLong(p.id).writeString(p.title).writeString(p.description).writeInt(p.color);
            UserInfo.write(w, p.owner);
            w.writeList(p.admins, UserInfo::write).writeVarInt(p.subscribers).writeVarInt(p.posts).writeBoolean(p.subscribed)
                    .writeEnum(p.role).writeLong(p.createdAt).writeNullable(p.avatar, ImageRef::write);
        }

        public PublicRef ref() {
            return new PublicRef(id, title, color, avatar);
        }

        public boolean canPost() {
            return role != Role.NONE;
        }
    }

    /**
     * Жалоба для модераторов. targetUser — чей контент (или на кого жалоба), excerpt — начало текста поста/комментария
     * или название паблика; reason — ключ причины (spam, abuse, photo, other).
     */
    public record ReportInfo(long id, UserInfo reporter, ReportTarget type, long targetId, UserInfo targetUser, String excerpt,
                             String reason, long createdAt) {
        public static ReportInfo read(PacketReader r) {
            return new ReportInfo(r.readLong(), UserInfo.read(r), r.readEnum(ReportTarget.values()), r.readLong(), UserInfo.read(r),
                    r.readString(400), r.readString(MAX_REASON_LENGTH * 2), r.readLong());
        }

        public static void write(PacketWriter w, ReportInfo rep) {
            w.writeLong(rep.id);
            UserInfo.write(w, rep.reporter);
            w.writeEnum(rep.type).writeLong(rep.targetId);
            UserInfo.write(w, rep.targetUser);
            w.writeString(rep.excerpt).writeString(rep.reason).writeLong(rep.createdAt);
        }
    }

    /** Цвета пабликов (аватар — квадрат этого цвета с первой буквой). */
    public static final int[] PUBLIC_COLORS = {0xFF5E81AC, 0xFFBF616A, 0xFFA3BE8C, 0xFFD08770, 0xFFB48EAD, 0xFF88C0D0,
            0xFFE0559B, 0xFF4C8DF6, 0xFF3FB97A, 0xFFF29544};

    private SocialData() {
    }
}

package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData.CommentInfo;
import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.NoticeType;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.ProfileInfo;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;

import java.util.List;
import java.util.UUID;

import static dev.blockphone.common.protocol.data.SocialData.MAX_BIO_LENGTH;
import static dev.blockphone.common.protocol.data.SocialData.MAX_COMMENT_LENGTH;
import static dev.blockphone.common.protocol.data.SocialData.MAX_LIST;
import static dev.blockphone.common.protocol.data.SocialData.MAX_POST_LENGTH;
import static dev.blockphone.common.protocol.data.SocialData.MAX_PUBLIC_DESCRIPTION;
import static dev.blockphone.common.protocol.data.SocialData.MAX_PUBLIC_TITLE;
import static dev.blockphone.common.protocol.data.SocialData.MAX_REASON_LENGTH;

/**
 * Пакеты ленты: посты, лайки, комментарии, профили, подписки, паблики, жалобы и модерация.
 * Ответы на списки несут requestId запроса — клиент отбрасывает устаревшие.
 */
public final class SocialPackets {

    // ---------- Сервер → клиент ----------

    /** Страница ленты (от новых к старым). */
    public record FeedPage(int requestId, List<PostInfo> posts, boolean hasMore) implements Packet {
        public static FeedPage read(PacketReader r) {
            return new FeedPage(r.readVarInt(), r.readList(MAX_LIST, PostInfo::read), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeList(posts, PostInfo::write).writeBoolean(hasMore);
        }
    }

    /** Пост создан (nonce — из CreatePost, только автору) или изменился (лайки, комментарии; nonce пустой). */
    public record PostUpdated(PostInfo post, String nonce) implements Packet {
        public static PostUpdated read(PacketReader r) {
            return new PostUpdated(PostInfo.read(r), r.readString(64));
        }

        @Override
        public void write(PacketWriter w) {
            PostInfo.write(w, post);
            w.writeString(nonce);
        }
    }

    public record PostRemoved(long postId) implements Packet {
        public static PostRemoved read(PacketReader r) {
            return new PostRemoved(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId);
        }
    }

    /** Комментарии (от старых к новым). older — подгрузка более старых, hasMore — есть ещё старее. */
    public record Comments(long postId, List<CommentInfo> comments, boolean older, boolean hasMore) implements Packet {
        public static Comments read(PacketReader r) {
            return new Comments(r.readLong(), r.readList(MAX_LIST, CommentInfo::read), r.readBoolean(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId).writeList(comments, CommentInfo::write).writeBoolean(older).writeBoolean(hasMore);
        }
    }

    public record CommentAdded(CommentInfo comment) implements Packet {
        public static CommentAdded read(PacketReader r) {
            return new CommentAdded(CommentInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            CommentInfo.write(w, comment);
        }
    }

    public record CommentRemoved(long postId, long commentId) implements Packet {
        public static CommentRemoved read(PacketReader r) {
            return new CommentRemoved(r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId).writeLong(commentId);
        }
    }

    public record Profile(ProfileInfo profile) implements Packet {
        public static Profile read(PacketReader r) {
            return new Profile(ProfileInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            ProfileInfo.write(w, profile);
        }
    }

    public record PublicData(PublicInfo info) implements Packet {
        public static PublicData read(PacketReader r) {
            return new PublicData(PublicInfo.read(r));
        }

        @Override
        public void write(PacketWriter w) {
            PublicInfo.write(w, info);
        }
    }

    /** Паблик удалён (владельцем или модератором). */
    public record PublicRemoved(long publicId) implements Packet {
        public static PublicRemoved read(PacketReader r) {
            return new PublicRemoved(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(publicId);
        }
    }

    public record PublicList(int requestId, List<PublicInfo> publics) implements Packet {
        public static PublicList read(PacketReader r) {
            return new PublicList(r.readVarInt(), r.readList(MAX_LIST, PublicInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeList(publics, PublicInfo::write);
        }
    }

    /** Список игроков (подписчики / подписки). */
    public record UserList(int requestId, List<UserInfo> users) implements Packet {
        public static UserList read(PacketReader r) {
            return new UserList(r.readVarInt(), r.readList(MAX_LIST, UserInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeList(users, UserInfo::write);
        }
    }

    public record SearchResults(int requestId, List<UserInfo> users, List<PublicInfo> publics) implements Packet {
        public static SearchResults read(PacketReader r) {
            return new SearchResults(r.readVarInt(), r.readList(MAX_LIST, UserInfo::read), r.readList(MAX_LIST, PublicInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeList(users, UserInfo::write).writeList(publics, PublicInfo::write);
        }
    }

    /** Необработанные жалобы (только модераторам). */
    public record Reports(List<ReportInfo> reports) implements Packet {
        public static Reports read(PacketReader r) {
            return new Reports(r.readList(MAX_LIST, ReportInfo::read));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeList(reports, ReportInfo::write);
        }
    }

    /** Уведомление (лайк, комментарий, подписка, новый пост, жалоба). text — начало комментария/поста. */
    public record Notice(NoticeType type, UserInfo actor, long postId, String text) implements Packet {
        public static Notice read(PacketReader r) {
            return new Notice(r.readEnum(NoticeType.values()), UserInfo.read(r), r.readLong(), r.readString(400));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeEnum(type);
            UserInfo.write(w, actor);
            w.writeLong(postId).writeString(text);
        }
    }

    /** Ошибка: ключ перевода (social.error.*) и необязательный аргумент. */
    public record Failure(String key, String arg) implements Packet {
        public static Failure read(PacketReader r) {
            return new Failure(r.readString(128), r.readString(256));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(key).writeString(arg);
        }
    }

    /** Действие выполнено (ключ перевода social.done.*) — например, «Жалоба отправлена». */
    public record Done(String key) implements Packet {
        public static Done read(PacketReader r) {
            return new Done(r.readString(128));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(key);
        }
    }

    // ---------- Клиент → сервер ----------

    /**
     * Страница ленты. user — для USER, publicId — для PUBLIC; beforeId = 0 — самые новые.
     */
    public record RequestFeed(int requestId, FeedKind kind, UUID user, long publicId, long beforeId) implements Packet {
        public static RequestFeed read(PacketReader r) {
            return new RequestFeed(r.readVarInt(), r.readEnum(FeedKind.values()), r.readNullable(PacketReader::readUuid), r.readLong(),
                    r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeEnum(kind).writeNullable(user, PacketWriter::writeUuid).writeLong(publicId).writeLong(beforeId);
        }
    }

    /** Новый пост: publicId = 0 — от себя, иначе — в паблик (нужно быть его администратором). imageId = 0 — без фото. */
    public record CreatePost(String nonce, long publicId, String text, long imageId) implements Packet {
        public static CreatePost read(PacketReader r) {
            return new CreatePost(r.readString(64), r.readLong(), r.readString(MAX_POST_LENGTH), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(nonce).writeLong(publicId).writeString(text).writeLong(imageId);
        }
    }

    public record DeletePost(long postId) implements Packet {
        public static DeletePost read(PacketReader r) {
            return new DeletePost(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId);
        }
    }

    public record LikePost(long postId, boolean like) implements Packet {
        public static LikePost read(PacketReader r) {
            return new LikePost(r.readLong(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId).writeBoolean(like);
        }
    }

    /** Комментарии к посту: beforeId = 0 — последние (заодно сервер шлёт актуальный пост). */
    public record RequestComments(long postId, long beforeId) implements Packet {
        public static RequestComments read(PacketReader r) {
            return new RequestComments(r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId).writeLong(beforeId);
        }
    }

    public record AddComment(long postId, String text) implements Packet {
        public static AddComment read(PacketReader r) {
            return new AddComment(r.readLong(), r.readString(MAX_COMMENT_LENGTH));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(postId).writeString(text);
        }
    }

    public record DeleteComment(long commentId) implements Packet {
        public static DeleteComment read(PacketReader r) {
            return new DeleteComment(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(commentId);
        }
    }

    public record RequestProfile(UUID user) implements Packet {
        public static RequestProfile read(PacketReader r) {
            return new RequestProfile(r.readUuid());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(user);
        }
    }

    /** Изменить «о себе». */
    public record UpdateBio(String bio) implements Packet {
        public static UpdateBio read(PacketReader r) {
            return new UpdateBio(r.readString(MAX_BIO_LENGTH));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(bio);
        }
    }

    /** Оформление своей страницы. bannerImageId: 0 — без фото, -1 — оставить как есть, иначе — загруженное фото. */
    /** avatarImageId — как bannerImageId: 0 — без фото, −1 — оставить, иначе — загруженное фото (Purpose POST). */
    public record UpdateLook(int avatarType, int pose, int background, int bannerStyle, long bannerImageId, long avatarImageId) implements Packet {
        public static UpdateLook read(PacketReader r) {
            return new UpdateLook(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(avatarType).writeVarInt(pose).writeVarInt(background).writeVarInt(bannerStyle).writeLong(bannerImageId)
                    .writeLong(avatarImageId);
        }
    }

    /** C→S: аватар паблика (владелец, админ или модератор пабликов): imageId — загруженное фото (Purpose POST), 0 — убрать. */
    public record SetPublicAvatar(long publicId, long imageId) implements Packet {
        public static SetPublicAvatar read(PacketReader r) {
            return new SetPublicAvatar(r.readLong(), r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(publicId).writeLong(imageId);
        }
    }

    public record Follow(UUID user, boolean follow) implements Packet {
        public static Follow read(PacketReader r) {
            return new Follow(r.readUuid(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(user).writeBoolean(follow);
        }
    }

    /** Подписчики (followers = true) или подписки игрока. Ответ — {@link UserList}. */
    public record RequestFollows(int requestId, UUID user, boolean followers) implements Packet {
        public static RequestFollows read(PacketReader r) {
            return new RequestFollows(r.readVarInt(), r.readUuid(), r.readBoolean());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeUuid(user).writeBoolean(followers);
        }
    }

    public record RequestPublic(long publicId) implements Packet {
        public static RequestPublic read(PacketReader r) {
            return new RequestPublic(r.readLong());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(publicId);
        }
    }

    public record CreatePublic(String title, String description) implements Packet {
        public static CreatePublic read(PacketReader r) {
            return new CreatePublic(r.readString(MAX_PUBLIC_TITLE), r.readString(MAX_PUBLIC_DESCRIPTION));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(title).writeString(description);
        }
    }

    /** Действие с пабликом. EDIT — title/description; ADD_ADMIN/REMOVE_ADMIN — user. */
    public record PublicAction(long publicId, Action action, String title, String description, UUID user) implements Packet {
        public enum Action { EDIT, SUBSCRIBE, UNSUBSCRIBE, ADD_ADMIN, REMOVE_ADMIN, DELETE }

        public static PublicAction read(PacketReader r) {
            return new PublicAction(r.readLong(), r.readEnum(Action.values()), r.readString(MAX_PUBLIC_TITLE),
                    r.readString(MAX_PUBLIC_DESCRIPTION), r.readNullable(PacketReader::readUuid));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(publicId).writeEnum(action).writeString(title).writeString(description).writeNullable(user, PacketWriter::writeUuid);
        }
    }

    public record RequestPublics(int requestId, Kind kind) implements Packet {
        /** SUBSCRIBED — мои подписки, MANAGED — где я владелец/администратор, POPULAR — самые популярные. */
        public enum Kind { SUBSCRIBED, MANAGED, POPULAR }

        public static RequestPublics read(PacketReader r) {
            return new RequestPublics(r.readVarInt(), r.readEnum(Kind.values()));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeEnum(kind);
        }
    }

    /** Поиск игроков (по началу ника) и пабликов (по части названия). */
    public record Search(int requestId, String query) implements Packet {
        public static Search read(PacketReader r) {
            return new Search(r.readVarInt(), r.readString(32));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeVarInt(requestId).writeString(query);
        }
    }

    /** Жалоба. targetId — id поста/комментария/паблика; для USER — 0 и targetUser. */
    public record Report(ReportTarget type, long targetId, UUID targetUser, String reason) implements Packet {
        public static Report read(PacketReader r) {
            return new Report(r.readEnum(ReportTarget.values()), r.readLong(), r.readNullable(PacketReader::readUuid),
                    r.readString(MAX_REASON_LENGTH));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeEnum(type).writeLong(targetId).writeNullable(targetUser, PacketWriter::writeUuid).writeString(reason);
        }
    }

    public record RequestReports() implements Packet {
        public static RequestReports read(PacketReader r) {
            return new RequestReports();
        }

        @Override
        public void write(PacketWriter w) {
        }
    }

    /** Решение по жалобе (и по всем остальным жалобам на тот же объект). */
    public record ResolveReport(long reportId, Resolution resolution) implements Packet {
        /** DISMISS — отклонить, DELETE — удалить контент, DELETE_AND_MUTE — удалить и заглушить автора на сутки. */
        public enum Resolution { DISMISS, DELETE, DELETE_AND_MUTE }

        public static ResolveReport read(PacketReader r) {
            return new ResolveReport(r.readLong(), r.readEnum(Resolution.values()));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeLong(reportId).writeEnum(resolution);
        }
    }

    /** Заглушить игрока в ленте: hours > 0 — на столько часов, -1 — навсегда, 0 — снять. */
    public record Mute(UUID user, int hours) implements Packet {
        public static Mute read(PacketReader r) {
            return new Mute(r.readUuid(), r.readVarInt());
        }

        @Override
        public void write(PacketWriter w) {
            w.writeUuid(user).writeVarInt(hours);
        }
    }

    private SocialPackets() {
    }
}

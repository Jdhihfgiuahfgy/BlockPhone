package dev.blockphone.fabric.client.social;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData.CommentInfo;
import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.NoticeType;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.ProfileInfo;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.network.ClientSession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Состояние ленты на клиенте: загруженные ленты, посты, комментарии, профили, паблики, жалобы.
 * Обновляется пакетами сервера ({@link #handle}), UI читает его каждый кадр.
 * Только поток рендера; сбрасывается при выходе с сервера.
 */
public final class SocialClient {
    /** Загруженная лента (id постов от новых к старым). */
    public static final class Feed {
        public final FeedKind kind;
        public final @Nullable UUID user;
        public final long publicId;
        public final List<Long> ids = new ArrayList<>();
        public boolean loaded;
        public boolean loading;
        public boolean hasMore;

        Feed(FeedKind kind, @Nullable UUID user, long publicId) {
            this.kind = kind;
            this.user = user;
            this.publicId = publicId;
        }
    }

    /** Загруженные комментарии поста (от старых к новым). */
    public static final class Comments {
        public final List<CommentInfo> list = new ArrayList<>();
        public boolean loaded;
        public boolean hasMore;
        public boolean loadingOlder;
    }

    private record FeedRequest(String key, boolean refresh) {
    }

    private static final Map<String, Feed> FEEDS = new HashMap<>();
    private static final Map<Integer, FeedRequest> FEED_REQUESTS = new HashMap<>();
    private static final Map<Long, PostInfo> POSTS = new HashMap<>();
    private static final Map<Long, Comments> COMMENTS = new HashMap<>();
    private static final Map<UUID, ProfileInfo> PROFILES = new HashMap<>();
    private static final Map<Long, PublicInfo> PUBLICS = new HashMap<>();
    private static final Map<SocialPackets.RequestPublics.Kind, List<Long>> PUBLIC_LISTS =
            new EnumMap<>(SocialPackets.RequestPublics.Kind.class);
    private static final Map<Integer, SocialPackets.RequestPublics.Kind> PUBLIC_REQUESTS = new HashMap<>();
    private static List<UserInfo> searchUsers = List.of();
    private static List<Long> searchPublics = List.of();
    private static boolean searchLoaded;
    private static List<UserInfo> userList = List.of();
    private static boolean userListLoaded;
    private static @Nullable List<ReportInfo> reports;
    private static int requestCounter;
    private static int searchRequest;
    private static int userListRequest;
    private static int newPosts;
    private static int nonceCounter;
    private static @Nullable String createdNonce;
    private static @Nullable PostInfo createdPost;
    private static @Nullable String message;
    private static boolean messageError;
    private static long messageAt;

    // --- Чтение состояния ---

    public static String feedKey(FeedKind kind, @Nullable UUID user, long publicId) {
        return switch (kind) {
            case USER -> "user:" + user;
            case PUBLIC -> "public:" + publicId;
            default -> kind.name();
        };
    }

    /** Лента (создаётся и запрашивается при первом обращении). */
    public static Feed feed(FeedKind kind, @Nullable UUID user, long publicId) {
        String key = feedKey(kind, user, publicId);
        Feed feed = FEEDS.get(key);
        if (feed == null) {
            feed = new Feed(kind, user, publicId);
            FEEDS.put(key, feed);
            refresh(feed);
        }
        return feed;
    }

    public static List<PostInfo> posts(Feed feed) {
        List<PostInfo> result = new ArrayList<>(feed.ids.size());
        for (long id : feed.ids) {
            PostInfo post = POSTS.get(id);
            if (post != null) {
                result.add(post);
            }
        }
        return result;
    }

    public static @Nullable PostInfo post(long id) {
        return POSTS.get(id);
    }

    public static Comments comments(long postId) {
        return COMMENTS.computeIfAbsent(postId, k -> new Comments());
    }

    public static @Nullable ProfileInfo profile(UUID uuid) {
        return PROFILES.get(uuid);
    }

    public static @Nullable PublicInfo publicInfo(long id) {
        return PUBLICS.get(id);
    }

    /** Список пабликов (null — ещё загружается). */
    public static @Nullable List<PublicInfo> publics(SocialPackets.RequestPublics.Kind kind) {
        List<Long> ids = PUBLIC_LISTS.get(kind);
        if (ids == null) {
            return null;
        }
        List<PublicInfo> result = new ArrayList<>();
        for (long id : ids) {
            PublicInfo info = PUBLICS.get(id);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    /** Известный клиенту паблик, где игрок владелец, по названию в нижнем регистре (после создания). */
    public static @Nullable PublicInfo findOwnedPublic(String titleLower) {
        for (PublicInfo info : PUBLICS.values()) {
            if (info.role() == dev.blockphone.common.protocol.data.SocialData.Role.OWNER
                    && info.title().toLowerCase(java.util.Locale.ROOT).equals(titleLower)) {
                return info;
            }
        }
        return null;
    }

    public static boolean searchLoaded() {
        return searchLoaded;
    }

    public static List<UserInfo> searchUsers() {
        return searchUsers;
    }

    public static List<PublicInfo> searchPublics() {
        List<PublicInfo> result = new ArrayList<>();
        for (long id : searchPublics) {
            PublicInfo info = PUBLICS.get(id);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    /** Подписчики/подписки (null — загружается). */
    public static @Nullable List<UserInfo> userList() {
        return userListLoaded ? userList : null;
    }

    public static @Nullable List<ReportInfo> reports() {
        return reports;
    }

    /** Число на значке приложения: новые посты в подписках + жалобы (для модераторов). */
    public static int badge() {
        return newPosts + ClientSession.openReports();
    }

    public static int newPosts() {
        return newPosts;
    }

    public static void clearNewPosts() {
        newPosts = 0;
    }

    /** Свой пост, созданный в ответ на {@link #createPost} с этим nonce (одноразово). */
    public static @Nullable PostInfo takeCreated(String nonce) {
        if (nonce.equals(createdNonce)) {
            PostInfo post = createdPost;
            createdNonce = null;
            createdPost = null;
            return post;
        }
        return null;
    }

    /** Последнее сообщение сервера («Жалоба отправлена» / ошибка), если было не позже 4 секунд назад. */
    public static @Nullable String recentMessage() {
        return message != null && System.currentTimeMillis() - messageAt < 4000 ? message : null;
    }

    public static boolean recentMessageIsError() {
        return messageError;
    }

    /** Показать сообщение в приложении (например, ошибку загрузки фото). */
    public static void showMessage(String text, boolean error) {
        message = text;
        messageError = error;
        messageAt = System.currentTimeMillis();
    }

    // --- Действия ---

    public static void send(Packet packet) {
        ClientNetwork.send(packet);
    }

    public static void refresh(Feed feed) {
        int id = ++requestCounter;
        FEED_REQUESTS.put(id, new FeedRequest(feedKey(feed.kind, feed.user, feed.publicId), true));
        feed.loading = true;
        send(new SocialPackets.RequestFeed(id, feed.kind, feed.user, feed.publicId, 0));
    }

    public static void loadMore(Feed feed) {
        if (!feed.loaded || feed.loading || !feed.hasMore || feed.ids.isEmpty()) {
            return;
        }
        int id = ++requestCounter;
        FEED_REQUESTS.put(id, new FeedRequest(feedKey(feed.kind, feed.user, feed.publicId), false));
        feed.loading = true;
        send(new SocialPackets.RequestFeed(id, feed.kind, feed.user, feed.publicId, feed.ids.get(feed.ids.size() - 1)));
    }

    /** Отправить пост. Возвращает nonce для {@link #takeCreated}. */
    public static String createPost(long publicId, String text, long imageId) {
        String nonce = "p" + (++nonceCounter) + "_" + System.nanoTime();
        send(new SocialPackets.CreatePost(nonce, publicId, text, imageId));
        return nonce;
    }

    /** Лайк/снять лайк (сразу на экране, потом подтверждает сервер). */
    public static void toggleLike(PostInfo post) {
        boolean like = !post.liked();
        POSTS.put(post.id(), post.withLikes(Math.max(0, post.likes() + (like ? 1 : -1)), like));
        send(new SocialPackets.LikePost(post.id(), like));
    }

    public static void requestComments(long postId) {
        Comments c = comments(postId);
        c.loaded = false;
        send(new SocialPackets.RequestComments(postId, 0));
    }

    public static void requestOlderComments(long postId) {
        Comments c = comments(postId);
        if (c.loaded && c.hasMore && !c.loadingOlder && !c.list.isEmpty()) {
            c.loadingOlder = true;
            send(new SocialPackets.RequestComments(postId, c.list.get(0).id()));
        }
    }

    public static void requestProfile(UUID uuid) {
        send(new SocialPackets.RequestProfile(uuid));
    }

    public static void requestPublic(long id) {
        send(new SocialPackets.RequestPublic(id));
    }

    public static void requestPublics(SocialPackets.RequestPublics.Kind kind) {
        int id = ++requestCounter;
        PUBLIC_REQUESTS.put(id, kind);
        send(new SocialPackets.RequestPublics(id, kind));
    }

    public static void search(String query) {
        searchRequest = ++requestCounter;
        send(new SocialPackets.Search(searchRequest, query));
    }

    public static void requestFollows(UUID user, boolean followers) {
        userListRequest = ++requestCounter;
        userListLoaded = false;
        send(new SocialPackets.RequestFollows(userListRequest, user, followers));
    }

    public static void requestReports() {
        send(new SocialPackets.RequestReports());
    }

    public static void report(ReportTarget type, long targetId, @Nullable UUID user, String reason) {
        send(new SocialPackets.Report(type, targetId, user, reason));
    }

    // --- Пакеты сервера ---

    public static void onSession() {
        // Права могли измениться — модераторские данные перезапросятся при показе.
        if (!ClientSession.has(dev.blockphone.common.protocol.data.SocialData.Rights.MOD_REPORTS)) {
            reports = null;
        }
    }

    public static void handle(Packet packet) {
        switch (packet) {
            case SocialPackets.FeedPage p -> onFeedPage(p);
            case SocialPackets.PostUpdated p -> onPostUpdated(p);
            case SocialPackets.PostRemoved p -> removePost(p.postId());
            case SocialPackets.Comments p -> onComments(p);
            case SocialPackets.CommentAdded p -> {
                Comments c = COMMENTS.get(p.comment().postId());
                if (c != null && c.loaded && c.list.stream().noneMatch(x -> x.id() == p.comment().id())) {
                    c.list.add(p.comment());
                }
            }
            case SocialPackets.CommentRemoved p -> {
                Comments c = COMMENTS.get(p.postId());
                if (c != null) {
                    c.list.removeIf(x -> x.id() == p.commentId());
                }
            }
            case SocialPackets.Profile p -> PROFILES.put(p.profile().user().uuid(), p.profile());
            case SocialPackets.PublicData p -> PUBLICS.put(p.info().id(), p.info());
            case SocialPackets.PublicRemoved p -> removePublic(p.publicId());
            case SocialPackets.PublicList p -> {
                SocialPackets.RequestPublics.Kind kind = PUBLIC_REQUESTS.remove(p.requestId());
                if (kind != null) {
                    List<Long> ids = new ArrayList<>();
                    for (PublicInfo info : p.publics()) {
                        PUBLICS.put(info.id(), info);
                        ids.add(info.id());
                    }
                    PUBLIC_LISTS.put(kind, ids);
                }
            }
            case SocialPackets.SearchResults p -> {
                if (p.requestId() == searchRequest) {
                    searchUsers = p.users();
                    List<Long> ids = new ArrayList<>();
                    for (PublicInfo info : p.publics()) {
                        PUBLICS.put(info.id(), info);
                        ids.add(info.id());
                    }
                    searchPublics = ids;
                    searchLoaded = true;
                }
            }
            case SocialPackets.UserList p -> {
                if (p.requestId() == userListRequest) {
                    userList = p.users();
                    userListLoaded = true;
                }
            }
            case SocialPackets.Reports p -> {
                reports = p.reports();
                ClientSession.setOpenReports(p.reports().size());
            }
            case SocialPackets.Notice p -> onNotice(p);
            case SocialPackets.Failure p -> {
                String arg = p.arg();
                if (p.key().equals("social.error.muted")) {
                    arg = arg.isEmpty() ? PhoneLang.tr("social.forever") : SocialFormat.dateTime(Long.parseLong(arg));
                }
                showMessage(PhoneLang.tr(p.key(), arg), true);
                for (Feed feed : FEEDS.values()) {
                    feed.loading = false;
                }
            }
            case SocialPackets.Done p -> showMessage(PhoneLang.tr(p.key()), false);
            default -> {
            }
        }
    }

    private static void onFeedPage(SocialPackets.FeedPage p) {
        FeedRequest request = FEED_REQUESTS.remove(p.requestId());
        if (request == null) {
            return;
        }
        Feed feed = FEEDS.get(request.key());
        if (feed == null) {
            return;
        }
        if (request.refresh()) {
            feed.ids.clear();
        }
        for (PostInfo post : p.posts()) {
            POSTS.put(post.id(), post);
            if (!feed.ids.contains(post.id())) {
                feed.ids.add(post.id());
            }
        }
        feed.hasMore = p.hasMore();
        feed.loaded = true;
        feed.loading = false;
    }

    private static void onPostUpdated(SocialPackets.PostUpdated p) {
        PostInfo post = p.post();
        POSTS.put(post.id(), post);
        if (p.nonce().isEmpty()) {
            return;
        }
        // Свой новый пост — сверху в подходящих лентах.
        createdNonce = p.nonce();
        createdPost = post;
        List<String> keys = new ArrayList<>(List.of(FeedKind.FOLLOWING.name(), FeedKind.ALL.name()));
        keys.add(post.pub() != null ? feedKey(FeedKind.PUBLIC, null, post.pub().id()) : feedKey(FeedKind.USER, post.author().uuid(), 0));
        for (String key : keys) {
            Feed feed = FEEDS.get(key);
            if (feed != null && feed.loaded && !feed.ids.contains(post.id())) {
                feed.ids.add(0, post.id());
            }
        }
        ProfileInfo profile = PROFILES.get(post.author().uuid());
        if (profile != null && post.pub() == null) {
            PROFILES.put(profile.user().uuid(), new ProfileInfo(profile.user(), profile.bio(), profile.posts() + 1, profile.followers(),
                    profile.following(), profile.followed(), profile.followsYou(), profile.lastSeen(), profile.mutedUntil(), profile.look(),
                    profile.canMessage(), profile.blocked()));
        }
    }

    private static void onComments(SocialPackets.Comments p) {
        Comments c = comments(p.postId());
        if (p.older()) {
            c.list.addAll(0, p.comments());
            c.loadingOlder = false;
        } else {
            c.list.clear();
            c.list.addAll(p.comments());
            c.loaded = true;
        }
        c.hasMore = p.hasMore();
    }

    private static void removePost(long postId) {
        POSTS.remove(postId);
        COMMENTS.remove(postId);
        for (Feed feed : FEEDS.values()) {
            feed.ids.remove(postId);
        }
    }

    private static void removePublic(long publicId) {
        PUBLICS.remove(publicId);
        for (List<Long> ids : PUBLIC_LISTS.values()) {
            ids.remove(publicId);
        }
        List<Long> posts = new ArrayList<>();
        for (PostInfo post : POSTS.values()) {
            if (post.pub() != null && post.pub().id() == publicId) {
                posts.add(post.id());
            }
        }
        posts.forEach(SocialClient::removePost);
        FEEDS.remove(feedKey(FeedKind.PUBLIC, null, publicId));
    }

    private static void onNotice(SocialPackets.Notice p) {
        if (p.type() == NoticeType.NEW_POST) {
            if (!SocialNotifications.feedOpen()) {
                newPosts++;
            }
            return;
        }
        if (p.type() == NoticeType.REPORT) {
            ClientSession.setOpenReports(ClientSession.openReports() + 1);
            reports = null;
        }
        SocialNotifications.notify(p);
    }

    public static void reset() {
        FEEDS.clear();
        FEED_REQUESTS.clear();
        POSTS.clear();
        COMMENTS.clear();
        PROFILES.clear();
        PUBLICS.clear();
        PUBLIC_LISTS.clear();
        PUBLIC_REQUESTS.clear();
        searchUsers = List.of();
        searchPublics = List.of();
        searchLoaded = false;
        userList = List.of();
        userListLoaded = false;
        reports = null;
        newPosts = 0;
        createdNonce = null;
        createdPost = null;
        message = null;
    }

    public static boolean isSelf(UUID uuid) {
        return uuid.equals(self());
    }

    /** Свой UUID (для «это мой профиль»). */
    public static @Nullable UUID self() {
        return dev.blockphone.fabric.client.messenger.MessengerClient.self();
    }

    private SocialClient() {
    }
}

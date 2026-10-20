package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData;
import dev.blockphone.common.protocol.data.SocialData.CommentInfo;
import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.NoticeType;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.ProfileInfo;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.PublicRef;
import dev.blockphone.common.protocol.data.SocialData.ReportInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.data.SocialData.Role;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.server.ImageRepository.ImageMeta;
import dev.blockphone.common.server.SocialRepository.CommentRow;
import dev.blockphone.common.server.SocialRepository.PostRow;
import dev.blockphone.common.server.SocialRepository.PublicRow;
import dev.blockphone.common.server.SocialRepository.ReportRow;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Лента: посты (от себя и от пабликов), лайки, комментарии, профили и подписки, паблики, жалобы и модерация.
 * Работает в фоновом потоке {@link Backend}.
 */
public final class SocialService {
    private static final int SEARCH_LIMIT = 20;
    private static final int POPULAR_LIMIT = 30;
    private static final int FOLLOW_LIST_LIMIT = 200;
    private static final long MUTE_FOREVER = Long.MAX_VALUE;
    private static final Set<String> REASONS = Set.of("spam", "abuse", "photo", "other");

    private final Backend backend;
    private final SocialRepository repo;
    private final RateLimiter postLimit = new RateLimiter(5, 60_000);
    private final RateLimiter commentLimit = new RateLimiter(10, 60_000);
    private final RateLimiter likeLimit = new RateLimiter(30, 10_000);
    private final RateLimiter reportLimit = new RateLimiter(5, 600_000);
    private final RateLimiter publicLimit = new RateLimiter(3, 600_000);
    /** Какой пост сейчас открыт у игрока (ему приходят новые комментарии и счётчики). */
    private final Map<UUID, Long> viewing = new HashMap<>();

    SocialService(Backend backend) {
        this.backend = backend;
        this.repo = new SocialRepository(backend.database());
    }

    void forget(UUID player) {
        viewing.remove(player);
        postLimit.forget(player);
        commentLimit.forget(player);
        likeLimit.forget(player);
        reportLimit.forget(player);
        publicLimit.forget(player);
    }

    long mutedUntil(UUID player) throws SQLException {
        return repo.mutedUntil(player, System.currentTimeMillis());
    }

    int openReports() throws SQLException {
        return repo.openReportCount();
    }

    /** Пост существует и не удалён (для выдачи его фото). */
    boolean postVisible(long postId) throws SQLException {
        PostRow row = repo.post(postId, new UUID(0, 0));
        return row != null && !row.deleted();
    }

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case SocialPackets.RequestFeed p -> feed(player, p);
            case SocialPackets.CreatePost p -> createPost(player, p);
            case SocialPackets.DeletePost p -> deletePost(player, p.postId());
            case SocialPackets.LikePost p -> like(player, p);
            case SocialPackets.RequestComments p -> comments(player, p);
            case SocialPackets.AddComment p -> addComment(player, p);
            case SocialPackets.DeleteComment p -> deleteComment(player, p.commentId());
            case SocialPackets.RequestProfile p -> sendProfile(player, p.user());
            case SocialPackets.UpdateBio p -> updateBio(player, p);
            case SocialPackets.Follow p -> follow(player, p);
            case SocialPackets.RequestFollows p -> follows(player, p);
            case SocialPackets.RequestPublic p -> sendPublic(player, p.publicId());
            case SocialPackets.CreatePublic p -> createPublic(player, p);
            case SocialPackets.PublicAction p -> publicAction(player, p);
            case SocialPackets.RequestPublics p -> publics(player, p);
            case SocialPackets.Search p -> search(player, p);
            case SocialPackets.Report p -> report(player, p);
            case SocialPackets.RequestReports p -> sendReports(player);
            case SocialPackets.ResolveReport p -> resolve(player, p);
            case SocialPackets.Mute p -> mute(player, p);
            default -> {
            }
        }
    }

    // --- Лента и посты ---

    private void feed(UUID player, SocialPackets.RequestFeed p) throws SQLException {
        if ((p.kind() == FeedKind.USER && p.user() == null) || (p.kind() == FeedKind.PUBLIC && p.publicId() <= 0)) {
            return;
        }
        int page = SocialData.FEED_PAGE;
        List<PostRow> rows = repo.feed(p.kind(), player, p.user(), p.publicId(), p.beforeId(), page + 1);
        boolean hasMore = rows.size() > page;
        if (hasMore) {
            rows = rows.subList(0, page);
        }
        send(player, new SocialPackets.FeedPage(p.requestId(), postInfos(rows, player), hasMore));
    }

    private void createPost(UUID player, SocialPackets.CreatePost p) throws SQLException {
        String text = p.text().strip();
        if (text.isEmpty() && p.imageId() <= 0) {
            fail(player, "empty");
            return;
        }
        if (text.length() > SocialData.MAX_POST_LENGTH) {
            fail(player, "too_long");
            return;
        }
        if (!canWrite(player)) {
            return;
        }
        if (p.publicId() > 0) {
            PublicRow pub = repo.publicRow(p.publicId());
            if (pub == null || role(pub, player) == Role.NONE) {
                fail(player, "not_admin");
                return;
            }
        }
        ImageMeta image = null;
        if (p.imageId() > 0) {
            String error = backend.images().checkAllowed(player, ImageRef.Purpose.POST);
            image = backend.imageRepo().meta(p.imageId());
            if (error == null && (image == null || !image.owner().equals(player) || image.kind() != ImageRepository.KIND_UNUSED)) {
                error = "photo.error.not_found";
            }
            if (error != null) {
                send(player, new SocialPackets.Failure(error, ""));
                return;
            }
        }
        if (!postLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        long now = System.currentTimeMillis();
        long id = repo.insertPost(player, p.publicId(), text, image != null ? image.id() : 0, now);
        if (image != null) {
            backend.imageRepo().attach(image.id(), ImageRepository.KIND_POST, id);
        }
        PostRow row = repo.post(id, player);
        send(player, new SocialPackets.PostUpdated(postInfos(List.of(row), player).get(0), p.nonce()));

        // Уведомить подписчиков, которые сейчас онлайн.
        Collection<UUID> audience = p.publicId() > 0 ? repo.subscribers(p.publicId()) : repo.followers(player, Integer.MAX_VALUE);
        UserInfo author = backend.user(player, backend.online().get(player));
        String excerpt = excerpt(text);
        for (UUID uuid : audience) {
            if (!uuid.equals(player) && backend.isOnline(uuid)) {
                send(uuid, new SocialPackets.Notice(NoticeType.NEW_POST, author, id, excerpt));
            }
        }
    }

    /** Можно ли игроку писать (посты, комментарии): право и не заглушён. Сам отправляет ошибку. */
    private boolean canWrite(UUID player) throws SQLException {
        if (!backend.has(player, Permission.POST)) {
            fail(player, "no_permission");
            return false;
        }
        long muted = mutedUntil(player);
        if (muted > 0) {
            send(player, new SocialPackets.Failure("social.error.muted", muted == MUTE_FOREVER ? "" : String.valueOf(muted)));
            return false;
        }
        return true;
    }

    private void deletePost(UUID player, long postId) throws SQLException {
        PostRow row = repo.post(postId, player);
        if (row == null || row.deleted()) {
            return;
        }
        boolean own = row.author().equals(player) || (row.publicId() > 0 && managedPublicIds(player).contains(row.publicId()));
        if (!own && !backend.has(player, Permission.MOD_POSTS)) {
            fail(player, "no_permission");
            return;
        }
        removePost(row, player, !own);
    }

    /** Удалить пост (moderation — модератором чужой пост: пишется в журнал). */
    private void removePost(PostRow row, UUID by, boolean moderation) throws SQLException {
        repo.deletePost(row.id());
        backend.imageRepo().delete(row.imageId());
        repo.resolveReports(ReportTarget.POST, row.id(), row.author(), 2, by, System.currentTimeMillis());
        if (moderation) {
            log(by, "delete_post", "post#" + row.id() + " by " + row.author(), excerpt(row.text()));
        }
        broadcast(new SocialPackets.PostRemoved(row.id()));
    }

    private void like(UUID player, SocialPackets.LikePost p) throws SQLException {
        PostRow row = repo.post(p.postId(), player);
        if (row == null || row.deleted() || !likeLimit.allow(player)) {
            return;
        }
        if (p.like()) {
            if (repo.like(row.id(), player, System.currentTimeMillis()) && !row.author().equals(player) && backend.isOnline(row.author())) {
                send(row.author(), new SocialPackets.Notice(NoticeType.LIKE, backend.user(player, backend.online().get(player)), row.id(),
                        excerpt(row.text())));
            }
        } else {
            repo.unlike(row.id(), player);
        }
        pushPost(row.id(), withViewers(row.id(), player));
    }

    /** Отправить каждому получателю актуальный пост (со своим «лайкнул ли я»). */
    private void pushPost(long postId, Collection<UUID> recipients) throws SQLException {
        for (UUID uuid : recipients) {
            PostRow fresh = repo.post(postId, uuid);
            if (fresh != null && !fresh.deleted()) {
                send(uuid, new SocialPackets.PostUpdated(postInfos(List.of(fresh), uuid).get(0), ""));
            }
        }
    }

    /** Игроки онлайн, у которых открыт этот пост, плюс extra. */
    private Set<UUID> withViewers(long postId, UUID extra) {
        Set<UUID> result = new LinkedHashSet<>();
        if (extra != null) {
            result.add(extra);
        }
        viewing.forEach((uuid, id) -> {
            if (id == postId && backend.isOnline(uuid)) {
                result.add(uuid);
            }
        });
        return result;
    }

    // --- Комментарии ---

    private void comments(UUID player, SocialPackets.RequestComments p) throws SQLException {
        PostRow post = repo.post(p.postId(), player);
        if (post == null || post.deleted()) {
            send(player, new SocialPackets.PostRemoved(p.postId()));
            return;
        }
        int page = SocialData.COMMENTS_PAGE;
        List<CommentRow> rows = repo.comments(post.id(), p.beforeId(), page + 1);
        boolean hasMore = rows.size() > page;
        if (hasMore) {
            rows = rows.subList(0, page);
        }
        List<CommentRow> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        if (p.beforeId() <= 0) {
            viewing.put(player, post.id());
            send(player, new SocialPackets.PostUpdated(postInfos(List.of(post), player).get(0), ""));
        }
        send(player, new SocialPackets.Comments(post.id(), commentInfos(ordered, post, player), p.beforeId() > 0, hasMore));
    }

    private void addComment(UUID player, SocialPackets.AddComment p) throws SQLException {
        String text = p.text().strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > SocialData.MAX_COMMENT_LENGTH) {
            fail(player, "too_long");
            return;
        }
        PostRow post = repo.post(p.postId(), player);
        if (post == null || post.deleted()) {
            send(player, new SocialPackets.PostRemoved(p.postId()));
            return;
        }
        if (!canWrite(player)) {
            return;
        }
        if (!commentLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        long id = repo.insertComment(post.id(), player, text, System.currentTimeMillis());
        CommentRow row = repo.comment(id);
        Set<UUID> recipients = withViewers(post.id(), player);
        for (UUID uuid : recipients) {
            send(uuid, new SocialPackets.CommentAdded(commentInfos(List.of(row), post, uuid).get(0)));
        }
        pushPost(post.id(), recipients);
        if (!post.author().equals(player) && backend.isOnline(post.author())) {
            send(post.author(), new SocialPackets.Notice(NoticeType.COMMENT, backend.user(player, backend.online().get(player)), post.id(),
                    excerpt(text)));
        }
    }

    private void deleteComment(UUID player, long commentId) throws SQLException {
        CommentRow row = repo.comment(commentId);
        if (row == null || row.deleted()) {
            return;
        }
        PostRow post = repo.post(row.postId(), player);
        boolean own = row.author().equals(player) || (post != null && canManagePost(post, player, managedPublicIds(player)));
        if (!own && !backend.has(player, Permission.MOD_POSTS)) {
            fail(player, "no_permission");
            return;
        }
        removeComment(row, player, !own);
    }

    private void removeComment(CommentRow row, UUID by, boolean moderation) throws SQLException {
        repo.deleteComment(row.id());
        repo.resolveReports(ReportTarget.COMMENT, row.id(), row.author(), 2, by, System.currentTimeMillis());
        if (moderation) {
            log(by, "delete_comment", "comment#" + row.id() + " by " + row.author(), excerpt(row.text()));
        }
        Set<UUID> recipients = withViewers(row.postId(), backend.isOnline(by) ? by : null);
        for (UUID uuid : recipients) {
            send(uuid, new SocialPackets.CommentRemoved(row.postId(), row.id()));
        }
        pushPost(row.postId(), recipients);
    }

    // --- Профили и подписки ---

    private void sendProfile(UUID player, UUID target) throws SQLException {
        Map<UUID, String> names = backend.users().userNames(List.of(target));
        if (!names.containsKey(target)) {
            fail(player, "user_not_found");
            return;
        }
        boolean seeMute = player.equals(target) || backend.has(player, Permission.MOD_MUTE);
        ProfileInfo info = new ProfileInfo(backend.user(target, names.get(target)), repo.bio(target), repo.userPostCount(target),
                repo.followerCount(target), repo.followingCount(target), repo.isFollowing(player, target), repo.isFollowing(target, player),
                backend.users().lastSeen(target), seeMute ? mutedUntil(target) : 0);
        send(player, new SocialPackets.Profile(info));
    }

    private void updateBio(UUID player, SocialPackets.UpdateBio p) throws SQLException {
        String bio = p.bio().strip();
        if (bio.length() > SocialData.MAX_BIO_LENGTH) {
            fail(player, "too_long");
            return;
        }
        if (!canWrite(player)) {
            return;
        }
        repo.setBio(player, bio, System.currentTimeMillis());
        sendProfile(player, player);
    }

    private void follow(UUID player, SocialPackets.Follow p) throws SQLException {
        UUID target = p.user();
        if (target.equals(player) || !backend.users().userExists(target)) {
            fail(player, "user_not_found");
            return;
        }
        if (p.follow()) {
            if (repo.follow(player, target, System.currentTimeMillis()) && backend.isOnline(target)) {
                send(target, new SocialPackets.Notice(NoticeType.FOLLOW, backend.user(player, backend.online().get(player)), 0, ""));
            }
        } else {
            repo.unfollow(player, target);
        }
        sendProfile(player, target);
    }

    private void follows(UUID player, SocialPackets.RequestFollows p) throws SQLException {
        List<UUID> ids = p.followers() ? repo.followers(p.user(), FOLLOW_LIST_LIMIT) : repo.following(p.user(), FOLLOW_LIST_LIMIT);
        send(player, new SocialPackets.UserList(p.requestId(), users(ids)));
    }

    // --- Паблики ---

    private Role role(PublicRow pub, UUID player) throws SQLException {
        if (pub.owner().equals(player)) {
            return Role.OWNER;
        }
        return repo.isAdmin(pub.id(), player) ? Role.ADMIN : Role.NONE;
    }

    private PublicInfo publicInfo(PublicRow row, UUID viewer) throws SQLException {
        List<UUID> adminIds = repo.admins(row.id());
        List<UUID> ids = new ArrayList<>(adminIds);
        ids.add(row.owner());
        Map<UUID, String> names = backend.users().userNames(ids);
        List<UserInfo> admins = adminIds.stream().map(u -> backend.user(u, names.get(u))).toList();
        return new PublicInfo(row.id(), row.title(), row.description(), row.color(), backend.user(row.owner(), names.get(row.owner())),
                admins, repo.subscriberCount(row.id()), repo.publicPostCount(row.id()), repo.isSubscribed(row.id(), viewer),
                role(row, viewer), row.createdAt());
    }

    private void sendPublic(UUID player, long publicId) throws SQLException {
        PublicRow row = repo.publicRow(publicId);
        if (row == null) {
            send(player, new SocialPackets.PublicRemoved(publicId));
            return;
        }
        send(player, new SocialPackets.PublicData(publicInfo(row, player)));
    }

    /** null — название и описание подходят, иначе ключ ошибки. */
    private String checkPublicText(String title, String description, long exceptId) throws SQLException {
        if (title.isEmpty() || title.length() > SocialData.MAX_PUBLIC_TITLE) {
            return "bad_title";
        }
        if (description.length() > SocialData.MAX_PUBLIC_DESCRIPTION) {
            return "too_long";
        }
        return repo.titleTaken(title, exceptId) ? "title_taken" : null;
    }

    private void createPublic(UUID player, SocialPackets.CreatePublic p) throws SQLException {
        String title = p.title().strip();
        String description = p.description().strip();
        if (!backend.has(player, Permission.CREATE_PUBLIC)) {
            fail(player, "no_permission");
            return;
        }
        if (!canWrite(player)) {
            return;
        }
        String error = checkPublicText(title, description, 0);
        if (error != null) {
            fail(player, error);
            return;
        }
        int max = backend.settings().social().maxPublicsPerPlayer();
        if (repo.ownedPublicCount(player) >= max && !backend.has(player, Permission.MOD_PUBLICS)) {
            send(player, new SocialPackets.Failure("social.error.public_limit", String.valueOf(max)));
            return;
        }
        if (!publicLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        long now = System.currentTimeMillis();
        int color = SocialData.PUBLIC_COLORS[Math.floorMod(title.toLowerCase(Locale.ROOT).hashCode(), SocialData.PUBLIC_COLORS.length)];
        long id = repo.createPublic(title, description, color, player, now);
        repo.subscribe(id, player, now);
        backend.platform().info(backend.online().get(player) + " created public #" + id + " \"" + title + "\"");
        sendPublic(player, id);
    }

    private void publicAction(UUID player, SocialPackets.PublicAction p) throws SQLException {
        PublicRow row = repo.publicRow(p.publicId());
        if (row == null) {
            send(player, new SocialPackets.PublicRemoved(p.publicId()));
            return;
        }
        Role role = role(row, player);
        long now = System.currentTimeMillis();
        switch (p.action()) {
            case SUBSCRIBE -> repo.subscribe(row.id(), player, now);
            case UNSUBSCRIBE -> repo.unsubscribe(row.id(), player);
            case EDIT -> {
                boolean mod = role == Role.NONE;
                if (mod && !backend.has(player, Permission.MOD_PUBLICS)) {
                    fail(player, "not_admin");
                    return;
                }
                String title = p.title().strip();
                String description = p.description().strip();
                String error = checkPublicText(title, description, row.id());
                if (error != null) {
                    fail(player, error);
                    return;
                }
                repo.updatePublic(row.id(), title, description);
                if (mod) {
                    log(player, "edit_public", "public#" + row.id(), row.title() + " -> " + title);
                }
            }
            case ADD_ADMIN, REMOVE_ADMIN -> {
                UUID target = p.user();
                boolean self = target != null && target.equals(player) && p.action() == SocialPackets.PublicAction.Action.REMOVE_ADMIN;
                if (target == null || (role != Role.OWNER && !self && !backend.has(player, Permission.MOD_PUBLICS))) {
                    fail(player, "not_owner");
                    return;
                }
                if (p.action() == SocialPackets.PublicAction.Action.ADD_ADMIN) {
                    if (target.equals(row.owner()) || !backend.users().userExists(target)) {
                        fail(player, "user_not_found");
                        return;
                    }
                    if (repo.admins(row.id()).size() >= SocialData.MAX_PUBLIC_ADMINS) {
                        send(player, new SocialPackets.Failure("social.error.admins_full", String.valueOf(SocialData.MAX_PUBLIC_ADMINS)));
                        return;
                    }
                    repo.addAdmin(row.id(), target, now);
                    repo.subscribe(row.id(), target, now);
                } else {
                    repo.removeAdmin(row.id(), target);
                }
            }
            case DELETE -> {
                boolean mod = role != Role.OWNER;
                if (mod && !backend.has(player, Permission.MOD_PUBLICS)) {
                    fail(player, "not_owner");
                    return;
                }
                removePublic(row, player, mod);
                return;
            }
        }
        sendPublic(player, row.id());
    }

    private void removePublic(PublicRow row, UUID by, boolean moderation) throws SQLException {
        for (long imageId : repo.deletePublic(row.id())) {
            backend.imageRepo().delete(imageId);
        }
        repo.resolveReports(ReportTarget.PUBLIC, row.id(), row.owner(), 2, by, System.currentTimeMillis());
        if (moderation) {
            log(by, "delete_public", "public#" + row.id() + " by " + row.owner(), row.title());
        }
        broadcast(new SocialPackets.PublicRemoved(row.id()));
    }

    private void publics(UUID player, SocialPackets.RequestPublics p) throws SQLException {
        List<PublicRow> rows = switch (p.kind()) {
            case SUBSCRIBED -> repo.subscribedPublics(player);
            case MANAGED -> repo.managedPublics(player);
            case POPULAR -> repo.popularPublics(POPULAR_LIMIT);
        };
        List<PublicInfo> result = new ArrayList<>();
        for (PublicRow row : rows) {
            result.add(publicInfo(row, player));
        }
        send(player, new SocialPackets.PublicList(p.requestId(), result));
    }

    private void search(UUID player, SocialPackets.Search p) throws SQLException {
        String query = p.query().strip();
        List<UserInfo> users = new ArrayList<>();
        List<PublicRow> publicRows;
        if (query.isEmpty()) {
            backend.online().forEach((uuid, name) -> {
                if (!uuid.equals(player)) {
                    users.add(new UserInfo(uuid, name, true));
                }
            });
            users.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            publicRows = List.of();
        } else {
            backend.users().searchUsers(query, SEARCH_LIMIT + 1).forEach((uuid, name) -> {
                if (!uuid.equals(player) && users.size() < SEARCH_LIMIT) {
                    users.add(backend.user(uuid, name));
                }
            });
            publicRows = repo.searchPublics(query, SEARCH_LIMIT);
        }
        List<PublicInfo> publics = new ArrayList<>();
        for (PublicRow row : publicRows) {
            publics.add(publicInfo(row, player));
        }
        send(player, new SocialPackets.SearchResults(p.requestId(), users, publics));
    }

    // --- Жалобы и модерация ---

    private void report(UUID player, SocialPackets.Report p) throws SQLException {
        String reason = REASONS.contains(p.reason()) ? p.reason() : "other";
        UUID targetUser;
        long targetId = p.targetId();
        switch (p.type()) {
            case POST -> {
                PostRow row = repo.post(targetId, player);
                targetUser = row != null && !row.deleted() ? row.author() : null;
            }
            case COMMENT -> {
                CommentRow row = repo.comment(targetId);
                targetUser = row != null && !row.deleted() ? row.author() : null;
            }
            case PUBLIC -> {
                PublicRow row = repo.publicRow(targetId);
                targetUser = row != null ? row.owner() : null;
            }
            default -> {
                targetId = 0;
                targetUser = p.targetUser() != null && backend.users().userExists(p.targetUser()) ? p.targetUser() : null;
            }
        }
        if (targetUser == null || targetUser.equals(player)) {
            return;
        }
        if (!repo.hasOpenReport(player, p.type(), targetId, targetUser)) {
            if (!reportLimit.allow(player)) {
                fail(player, "rate_limit");
                return;
            }
            repo.insertReport(player, p.type(), targetId, targetUser, reason, System.currentTimeMillis());
            UserInfo reporter = backend.user(player, backend.online().get(player));
            for (UUID uuid : backend.online().keySet()) {
                if (backend.has(uuid, Permission.MOD_REPORTS)) {
                    send(uuid, new SocialPackets.Notice(NoticeType.REPORT, reporter, 0, reason));
                }
            }
        }
        send(player, new SocialPackets.Done("social.done.reported"));
    }

    private void sendReports(UUID player) throws SQLException {
        if (!backend.has(player, Permission.MOD_REPORTS)) {
            fail(player, "no_permission");
            return;
        }
        List<ReportRow> rows = repo.openReports(100);
        Set<UUID> ids = new HashSet<>();
        for (ReportRow row : rows) {
            ids.add(row.reporter());
            ids.add(row.targetUser());
        }
        Map<UUID, String> names = backend.users().userNames(ids);
        List<ReportInfo> result = new ArrayList<>();
        for (ReportRow row : rows) {
            result.add(new ReportInfo(row.id(), backend.user(row.reporter(), names.get(row.reporter())), row.type(), row.targetId(),
                    backend.user(row.targetUser(), names.get(row.targetUser())), reportExcerpt(row), row.reason(), row.createdAt()));
        }
        send(player, new SocialPackets.Reports(result));
    }

    private String reportExcerpt(ReportRow row) throws SQLException {
        return switch (row.type()) {
            case POST -> {
                PostRow post = repo.post(row.targetId(), row.reporter());
                yield post == null ? "" : excerpt(post.text()) + (post.imageId() > 0 ? " [photo]" : "");
            }
            case COMMENT -> {
                CommentRow comment = repo.comment(row.targetId());
                yield comment == null ? "" : excerpt(comment.text());
            }
            case PUBLIC -> {
                PublicRow pub = repo.publicRow(row.targetId());
                yield pub == null ? "" : pub.title();
            }
            case USER -> "";
        };
    }

    private void resolve(UUID player, SocialPackets.ResolveReport p) throws SQLException {
        if (!backend.has(player, Permission.MOD_REPORTS)) {
            fail(player, "no_permission");
            return;
        }
        ReportRow report = repo.report(p.reportId());
        if (report == null) {
            sendReports(player);
            return;
        }
        long now = System.currentTimeMillis();
        SocialPackets.ResolveReport.Resolution resolution = p.resolution();
        if (resolution == SocialPackets.ResolveReport.Resolution.DISMISS) {
            repo.resolveReports(report.type(), report.targetId(), report.targetUser(), 1, player, now);
            log(player, "dismiss_report", "report#" + report.id(), report.type() + " " + report.targetId());
            sendReports(player);
            return;
        }
        boolean mute = resolution == SocialPackets.ResolveReport.Resolution.DELETE_AND_MUTE;
        Permission needed = report.type() == ReportTarget.PUBLIC ? Permission.MOD_PUBLICS : Permission.MOD_POSTS;
        if ((report.type() != ReportTarget.USER && !backend.has(player, needed)) || (mute && !backend.has(player, Permission.MOD_MUTE))) {
            fail(player, "no_permission");
            return;
        }
        switch (report.type()) {
            case POST -> {
                PostRow row = repo.post(report.targetId(), player);
                if (row != null && !row.deleted()) {
                    removePost(row, player, true);
                }
            }
            case COMMENT -> {
                CommentRow row = repo.comment(report.targetId());
                if (row != null && !row.deleted()) {
                    removeComment(row, player, true);
                }
            }
            case PUBLIC -> {
                PublicRow row = repo.publicRow(report.targetId());
                if (row != null) {
                    removePublic(row, player, true);
                }
            }
            case USER -> {
            }
        }
        repo.resolveReports(report.type(), report.targetId(), report.targetUser(), 2, player, now);
        if (mute) {
            applyMute(player, report.targetUser(), 24);
        }
        sendReports(player);
    }

    private void mute(UUID player, SocialPackets.Mute p) throws SQLException {
        if (!backend.has(player, Permission.MOD_MUTE)) {
            fail(player, "no_permission");
            return;
        }
        if (p.user().equals(player) || !backend.users().userExists(p.user())) {
            fail(player, "user_not_found");
            return;
        }
        applyMute(player, p.user(), p.hours());
        send(player, new SocialPackets.Done(p.hours() == 0 ? "social.done.unmuted" : "social.done.muted"));
        sendProfile(player, p.user());
    }

    /** hours: > 0 — на столько часов, -1 — навсегда, 0 — снять. */
    private void applyMute(UUID by, UUID target, int hours) throws SQLException {
        long now = System.currentTimeMillis();
        if (hours == 0) {
            repo.unmute(target);
            log(by, "unmute", target.toString(), "");
        } else {
            long until = hours < 0 ? MUTE_FOREVER : now + Math.min(hours, 24L * 365) * 3600_000L;
            repo.mute(target, until, by, now);
            log(by, "mute", target.toString(), hours < 0 ? "forever" : hours + "h");
        }
        if (backend.isOnline(target)) {
            backend.sendSession(target);
        }
    }

    private void log(UUID moderator, String action, String target, String details) throws SQLException {
        repo.log(moderator, action, target, details, System.currentTimeMillis());
        backend.platform().info("[moderation] " + backend.online().getOrDefault(moderator, moderator.toString()) + ": " + action + " "
                + target + (details.isEmpty() ? "" : " (" + details + ")"));
    }

    // --- Сборка данных ---

    private Set<Long> managedPublicIds(UUID player) throws SQLException {
        Set<Long> ids = new HashSet<>();
        for (PublicRow row : repo.managedPublics(player)) {
            ids.add(row.id());
        }
        return ids;
    }

    private static boolean canManagePost(PostRow post, UUID viewer, Set<Long> managed) {
        return post.author().equals(viewer) || (post.publicId() > 0 && managed.contains(post.publicId()));
    }

    private List<PostInfo> postInfos(List<PostRow> rows, UUID viewer) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = backend.users().userNames(rows.stream().map(PostRow::author).toList());
        Map<Long, PublicRef> pubs = new HashMap<>();
        for (PublicRow row : repo.publicRows(rows.stream().map(PostRow::publicId).toList())) {
            pubs.put(row.id(), new PublicRef(row.id(), row.title(), row.color()));
        }
        Map<Long, ImageRef> images = backend.imageRepo().refs(rows.stream().map(PostRow::imageId).toList());
        Set<Long> managed = managedPublicIds(viewer);
        boolean moderator = backend.has(viewer, Permission.MOD_POSTS);
        List<PostInfo> result = new ArrayList<>(rows.size());
        for (PostRow row : rows) {
            result.add(new PostInfo(row.id(), backend.user(row.author(), names.get(row.author())), pubs.get(row.publicId()), row.text(),
                    images.get(row.imageId()), row.createdAt(), row.likes(), row.liked(), row.comments(),
                    moderator || canManagePost(row, viewer, managed)));
        }
        return result;
    }

    private List<CommentInfo> commentInfos(List<CommentRow> rows, PostRow post, UUID viewer) throws SQLException {
        Map<UUID, String> names = backend.users().userNames(rows.stream().map(CommentRow::author).toList());
        boolean manage = backend.has(viewer, Permission.MOD_POSTS) || canManagePost(post, viewer, managedPublicIds(viewer));
        List<CommentInfo> result = new ArrayList<>(rows.size());
        for (CommentRow row : rows) {
            result.add(new CommentInfo(row.id(), row.postId(), backend.user(row.author(), names.get(row.author())), row.text(),
                    row.createdAt(), manage || row.author().equals(viewer)));
        }
        return result;
    }

    private List<UserInfo> users(List<UUID> ids) throws SQLException {
        Map<UUID, String> names = backend.users().userNames(ids);
        List<UserInfo> result = new ArrayList<>();
        for (UUID id : ids) {
            if (names.containsKey(id)) {
                result.add(backend.user(id, names.get(id)));
            }
        }
        return result;
    }

    private static String excerpt(String text) {
        String flat = text.replace('\n', ' ').strip();
        return flat.length() > 120 ? flat.substring(0, 119) + "…" : flat;
    }

    private void fail(UUID player, String key) {
        send(player, new SocialPackets.Failure("social.error." + key, ""));
    }

    private void broadcast(Packet packet) {
        for (UUID uuid : backend.online().keySet()) {
            send(uuid, packet);
        }
    }

    private void send(UUID player, Packet packet) {
        backend.send(player, packet);
    }

    // --- Для отладки и тестов ---

    /** Выполнить действие с репозиторием ленты в фоновом потоке. */
    public void runInThread(SqlRunnable action) {
        backend.execute(() -> action.run(repo));
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run(SocialRepository repo) throws SQLException;
    }
}

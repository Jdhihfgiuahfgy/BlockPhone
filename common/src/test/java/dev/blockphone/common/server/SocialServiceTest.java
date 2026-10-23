package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.ImageRef.PhotoPolicy;
import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.NoticeType;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.data.SocialData.Role;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.SocialPackets.PublicAction.Action;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Лента, фото и модерация целиком (протокол + логика + SQL на H2), без Minecraft. */
class SocialServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID HEROBRINE = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @TempDir
    Path dir;
    FakePlatform platform;
    BlockPhoneServer server;
    String serverId;

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        ServerSettings.Storage storage = new ServerSettings.Storage("h2", dir.resolve("db"), "", 0, "", "", "", "bp_");
        ServerSettings.Social social = new ServerSettings.Social(true, PhotoPolicy.SERVER, 64 * 1024, 1280, 1);
        server = new BlockPhoneServer(platform, new ServerSettings(true, true, storage, social, List.of("Herobrine")));
        server.start();
        join(ALEX, "Alex");
        join(STEVE, "Steve");
        join(NOTCH, "Notch");
        serverId = platform.last(ALEX, SessionPacket.class).serverId();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private void join(UUID uuid, String name) throws Exception {
        server.onChannelReady(uuid);
        server.onMessage(uuid, name, Packets.encode(new ClientHelloPacket(BlockPhone.PROTOCOL_VERSION, "test")));
        flush();
    }

    private String name(UUID uuid) {
        return uuid.equals(ALEX) ? "Alex" : uuid.equals(STEVE) ? "Steve" : uuid.equals(NOTCH) ? "Notch" : "Herobrine";
    }

    private void send(UUID uuid, Packet packet) throws Exception {
        server.onMessage(uuid, name(uuid), Packets.encode(packet));
        flush();
    }

    private void flush() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        server.backend().execute(latch::countDown);
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    private PostInfo post(UUID author, long publicId, String text, long imageId) throws Exception {
        String nonce = "n" + System.nanoTime();
        send(author, new SocialPackets.CreatePost(nonce, publicId, text, imageId));
        SocialPackets.PostUpdated created = platform.last(author, SocialPackets.PostUpdated.class);
        assertEquals(nonce, created.nonce());
        return created.post();
    }

    private List<PostInfo> feed(UUID viewer, FeedKind kind, UUID user, long publicId) throws Exception {
        send(viewer, new SocialPackets.RequestFeed(77, kind, user, publicId, 0));
        SocialPackets.FeedPage page = platform.last(viewer, SocialPackets.FeedPage.class);
        assertEquals(77, page.requestId());
        return page.posts();
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Загрузить картинку кусками. Возвращает результат загрузки. */
    private ImagePackets.UploadResult upload(UUID player, byte[] data, ImageRef.Purpose purpose, String serverId) throws Exception {
        int id = (int) (System.nanoTime() & 0xFFFF);
        send(player, new ImagePackets.UploadBegin(id, data.length, purpose, serverId));
        for (int offset = 0; offset < data.length; offset += ImageRef.CHUNK) {
            byte[] part = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + ImageRef.CHUNK));
            server.onMessage(player, name(player), Packets.encode(new ImagePackets.UploadChunk(id, offset, part)));
        }
        flush();
        ImagePackets.UploadResult result = platform.last(player, ImagePackets.UploadResult.class);
        assertEquals(id, result.uploadId());
        return result;
    }

    /** Скачать картинку целиком (null — нет доступа). */
    private byte[] download(UUID player, long imageId) throws Exception {
        platform.inbox.remove(player);
        send(player, new ImagePackets.RequestImage(imageId));
        List<ImagePackets.ImageData> parts = platform.of(player, ImagePackets.ImageData.class);
        if (parts.isEmpty() || parts.get(0).total() == 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ImagePackets.ImageData part : parts) {
            assertEquals(out.size(), part.offset());
            out.writeBytes(part.data());
        }
        assertEquals(parts.get(0).total(), out.size());
        return out.toByteArray();
    }

    private String serverId() {
        return serverId;
    }

    @Test
    void sessionHasRightsAndPolicy() {
        SessionPacket session = platform.last(ALEX, SessionPacket.class);
        assertFalse(session.serverId().isBlank());
        assertTrue(session.socialEnabled());
        assertEquals(PhotoPolicy.SERVER, session.photoPolicy());
        assertTrue(Rights.has(session.rights(), Rights.POST));
        assertTrue(Rights.has(session.rights(), Rights.PHOTO));
        assertFalse(Rights.has(session.rights(), Rights.MOD_POSTS));
        // Один и тот же id у всех игроков.
        assertEquals(session.serverId(), platform.last(STEVE, SessionPacket.class).serverId());
    }

    @Test
    void postsFeedLikesComments() throws Exception {
        send(ALEX, new SocialPackets.Follow(STEVE, true));
        assertEquals(NoticeType.FOLLOW, platform.last(STEVE, SocialPackets.Notice.class).type());
        assertEquals(1, platform.last(ALEX, SocialPackets.Profile.class).profile().followers());

        PostInfo post = post(STEVE, 0, "  Построил маяк!  ", 0);
        assertEquals("Построил маяк!", post.text());
        assertTrue(post.canDelete());
        assertEquals(NoticeType.NEW_POST, platform.last(ALEX, SocialPackets.Notice.class).type());

        assertEquals(1, feed(ALEX, FeedKind.FOLLOWING, null, 0).size());
        assertTrue(feed(NOTCH, FeedKind.FOLLOWING, null, 0).isEmpty());
        assertEquals(1, feed(NOTCH, FeedKind.ALL, null, 0).size());
        assertEquals(1, feed(NOTCH, FeedKind.USER, STEVE, 0).size());
        assertFalse(feed(NOTCH, FeedKind.ALL, null, 0).get(0).canDelete());

        send(ALEX, new SocialPackets.LikePost(post.id(), true));
        PostInfo liked = platform.last(ALEX, SocialPackets.PostUpdated.class).post();
        assertEquals(1, liked.likes());
        assertTrue(liked.liked());
        assertEquals(NoticeType.LIKE, platform.last(STEVE, SocialPackets.Notice.class).type());

        // Steve смотрит пост — ему приходят новые комментарии.
        send(STEVE, new SocialPackets.RequestComments(post.id(), 0));
        send(ALEX, new SocialPackets.AddComment(post.id(), "Красиво!"));
        SocialPackets.CommentAdded added = platform.last(STEVE, SocialPackets.CommentAdded.class);
        assertEquals("Красиво!", added.comment().text());
        assertTrue(added.comment().canDelete(), "автор поста может удалять комментарии");
        assertEquals(1, platform.last(STEVE, SocialPackets.PostUpdated.class).post().comments());

        send(NOTCH, new SocialPackets.RequestComments(post.id(), 0));
        SocialPackets.Comments comments = platform.last(NOTCH, SocialPackets.Comments.class);
        assertEquals(1, comments.comments().size());
        assertFalse(comments.comments().get(0).canDelete());

        // Чужой пост удалить нельзя.
        send(ALEX, new SocialPackets.DeletePost(post.id()));
        assertEquals("social.error.no_permission", platform.last(ALEX, SocialPackets.Failure.class).key());

        send(STEVE, new SocialPackets.DeleteComment(added.comment().id()));
        assertEquals(added.comment().id(), platform.last(STEVE, SocialPackets.CommentRemoved.class).commentId());
        send(STEVE, new SocialPackets.DeletePost(post.id()));
        assertEquals(post.id(), platform.last(NOTCH, SocialPackets.PostRemoved.class).postId());
        assertTrue(feed(NOTCH, FeedKind.ALL, null, 0).isEmpty());
    }

    @Test
    void feedPaging() throws Exception {
        server.social().runInThread(repo -> {
            for (int i = 0; i < 30; i++) {
                repo.insertPost(ALEX, 0, "post " + i, 0, 1000 + i);
            }
        });
        flush();
        send(STEVE, new SocialPackets.RequestFeed(1, FeedKind.ALL, null, 0, 0));
        SocialPackets.FeedPage page1 = platform.last(STEVE, SocialPackets.FeedPage.class);
        assertTrue(page1.hasMore());
        assertEquals("post 29", page1.posts().get(0).text());
        long last = page1.posts().get(page1.posts().size() - 1).id();
        send(STEVE, new SocialPackets.RequestFeed(2, FeedKind.ALL, null, 0, last));
        SocialPackets.FeedPage page2 = platform.last(STEVE, SocialPackets.FeedPage.class);
        assertTrue(page2.posts().get(0).id() < last);
    }

    @Test
    void publicsAndAdmins() throws Exception {
        send(ALEX, new SocialPackets.CreatePublic("Новости сервера", "Всё о сервере"));
        SocialPackets.PublicData created = platform.last(ALEX, SocialPackets.PublicData.class);
        long publicId = created.info().id();
        assertEquals(Role.OWNER, created.info().role());
        assertTrue(created.info().subscribed());

        // Название занято (без учёта регистра); лимит пабликов на игрока — 1.
        send(STEVE, new SocialPackets.CreatePublic("новости СЕРВЕРА", ""));
        assertEquals("social.error.title_taken", platform.last(STEVE, SocialPackets.Failure.class).key());
        send(ALEX, new SocialPackets.CreatePublic("Второй", ""));
        assertEquals("social.error.public_limit", platform.last(ALEX, SocialPackets.Failure.class).key());

        send(STEVE, new SocialPackets.PublicAction(publicId, Action.SUBSCRIBE, "", "", null));
        assertEquals(2, platform.last(STEVE, SocialPackets.PublicData.class).info().subscribers());

        PostInfo news = post(ALEX, publicId, "Сервер обновлён до 1.21.11", 0);
        assertEquals("Новости сервера", news.pub().title());
        assertEquals(NoticeType.NEW_POST, platform.last(STEVE, SocialPackets.Notice.class).type());
        assertEquals(1, feed(STEVE, FeedKind.FOLLOWING, null, 0).size());
        assertEquals(1, feed(NOTCH, FeedKind.PUBLIC, null, publicId).size());

        // Steve не администратор — писать в паблик нельзя; после назначения — можно.
        send(STEVE, new SocialPackets.CreatePost("x", publicId, "Привет", 0));
        assertEquals("social.error.not_admin", platform.last(STEVE, SocialPackets.Failure.class).key());
        send(STEVE, new SocialPackets.PublicAction(publicId, Action.ADD_ADMIN, "", "", NOTCH));
        assertEquals("social.error.not_owner", platform.last(STEVE, SocialPackets.Failure.class).key());
        send(ALEX, new SocialPackets.PublicAction(publicId, Action.ADD_ADMIN, "", "", STEVE));
        assertEquals(1, platform.last(ALEX, SocialPackets.PublicData.class).info().admins().size());
        PostInfo byAdmin = post(STEVE, publicId, "Завтра ивент!", 0);
        assertTrue(byAdmin.canDelete());

        send(NOTCH, new SocialPackets.Search(5, "новости"));
        SocialPackets.SearchResults found = platform.last(NOTCH, SocialPackets.SearchResults.class);
        assertEquals(1, found.publics().size());

        send(ALEX, new SocialPackets.RequestPublics(9, SocialPackets.RequestPublics.Kind.MANAGED));
        assertEquals(1, platform.last(ALEX, SocialPackets.PublicList.class).publics().size());

        send(ALEX, new SocialPackets.PublicAction(publicId, Action.DELETE, "", "", null));
        assertEquals(publicId, platform.last(NOTCH, SocialPackets.PublicRemoved.class).publicId());
        assertTrue(feed(NOTCH, FeedKind.ALL, null, 0).isEmpty());
    }

    @Test
    void photoUploadPolicyAndAccess() throws Exception {
        byte[] image = png(40, 30);
        assertEquals("photo.error.not_this_server",
                upload(ALEX, image, ImageRef.Purpose.POST, "other-server").error());
        assertEquals("photo.error.too_big",
                upload(ALEX, new byte[70 * 1024], ImageRef.Purpose.POST, serverId()).error());

        ImagePackets.UploadResult ok = upload(ALEX, image, ImageRef.Purpose.POST, serverId());
        assertNotNull(ok.image(), ok.error());
        assertEquals(40, ok.image().width());
        assertEquals(30, ok.image().height());
        // Пока фото не прикреплено, его видит только владелец.
        assertNull(download(STEVE, ok.image().id()));
        assertArrayEquals(image, download(ALEX, ok.image().id()));

        PostInfo post = post(ALEX, 0, "", ok.image().id());
        assertEquals(ok.image().id(), post.image().id());
        assertArrayEquals(image, download(NOTCH, ok.image().id()));
        // Повторно прикрепить то же фото нельзя.
        send(ALEX, new SocialPackets.CreatePost("again", 0, "ещё раз", ok.image().id()));
        assertEquals("photo.error.not_found", platform.last(ALEX, SocialPackets.Failure.class).key());

        // Фото в личном чате видят только участники.
        send(ALEX, new MessengerPackets.OpenDirect(STEVE));
        long chatId = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        ImagePackets.UploadResult chatPhoto = upload(ALEX, png(8, 8), ImageRef.Purpose.CHAT, serverId());
        send(ALEX, new MessengerPackets.SendMessage(chatId, "p1", "", chatPhoto.image().id(), 0));
        MessengerPackets.NewMessage message = platform.last(STEVE, MessengerPackets.NewMessage.class);
        assertEquals(8, message.message().image().width());
        assertNotNull(download(STEVE, chatPhoto.image().id()));
        assertNull(download(NOTCH, chatPhoto.image().id()));

        // Без права на фото загрузка отклоняется.
        platform.deny(STEVE, Permission.PHOTO);
        assertEquals("photo.error.no_permission", upload(STEVE, image, ImageRef.Purpose.CHAT, serverId()).error());
    }

    @Test
    void reportsMutesAndModerators() throws Exception {
        platform.grant(NOTCH, Permission.MOD_REPORTS);
        platform.grant(NOTCH, Permission.MOD_POSTS);
        platform.grant(NOTCH, Permission.MOD_MUTE);
        send(NOTCH, new SessionPacket.Refresh());
        assertTrue(Rights.has(platform.last(NOTCH, SessionPacket.class).rights(), Rights.MOD_REPORTS));

        PostInfo spam = post(ALEX, 0, "Купи алмазы дёшево!!!", 0);
        send(STEVE, new SocialPackets.Report(ReportTarget.POST, spam.id(), null, "spam"));
        assertEquals("social.done.reported", platform.last(STEVE, SocialPackets.Done.class).key());
        assertEquals(NoticeType.REPORT, platform.last(NOTCH, SocialPackets.Notice.class).type());

        // Обычный игрок жалобы не видит.
        send(STEVE, new SocialPackets.RequestReports());
        assertEquals("social.error.no_permission", platform.last(STEVE, SocialPackets.Failure.class).key());

        send(NOTCH, new SocialPackets.RequestReports());
        SocialPackets.Reports reports = platform.last(NOTCH, SocialPackets.Reports.class);
        assertEquals(1, reports.reports().size());
        assertEquals("Alex", reports.reports().get(0).targetUser().name());
        assertTrue(reports.reports().get(0).excerpt().startsWith("Купи"));

        send(NOTCH, new SocialPackets.ResolveReport(reports.reports().get(0).id(),
                SocialPackets.ResolveReport.Resolution.DELETE_AND_MUTE));
        assertEquals(spam.id(), platform.last(STEVE, SocialPackets.PostRemoved.class).postId());
        assertTrue(platform.last(NOTCH, SocialPackets.Reports.class).reports().isEmpty());
        assertTrue(platform.last(ALEX, SessionPacket.class).mutedUntil() > System.currentTimeMillis());

        send(ALEX, new SocialPackets.CreatePost("m", 0, "Я снова тут", 0));
        assertEquals("social.error.muted", platform.last(ALEX, SocialPackets.Failure.class).key());
        send(ALEX, new SocialPackets.AddComment(spam.id(), "?"));

        send(NOTCH, new SocialPackets.Mute(ALEX, 0));
        assertEquals("social.done.unmuted", platform.last(NOTCH, SocialPackets.Done.class).key());
        assertEquals(0, platform.last(ALEX, SessionPacket.class).mutedUntil());
        post(ALEX, 0, "Исправился", 0);

        // Модератор из списка moderators в настройках получает все права.
        join(HEROBRINE, "Herobrine");
        int rights = platform.last(HEROBRINE, SessionPacket.class).rights();
        assertTrue(Rights.has(rights, Rights.MOD_PUBLICS) && Rights.has(rights, Rights.MOD_MUTE));
        // Узел blockphone.moderator тоже даёт все права.
        platform.grant(STEVE, Permission.MODERATOR);
        send(STEVE, new SessionPacket.Refresh());
        assertTrue(Rights.has(platform.last(STEVE, SessionPacket.class).rights(), Rights.MOD_POSTS));
    }

    @Test
    void profileBio() throws Exception {
        send(STEVE, new SocialPackets.UpdateBio("Строю замки"));
        assertEquals("Строю замки", platform.last(STEVE, SocialPackets.Profile.class).profile().bio());
        send(ALEX, new SocialPackets.Follow(STEVE, true));
        send(ALEX, new SocialPackets.RequestProfile(STEVE));
        var profile = platform.last(ALEX, SocialPackets.Profile.class).profile();
        assertEquals("Строю замки", profile.bio());
        assertTrue(profile.followed());
        assertEquals(0, profile.mutedUntil());
        send(ALEX, new SocialPackets.RequestFollows(3, STEVE, true));
        assertEquals("Alex", platform.last(ALEX, SocialPackets.UserList.class).users().get(0).name());
    }

    @Test
    void customAvatarsForProfileAndPublic() throws Exception {
        byte[] image = png(32, 32);
        ImagePackets.UploadResult photo = upload(ALEX, image, ImageRef.Purpose.POST, serverId());
        send(ALEX, new SocialPackets.UpdateLook(2, 0, 0, 0, -1, photo.image().id()));
        dev.blockphone.common.protocol.data.SocialData.ProfileLook look = platform.last(ALEX, SocialPackets.Profile.class).profile().look();
        assertEquals(2, look.avatarType());
        assertEquals(photo.image().id(), look.avatarImage().id());
        // Аватар видят все.
        assertArrayEquals(image, download(STEVE, photo.image().id()));

        send(ALEX, new SocialPackets.CreatePublic("Аватарки", ""));
        long publicId = platform.last(ALEX, SocialPackets.PublicData.class).info().id();
        ImagePackets.UploadResult pubPhoto = upload(ALEX, image, ImageRef.Purpose.POST, serverId());
        // Чужой паблик менять нельзя.
        send(STEVE, new SocialPackets.SetPublicAvatar(publicId, pubPhoto.image().id()));
        assertEquals("social.error.not_admin", platform.last(STEVE, SocialPackets.Failure.class).key());
        send(ALEX, new SocialPackets.SetPublicAvatar(publicId, pubPhoto.image().id()));
        assertEquals(pubPhoto.image().id(), platform.last(ALEX, SocialPackets.PublicData.class).info().avatar().id());
        PostInfo post = post(ALEX, publicId, "Привет", 0);
        assertEquals(pubPhoto.image().id(), post.pub().avatar().id());
        send(ALEX, new SocialPackets.SetPublicAvatar(publicId, 0));
        assertNull(platform.last(ALEX, SocialPackets.PublicData.class).info().avatar());
    }

    @Test
    void forwardMessagesAndSharePosts() throws Exception {
        send(ALEX, new MessengerPackets.OpenDirect(STEVE));
        long direct = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        ImagePackets.UploadResult photo = upload(STEVE, png(8, 8), ImageRef.Purpose.CHAT, serverId());
        send(STEVE, new MessengerPackets.SendMessage(direct, "s1", "смотри", photo.image().id(), 0));
        long original = platform.last(ALEX, MessengerPackets.NewMessage.class).message().id();

        send(ALEX, new MessengerPackets.CreateGroup("Друзья", List.of(NOTCH)));
        long group = platform.last(ALEX, MessengerPackets.ChatOpened.class).chatId();
        send(ALEX, new MessengerPackets.ForwardMessage(original, group, "f1"));
        MessengerPackets.NewMessage forwarded = platform.last(NOTCH, MessengerPackets.NewMessage.class);
        assertEquals("Steve", forwarded.message().forwardedFrom());
        assertEquals("смотри", forwarded.message().text());
        assertNotEquals(photo.image().id(), forwarded.message().image().id());
        assertNotNull(download(NOTCH, forwarded.message().image().id()));
        assertNull(download(NOTCH, photo.image().id()));
        assertEquals("f1", platform.last(ALEX, MessengerPackets.NewMessage.class).nonce());

        // Переслать дальше — автор остаётся первым.
        send(NOTCH, new MessengerPackets.OpenDirect(STEVE));
        long notchSteve = platform.last(NOTCH, MessengerPackets.ChatOpened.class).chatId();
        send(NOTCH, new MessengerPackets.ForwardMessage(forwarded.message().id(), notchSteve, "f2"));
        assertEquals("Steve", platform.last(STEVE, MessengerPackets.NewMessage.class).message().forwardedFrom());

        // Чужое сообщение (не участник чата) переслать нельзя.
        send(NOTCH, new MessengerPackets.ForwardMessage(original, notchSteve, "f3"));
        assertEquals("messenger.error.not_found", platform.last(NOTCH, MessengerPackets.Failure.class).key());

        // Пост в чат.
        PostInfo post = post(ALEX, 0, "Новый дом готов", 0);
        send(ALEX, new MessengerPackets.SharePost(post.id(), direct, "p1", "глянь"));
        MessengerPackets.NewMessage shared = platform.last(STEVE, MessengerPackets.NewMessage.class);
        assertEquals("глянь", shared.message().text());
        assertEquals(post.id(), shared.message().post().postId());
        assertEquals("Alex", shared.message().post().author());
        assertEquals("Новый дом готов", shared.message().post().text());
        send(ALEX, new SocialPackets.DeletePost(post.id()));
        send(STEVE, new MessengerPackets.RequestHistory(direct, 0));
        MessengerPackets.History history = platform.last(STEVE, MessengerPackets.History.class);
        assertTrue(history.messages().stream().anyMatch(m -> m.post() != null && m.post().deleted()));
    }
}

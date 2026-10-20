package dev.blockphone.common.protocol;

import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.VoicePackets;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Реестр пакетов. Формат сообщения в канале: VarInt id + тело пакета.
 * Новые пакеты добавлять только в конец, id не переиспользовать.
 */
public final class Packets {
    private static final Map<Integer, Function<PacketReader, ? extends Packet>> DECODERS = new HashMap<>();
    private static final Map<Class<? extends Packet>, Integer> IDS = new HashMap<>();

    static {
        // Рукопожатие
        register(0, ServerHelloPacket.class, ServerHelloPacket::read);
        register(1, ClientHelloPacket.class, ClientHelloPacket::read);
        // Мессенджер: сервер → клиент
        register(2, MessengerPackets.ChatList.class, MessengerPackets.ChatList::read);
        register(3, MessengerPackets.ChatUpdate.class, MessengerPackets.ChatUpdate::read);
        register(4, MessengerPackets.ChatRemoved.class, MessengerPackets.ChatRemoved::read);
        register(5, MessengerPackets.History.class, MessengerPackets.History::read);
        register(6, MessengerPackets.NewMessage.class, MessengerPackets.NewMessage::read);
        register(7, MessengerPackets.MessageUpdated.class, MessengerPackets.MessageUpdated::read);
        register(8, MessengerPackets.UserList.class, MessengerPackets.UserList::read);
        register(9, MessengerPackets.ChatOpened.class, MessengerPackets.ChatOpened::read);
        register(10, MessengerPackets.TypingState.class, MessengerPackets.TypingState::read);
        register(11, MessengerPackets.Presence.class, MessengerPackets.Presence::read);
        register(12, MessengerPackets.Failure.class, MessengerPackets.Failure::read);
        // Мессенджер: клиент → сервер
        register(13, MessengerPackets.RequestHistory.class, MessengerPackets.RequestHistory::read);
        register(14, MessengerPackets.SendMessage.class, MessengerPackets.SendMessage::read);
        register(15, MessengerPackets.EditMessage.class, MessengerPackets.EditMessage::read);
        register(16, MessengerPackets.DeleteMessage.class, MessengerPackets.DeleteMessage::read);
        register(17, MessengerPackets.MarkRead.class, MessengerPackets.MarkRead::read);
        register(18, MessengerPackets.SearchUsers.class, MessengerPackets.SearchUsers::read);
        register(19, MessengerPackets.OpenDirect.class, MessengerPackets.OpenDirect::read);
        register(20, MessengerPackets.CreateGroup.class, MessengerPackets.CreateGroup::read);
        register(21, MessengerPackets.GroupAction.class, MessengerPackets.GroupAction::read);
        register(22, MessengerPackets.Typing.class, MessengerPackets.Typing::read);
        // Сессия
        register(23, SessionPacket.class, SessionPacket::read);
        register(24, SessionPacket.Refresh.class, SessionPacket.Refresh::read);
        // Фото
        register(25, ImagePackets.UploadBegin.class, ImagePackets.UploadBegin::read);
        register(26, ImagePackets.UploadChunk.class, ImagePackets.UploadChunk::read);
        register(27, ImagePackets.UploadResult.class, ImagePackets.UploadResult::read);
        register(28, ImagePackets.RequestImage.class, ImagePackets.RequestImage::read);
        register(29, ImagePackets.ImageData.class, ImagePackets.ImageData::read);
        // Лента: сервер → клиент
        register(30, SocialPackets.FeedPage.class, SocialPackets.FeedPage::read);
        register(31, SocialPackets.PostUpdated.class, SocialPackets.PostUpdated::read);
        register(32, SocialPackets.PostRemoved.class, SocialPackets.PostRemoved::read);
        register(33, SocialPackets.Comments.class, SocialPackets.Comments::read);
        register(34, SocialPackets.CommentAdded.class, SocialPackets.CommentAdded::read);
        register(35, SocialPackets.CommentRemoved.class, SocialPackets.CommentRemoved::read);
        register(36, SocialPackets.Profile.class, SocialPackets.Profile::read);
        register(37, SocialPackets.PublicData.class, SocialPackets.PublicData::read);
        register(38, SocialPackets.PublicRemoved.class, SocialPackets.PublicRemoved::read);
        register(39, SocialPackets.PublicList.class, SocialPackets.PublicList::read);
        register(40, SocialPackets.UserList.class, SocialPackets.UserList::read);
        register(41, SocialPackets.SearchResults.class, SocialPackets.SearchResults::read);
        register(42, SocialPackets.Reports.class, SocialPackets.Reports::read);
        register(43, SocialPackets.Notice.class, SocialPackets.Notice::read);
        register(44, SocialPackets.Failure.class, SocialPackets.Failure::read);
        register(45, SocialPackets.Done.class, SocialPackets.Done::read);
        // Лента: клиент → сервер
        register(46, SocialPackets.RequestFeed.class, SocialPackets.RequestFeed::read);
        register(47, SocialPackets.CreatePost.class, SocialPackets.CreatePost::read);
        register(48, SocialPackets.DeletePost.class, SocialPackets.DeletePost::read);
        register(49, SocialPackets.LikePost.class, SocialPackets.LikePost::read);
        register(50, SocialPackets.RequestComments.class, SocialPackets.RequestComments::read);
        register(51, SocialPackets.AddComment.class, SocialPackets.AddComment::read);
        register(52, SocialPackets.DeleteComment.class, SocialPackets.DeleteComment::read);
        register(53, SocialPackets.RequestProfile.class, SocialPackets.RequestProfile::read);
        register(54, SocialPackets.UpdateBio.class, SocialPackets.UpdateBio::read);
        register(55, SocialPackets.Follow.class, SocialPackets.Follow::read);
        register(56, SocialPackets.RequestFollows.class, SocialPackets.RequestFollows::read);
        register(57, SocialPackets.RequestPublic.class, SocialPackets.RequestPublic::read);
        register(58, SocialPackets.CreatePublic.class, SocialPackets.CreatePublic::read);
        register(59, SocialPackets.PublicAction.class, SocialPackets.PublicAction::read);
        register(60, SocialPackets.RequestPublics.class, SocialPackets.RequestPublics::read);
        register(61, SocialPackets.Search.class, SocialPackets.Search::read);
        register(62, SocialPackets.Report.class, SocialPackets.Report::read);
        register(63, SocialPackets.RequestReports.class, SocialPackets.RequestReports::read);
        register(64, SocialPackets.ResolveReport.class, SocialPackets.ResolveReport::read);
        register(65, SocialPackets.Mute.class, SocialPackets.Mute::read);
        // Голосовые сообщения и звонки
        register(66, VoicePackets.UploadBegin.class, VoicePackets.UploadBegin::read);
        register(67, VoicePackets.UploadChunk.class, VoicePackets.UploadChunk::read);
        register(68, VoicePackets.UploadResult.class, VoicePackets.UploadResult::read);
        register(69, VoicePackets.RequestVoice.class, VoicePackets.RequestVoice::read);
        register(70, VoicePackets.VoiceChunk.class, VoicePackets.VoiceChunk::read);
        register(71, VoicePackets.CallState.class, VoicePackets.CallState::read);
        register(72, VoicePackets.StartCall.class, VoicePackets.StartCall::read);
        register(73, VoicePackets.AnswerCall.class, VoicePackets.AnswerCall::read);
        register(74, VoicePackets.LeaveCall.class, VoicePackets.LeaveCall::read);
    }

    private static <T extends Packet> void register(int id, Class<T> type, Function<PacketReader, T> decoder) {
        DECODERS.put(id, decoder);
        IDS.put(type, id);
    }

    public static byte[] encode(Packet packet) {
        Integer id = IDS.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered packet " + packet.getClass().getName());
        }
        PacketWriter writer = new PacketWriter().writeVarInt(id);
        packet.write(writer);
        return writer.toByteArray();
    }

    public static Packet decode(byte[] data) {
        PacketReader reader = new PacketReader(data);
        int id = reader.readVarInt();
        Function<PacketReader, ? extends Packet> decoder = DECODERS.get(id);
        if (decoder == null) {
            throw new ProtocolException("Unknown packet id " + id);
        }
        return decoder.apply(reader);
    }

    private Packets() {
    }
}

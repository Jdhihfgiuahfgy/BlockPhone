package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.common.server.MessengerRepository.ChatRow;
import dev.blockphone.common.voice.VoiceBridge;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Звонки в чатах через группы Simple Voice Chat: звонящий создаёт скрытую группу и попадает в неё, остальным участникам
 * чата (онлайн) приходит входящий звонок; принявшие переводятся в группу. Звонок кончается, когда в нём остаётся
 * один человек и больше никто не ждёт ответа. Не ответили за 45 секунд — вызов снимается. Только из потока {@link Backend}.
 */
final class CallService {
    static final long RING_MS = 45_000;

    private static final class Call {
        final long chatId;
        final UUID groupId;
        final long startedAt;
        final Set<UUID> participants = new LinkedHashSet<>();
        /** Кому звонят: игрок → когда позвонили. */
        final Map<UUID, Long> invited = new LinkedHashMap<>();

        Call(long chatId, UUID groupId, long startedAt) {
            this.chatId = chatId;
            this.groupId = groupId;
            this.startedAt = startedAt;
        }
    }

    private final Backend backend;
    private final java.util.function.Supplier<VoiceBridge> bridges;
    private final Map<Long, Call> calls = new HashMap<>();
    /** В каком звонке игрок (по id чата). */
    private final Map<UUID, Long> playerCall = new HashMap<>();

    CallService(Backend backend, java.util.function.Supplier<VoiceBridge> bridges) {
        this.backend = backend;
        this.bridges = bridges;
    }

    private MessengerRepository repo() {
        return backend.messenger().repo();
    }

    /** Звонки включены и голосовой чат на сервере есть. */
    boolean available() {
        return backend.settings().voice().calls() && backend.messenger() != null && bridges.get() != null;
    }

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case VoicePackets.StartCall p -> start(player, p.chatId());
            case VoicePackets.AnswerCall p -> answer(player, p.chatId(), p.accept());
            case VoicePackets.LeaveCall p -> leave(player, p.chatId());
            default -> {
            }
        }
    }

    // --- Действия игроков ---

    private void start(UUID player, long chatId) throws SQLException {
        VoiceBridge bridge = bridges.get();
        if (!backend.settings().voice().calls() || bridge == null) {
            fail(player, "unavailable");
            return;
        }
        if (!backend.has(player, Permission.CALL)) {
            fail(player, "no_permission");
            return;
        }
        if (!repo().isMember(chatId, player)) {
            return;
        }
        if (!bridge.connected(player)) {
            fail(player, "no_voicechat");
            return;
        }
        Call call = calls.get(chatId);
        if (call == null) {
            ChatRow chat = repo().chat(chatId);
            if (chat == null) {
                return;
            }
            if (chat.type() == ChatType.DIRECT) {
                for (UUID member : repo().members(chatId)) {
                    if (!member.equals(player) && !backend.privacy().allows(member, player,
                            dev.blockphone.common.protocol.data.PrivacyData.Kind.CALLS)) {
                        fail(player, "privacy");
                        return;
                    }
                }
            }
            String title = chat.type() == ChatType.GROUP ? chat.title() : "BlockPhone";
            boolean isolated = "isolated".equalsIgnoreCase(backend.settings().voice().callGroupType());
            call = new Call(chatId, bridge.createGroup(title, isolated), System.currentTimeMillis());
            calls.put(chatId, call);
            long now = System.currentTimeMillis();
            for (UUID member : repo().members(chatId)) {
                if (!member.equals(player) && backend.isOnline(member) && !playerCall.containsKey(member)) {
                    call.invited.put(member, now);
                }
            }
        }
        enter(call, player, bridge);
        broadcast(call);
    }

    private void answer(UUID player, long chatId, boolean accept) throws SQLException {
        Call call = calls.get(chatId);
        if (call == null || !call.invited.containsKey(player)) {
            return;
        }
        if (!accept) {
            call.invited.remove(player);
            checkEnd(call);
            return;
        }
        VoiceBridge bridge = bridges.get();
        if (bridge == null || !bridge.connected(player)) {
            fail(player, "no_voicechat");
            return;
        }
        enter(call, player, bridge);
        broadcast(call);
    }

    private void enter(Call call, UUID player, VoiceBridge bridge) throws SQLException {
        Long other = playerCall.get(player);
        if (other != null && other != call.chatId) {
            leave(player, other);
        }
        call.invited.remove(player);
        call.participants.add(player);
        playerCall.put(player, call.chatId);
        bridge.join(call.groupId, player);
    }

    void leave(UUID player, long chatId) throws SQLException {
        Call call = calls.get(chatId);
        if (call == null) {
            return;
        }
        boolean was = call.participants.remove(player);
        call.invited.remove(player);
        if (was) {
            playerCall.remove(player);
            VoiceBridge bridge = bridges.get();
            if (bridge != null) {
                bridge.leave(call.groupId, player);
            }
        }
        checkEnd(call);
    }

    /** Игрок сам вышел из группы через меню голосового чата. */
    void leftGroup(UUID player, UUID groupId) throws SQLException {
        Long chatId = playerCall.get(player);
        Call call = chatId != null ? calls.get(chatId) : null;
        if (call != null && call.groupId.equals(groupId)) {
            leave(player, chatId);
        }
    }

    void quit(UUID player) throws SQLException {
        Long chatId = playerCall.get(player);
        if (chatId != null) {
            leave(player, chatId);
        }
        for (Call call : new ArrayList<>(calls.values())) {
            if (call.invited.remove(player) != null) {
                checkEnd(call);
            }
        }
    }

    /** При входе — показать идущие звонки в чатах игрока. */
    void join(UUID player) throws SQLException {
        for (Call call : calls.values()) {
            if (repo().isMember(call.chatId, player)) {
                backend.send(player, new VoicePackets.CallState(info(call, true)));
            }
        }
    }

    /** Раз в несколько секунд: снять вызовы, на которые не ответили. */
    void tick() throws SQLException {
        long now = System.currentTimeMillis();
        for (Call call : new ArrayList<>(calls.values())) {
            if (call.invited.values().removeIf(at -> now - at > RING_MS)) {
                if (!checkEnd(call)) {
                    broadcast(call);
                }
            }
        }
    }

    /** Завершить звонок, если говорить не с кем. true — завершён. */
    private boolean checkEnd(Call call) throws SQLException {
        if (!call.participants.isEmpty() && (call.participants.size() > 1 || !call.invited.isEmpty())) {
            broadcast(call);
            return false;
        }
        calls.remove(call.chatId);
        VoiceBridge bridge = bridges.get();
        for (UUID p : call.participants) {
            playerCall.remove(p);
            if (bridge != null) {
                bridge.leave(call.groupId, p);
            }
        }
        if (bridge != null) {
            bridge.removeGroup(call.groupId);
        }
        CallInfo ended = info(call, false);
        for (UUID member : repo().members(call.chatId)) {
            if (backend.isOnline(member)) {
                backend.send(member, new VoicePackets.CallState(ended));
            }
        }
        return true;
    }

    private void broadcast(Call call) throws SQLException {
        CallInfo info = info(call, true);
        for (UUID member : repo().members(call.chatId)) {
            if (backend.isOnline(member)) {
                backend.send(member, new VoicePackets.CallState(info));
            }
        }
    }

    private CallInfo info(Call call, boolean active) throws SQLException {
        List<UUID> ids = new ArrayList<>(call.participants);
        ids.addAll(call.invited.keySet());
        Map<UUID, String> names = backend.users().userNames(ids);
        List<UserInfo> participants = call.participants.stream().map(u -> backend.user(u, names.get(u))).toList();
        List<UserInfo> invited = call.invited.keySet().stream().map(u -> backend.user(u, names.get(u))).toList();
        return new CallInfo(call.chatId, participants, invited, call.startedAt, active);
    }

    private void fail(UUID player, String key) {
        backend.send(player, new MessengerPackets.Failure("call.error." + key, ""));
    }
}

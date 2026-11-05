package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.VoiceData;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.common.server.MessengerRepository.ChatRow;

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
 * Звонки в чатах — свои, без других модов: телефон шлёт звук микрофона (Opus 16 кГц, кадры по 20 мс) серверу, сервер
 * пересылает его остальным участникам. Звонящий попадает в звонок сразу, остальным участникам чата (онлайн) приходит
 * входящий вызов; не ответили за 45 секунд — вызов снимается; остался один и никто не ждёт — звонок завершён.
 * <p>Управление: свой микрофон выключен (звук не пересылается) и громкая связь: игроки с модом в радиусе
 * {@link ServerSettings.Voice#speakerRadius()} от участника слышат звонок из его точки мира, а если на сервере есть
 * Simple Voice Chat — голоса игроков рядом с ним уходят в звонок. Позиции — снимок раз в 0,5 с. Только поток {@link Backend}.
 */
final class CallService {
    static final long RING_MS = 45_000;

    private static final class Call {
        final long chatId;
        final long startedAt;
        final Set<UUID> participants = new LinkedHashSet<>();
        /** Кому звонят: игрок → когда позвонили. */
        final Map<UUID, Long> invited = new LinkedHashMap<>();
        final Set<UUID> muted = new LinkedHashSet<>();
        final Set<UUID> speaker = new LinkedHashSet<>();
        /** Номера пересланных голосов игроков рядом (из SVC). */
        int nearbySeq;

        Call(long chatId, long startedAt) {
            this.chatId = chatId;
            this.startedAt = startedAt;
        }
    }

    private final Backend backend;
    private final Map<Long, Call> calls = new HashMap<>();
    /** В каком звонке игрок (по id чата). */
    private final Map<UUID, Long> playerCall = new HashMap<>();
    /** Не больше 40 пакетов звука в секунду в среднем (два кадра в пакете — 25/с). */
    private final RateLimiter audioLimit = new RateLimiter(80, 2_000);
    private volatile Map<UUID, ServerPlatform.Position> positions = Map.of();

    CallService(Backend backend) {
        this.backend = backend;
    }

    private MessengerRepository repo() {
        return backend.messenger().repo();
    }

    /** Звонки включены (нужен мессенджер; других модов не нужно). */
    boolean available() {
        return backend.settings().voice().calls() && backend.messenger() != null;
    }

    private int radius() {
        return backend.settings().voice().speakerRadius();
    }

    /** Кто-то в звонке с громкой связью — нужны позиции игроков. */
    boolean needsPositions() {
        if (radius() <= 0) {
            return false;
        }
        for (Call call : calls.values()) {
            if (!call.speaker.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    void positions(Map<UUID, ServerPlatform.Position> snapshot) {
        positions = snapshot;
    }

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case VoicePackets.StartCall p -> start(player, p.chatId());
            case VoicePackets.AnswerCall p -> answer(player, p.chatId(), p.accept());
            case VoicePackets.LeaveCall p -> leave(player, p.chatId());
            case VoicePackets.CallControl p -> control(player, p);
            case VoicePackets.CallAudio p -> audio(player, p);
            default -> {
            }
        }
    }

    // --- Действия игроков ---

    private void start(UUID player, long chatId) throws SQLException {
        if (!available()) {
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
            call = new Call(chatId, System.currentTimeMillis());
            calls.put(chatId, call);
            long now = System.currentTimeMillis();
            for (UUID member : repo().members(chatId)) {
                if (!member.equals(player) && backend.isOnline(member) && !playerCall.containsKey(member)) {
                    call.invited.put(member, now);
                }
            }
        }
        enter(call, player);
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
        enter(call, player);
        broadcast(call);
    }

    private void enter(Call call, UUID player) throws SQLException {
        Long other = playerCall.get(player);
        if (other != null && other != call.chatId) {
            leave(player, other);
        }
        call.invited.remove(player);
        call.participants.add(player);
        playerCall.put(player, call.chatId);
    }

    void leave(UUID player, long chatId) throws SQLException {
        Call call = calls.get(chatId);
        if (call == null) {
            return;
        }
        if (call.participants.remove(player)) {
            playerCall.remove(player);
        }
        call.invited.remove(player);
        call.muted.remove(player);
        call.speaker.remove(player);
        checkEnd(call);
    }

    private void control(UUID player, VoicePackets.CallControl p) throws SQLException {
        Call call = calls.get(p.chatId());
        if (call == null || !call.participants.contains(player)) {
            return;
        }
        boolean changed = p.muted() ? call.muted.add(player) : call.muted.remove(player);
        boolean speaker = p.speaker() && radius() > 0;
        changed |= speaker ? call.speaker.add(player) : call.speaker.remove(player);
        if (changed) {
            broadcast(call);
        }
    }

    /** Звук участника: остальным участникам и игрокам рядом с теми, у кого громкая связь. */
    private void audio(UUID player, VoicePackets.CallAudio p) {
        Call call = calls.get(p.chatId());
        if (call == null || !call.participants.contains(player) || call.muted.contains(player) || p.data().length == 0
                || !audioLimit.allow(player)) {
            return;
        }
        byte[] direct = Packets.encode(new VoicePackets.CallAudioFrom(call.chatId, player, VoiceData.NOBODY, p.seq(),
                VoiceData.CODEC_CALL_OPUS16, p.data()));
        for (UUID other : call.participants) {
            if (!other.equals(player)) {
                backend.platform().send(other, direct);
            }
        }
        for (UUID holder : call.speaker) {
            if (holder.equals(player)) {
                continue;
            }
            byte[] loud = null;
            for (UUID listener : nearby(holder)) {
                if (!call.participants.contains(listener) && backend.isOnline(listener)) {
                    if (loud == null) {
                        loud = Packets.encode(new VoicePackets.CallAudioFrom(call.chatId, player, holder, p.seq(),
                                VoiceData.CODEC_CALL_OPUS16, p.data()));
                    }
                    backend.platform().send(listener, loud);
                }
            }
        }
    }

    /** Голос игрока из Simple Voice Chat: если он рядом с участником, у которого громкая связь, — в звонок. */
    void nearbyVoice(UUID speakerPlayer, byte[] opus) {
        if (opus == null || opus.length == 0 || opus.length > VoiceData.MAX_CALL_AUDIO || radius() <= 0) {
            return;
        }
        for (Call call : calls.values()) {
            if (call.speaker.isEmpty() || call.participants.contains(speakerPlayer)) {
                continue;
            }
            UUID near = null;
            for (UUID holder : call.speaker) {
                if (nearby(holder).contains(speakerPlayer)) {
                    near = holder;
                    break;
                }
            }
            if (near == null) {
                continue;
            }
            byte[] data = Packets.encode(new VoicePackets.CallAudioFrom(call.chatId, speakerPlayer, VoiceData.NOBODY, call.nearbySeq++,
                    VoiceData.CODEC_SVC_OPUS48, opus));
            for (UUID participant : call.participants) {
                // Тот, рядом с кем говорят, и так слышит голос в игре.
                if (!participant.equals(near)) {
                    backend.platform().send(participant, data);
                }
            }
        }
    }

    /** Игроки в радиусе громкой связи от игрока (по снимку позиций; без него — никто). */
    private List<UUID> nearby(UUID center) {
        Map<UUID, ServerPlatform.Position> snapshot = positions;
        ServerPlatform.Position c = snapshot.get(center);
        List<UUID> result = new ArrayList<>();
        if (c == null) {
            return result;
        }
        double r2 = (double) radius() * radius();
        for (Map.Entry<UUID, ServerPlatform.Position> e : snapshot.entrySet()) {
            if (!e.getKey().equals(center) && e.getValue().distanceSq(c) <= r2) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    void quit(UUID player) throws SQLException {
        audioLimit.forget(player);
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
        for (UUID p : call.participants) {
            playerCall.remove(p);
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
        return new CallInfo(call.chatId, participants, invited, call.startedAt, active, List.copyOf(call.muted),
                List.copyOf(call.speaker));
    }

    private void fail(UUID player, String key) {
        backend.send(player, new MessengerPackets.Failure("call.error." + key, ""));
    }
}

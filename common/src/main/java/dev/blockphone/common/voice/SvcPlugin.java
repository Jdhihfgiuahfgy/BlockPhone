package dev.blockphone.common.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

/**
 * Плагин BlockPhone для Simple Voice Chat. На Fabric подключается точкой входа {@code voicechat} в fabric.mod.json,
 * на Paper — через {@code BukkitVoicechatService}. Если SVC не установлен, класс никогда не загружается.
 */
public final class SvcPlugin implements VoicechatPlugin {
    private static volatile VoicechatApi api;

    /** API голосового чата (есть и на клиенте, и на сервере) или null. */
    public static VoicechatApi api() {
        return api;
    }

    @Override
    public String getPluginId() {
        return "blockphone";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        api = voicechatApi;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> SvcBridge.started(event.getVoicechat()));
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> SvcBridge.stopped());
        registration.registerEvent(LeaveGroupEvent.class, event -> SvcBridge.playerLeftGroup(event.getConnection(), event.getGroup()));
        // Голоса игроков рядом с «громкой связью» звонка BlockPhone уходят в звонок.
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            if (event.getSenderConnection() != null && event.getSenderConnection().getPlayer() != null) {
                VoiceBridges.microphone(event.getSenderConnection().getPlayer().getUuid(), event.getPacket().getOpusEncodedData());
            }
        });
    }
}

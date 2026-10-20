package dev.blockphone.paper;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.blockphone.common.voice.SvcPlugin;

/**
 * Регистрация плагина BlockPhone в Simple Voice Chat (для звонков). Отдельный класс, чтобы без SVC
 * не загружались его типы.
 */
final class PaperVoicechat {
    static void register(BlockPhonePaper plugin) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) {
            service.registerPlugin(new SvcPlugin());
            plugin.getLogger().info("Simple Voice Chat found: calls enabled");
        } else {
            plugin.getLogger().warning("Simple Voice Chat is installed but its service is unavailable: calls disabled");
        }
    }

    private PaperVoicechat() {
    }
}

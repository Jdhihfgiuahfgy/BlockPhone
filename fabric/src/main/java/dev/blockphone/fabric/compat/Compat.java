package dev.blockphone.fabric.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Места, где API Minecraft отличается между версиями. Это версия для 1.21.11; для 1.21.10 — одноимённый класс
 * в модуле fabric-1.21.10 (подменяет этот при сборке).
 */
public final class Compat {
    /** Оператор уровня 2+ (может выдавать права, /gamemode и т. п.). */
    public static boolean isOperator(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private Compat() {
    }
}

package dev.blockphone.fabric.compat;

import net.minecraft.server.level.ServerPlayer;

/** Места, где API Minecraft отличается между версиями — версия для 1.21.10. */
public final class Compat {
    /** Оператор уровня 2+. */
    public static boolean isOperator(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    private Compat() {
    }
}

package dev.blockphone.fabric.client.screens;

import dev.blockphone.fabric.BlockPhoneFabric;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Совместимость с модом Sound Physics Remastered (эхо, стены глушат звук). Колонки — свои источники OpenAL мимо движка
 * игры, поэтому мод сам их не видит: раз в секунду для каждой колонки вызываем его же
 * {@code SoundPhysics.processSound(source, x, y, z, category, sound)} — так он считает окружение любого звука. Через
 * отражение: без мода класс ничего не делает.
 */
final class SoundPhysicsCompat {
    private static final String MOD_ID = "sound_physics_remastered";
    private static volatile boolean checked;
    private static volatile @Nullable Method process;

    private static boolean ready() {
        if (checked) {
            return process != null;
        }
        synchronized (SoundPhysicsCompat.class) {
            if (!checked) {
                if (FabricLoader.getInstance().isModLoaded(MOD_ID)) {
                    try {
                        Class<?> sp = Class.forName("com.sonicether.soundphysics.SoundPhysics");
                        process = sp.getMethod("processSound", int.class, double.class, double.class, double.class, SoundSource.class,
                                Identifier.class);
                        BlockPhoneFabric.LOGGER.info("[screens] Sound Physics Remastered: speakers are processed every second");
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        BlockPhoneFabric.LOGGER.warn("[screens] Sound Physics found, but its API differs: {}", e.toString());
                    }
                }
                checked = true;
            }
        }
        return process != null;
    }

    /** Пересчитать окружение источника OpenAL колонки (поток подкачки). */
    static void process(int source, double x, double y, double z) {
        if (!ready()) {
            return;
        }
        try {
            process.invoke(null, source, x, y, z, SoundSource.BLOCKS, StreamSound.EVENT);
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockPhoneFabric.LOGGER.debug("[screens] Sound Physics: {}", e.toString());
        }
    }

    private SoundPhysicsCompat() {
    }
}

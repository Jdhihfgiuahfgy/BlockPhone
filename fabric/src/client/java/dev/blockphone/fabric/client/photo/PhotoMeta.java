package dev.blockphone.fabric.client.photo;

/**
 * Сведения о снимке, хранятся рядом с PNG в {@code <имя>.json}.
 * {@link #serverId} нужен для политики сервера «только фото, сделанные на этом сервере».
 */
public final class PhotoMeta {
    /** Время съёмки, мс с эпохи. */
    public long takenAt;
    /** Адрес сервера (null — одиночная игра). */
    public String server;
    /** Название мира в одиночной игре. */
    public String world;
    /** Постоянный id сервера с BlockPhone, на котором сделан снимок (null — сервер без BlockPhone или одиночная игра). */
    public String serverId;
    public String dimension;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;

    public String filter = "none";
    /** 0 — авто. Эффективное значение — {@link #effectiveIso}. */
    public int iso;
    public int effectiveIso;
    /** Выдержка строкой («1/125», «AUTO»). */
    public String shutter = "AUTO";
    public float ev;
    public float zoom = 1f;
    public int width;
    public int height;
    /** Фото изменено в редакторе галереи. */
    public boolean edited;
    /** Имя исходного файла, если это отредактированная копия. */
    public String editedFrom;

    public PhotoMeta copy() {
        PhotoMeta m = new PhotoMeta();
        m.takenAt = takenAt;
        m.server = server;
        m.world = world;
        m.serverId = serverId;
        m.dimension = dimension;
        m.x = x;
        m.y = y;
        m.z = z;
        m.yaw = yaw;
        m.pitch = pitch;
        m.filter = filter;
        m.iso = iso;
        m.effectiveIso = effectiveIso;
        m.shutter = shutter;
        m.ev = ev;
        m.zoom = zoom;
        m.width = width;
        m.height = height;
        m.edited = edited;
        m.editedFrom = editedFrom;
        return m;
    }
}

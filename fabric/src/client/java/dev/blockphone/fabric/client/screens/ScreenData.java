package dev.blockphone.fabric.client.screens;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Экраны и колонки — наложения на обычные блоки (самих блоков мод не добавляет). Экран — прямоугольник из блоков,
 * картинка на одной их стороне; колонка — невидимый источник звука в блоке. Данные владельца: хранятся на его компьютере
 * ({@link ScreenStore}) и рассылаются другим игрокам с модом ({@link ScreenSync}). Поля публичные — Gson.
 */
public final class ScreenData {
    public static final int MAX_SCREENS = 8;
    public static final int MAX_SPEAKERS = 32;
    /** Наибольшая сторона экрана в блоках. */
    public static final int MAX_SIZE = 32;
    public static final int MIN_RADIUS = 4;
    public static final int MAX_RADIUS = 100;
    public static final int MAX_URL = 1000;
    public static final int MAX_NAME = 32;
    /** Колонка, подключённая к телефону (а не к экрану): {@link Speaker#screen} = PHONE. */
    public static final String PHONE = "@phone";
    /** Источник колонки «объединённая группа экранов»: {@link Speaker#screen} = GROUP_PREFIX + id группы. */
    public static final String GROUP_PREFIX = "g:";
    public static final int MAX_GROUPS = 16;
    public static final int MAX_GROUP_MEMBERS = 32;

    /** Экран: углы (блоки), сторона, ссылка и что сейчас играет. */
    public static final class Screen {
        public String id = "";
        public String name = "";
        /** Измерение (minecraft:overworld …). */
        public String dim = "";
        public int x1;
        public int y1;
        public int z1;
        public int x2;
        public int y2;
        public int z2;
        /** Сторона блоков, на которой картинка ({@link Direction#get3DDataValue()}). */
        public int face = Direction.NORTH.get3DDataValue();
        /** Для экрана на полу или потолке — куда смотрит верх картинки (горизонтальное направление). */
        public int up = Direction.SOUTH.get3DDataValue();
        public String url = "";
        public boolean on = true;
        public boolean paused;
        /** Позиция видео (мс) в момент {@link #at} (часы владельца). */
        public long pos;
        public long at;
        /** true — звук только у подключившихся телефоном (не в мире); false — из колонок (или из самого экрана). */
        public boolean phone;
        public int radius = 24;
        /** Громкость 0..100. */
        public int volume = 100;
        public boolean loop;
        /** Трансляция экрана телефона владельца (вместо ссылки). */
        public boolean mirror;
        /** Только видео на весь экран (остальное на странице прячется); false — страница как есть. */
        public boolean fit = true;
        /**
         * Записанные действия владельца на странице («Управлять страницей»): «время,вид,a,b,c» — d/u нажатие и отпускание
         * (x, y в десятитысячных долях страницы, кнопка), w колесо, k служебная клавиша (код, скан-код, модификаторы).
         * Повторяются у всех после загрузки страницы. Набранный текст не записывается.
         */
        public List<String> actions = new ArrayList<>();
        /** Растёт при «Сбросить действия» — страница у всех загружается заново. */
        public int rev;
        /** Своё приложение на экране («режим экрана», {@link AppDisplays}): id приложения, «» — нет. */
        public String app = "";
        /** Название приложения — для заставки у тех, у кого оно не подключено или не установлено. */
        public String appTitle = "";

        /** Позиция видео (мс) по часам владельца. */
        public long positionAt(long ownerNow) {
            return paused ? pos : pos + Math.max(0, ownerNow - at);
        }

        /** Запомнить новую позицию (паузу/перемотку) по часам владельца. */
        public void setPosition(long ms, long ownerNow) {
            pos = Math.max(0, ms);
            at = ownerNow;
        }

        public Direction faceDir() {
            return Direction.from3DDataValue(Math.floorMod(face, 6));
        }

        public Direction upDir() {
            Direction f = faceDir();
            if (f.getAxis() != Direction.Axis.Y) {
                return Direction.UP;
            }
            Direction u = Direction.from3DDataValue(Math.floorMod(up, 6));
            return u.getAxis() == Direction.Axis.Y ? Direction.SOUTH : u;
        }

        /** Ширина и высота картинки в блоках. */
        public int widthBlocks() {
            return extent(rightAxis());
        }

        public int heightBlocks() {
            return extent(upDir().getAxis());
        }

        private Direction.Axis rightAxis() {
            Direction.Axis f = faceDir().getAxis();
            Direction.Axis u = upDir().getAxis();
            for (Direction.Axis a : Direction.Axis.values()) {
                if (a != f && a != u) {
                    return a;
                }
            }
            return Direction.Axis.X;
        }

        private int extent(Direction.Axis axis) {
            return switch (axis) {
                case X -> Math.abs(x2 - x1) + 1;
                case Y -> Math.abs(y2 - y1) + 1;
                case Z -> Math.abs(z2 - z1) + 1;
            };
        }

        /** Откуда звучит экран без колонок: полблока перед картинкой (не в стене — для Sound Physics). */
        public Vec3 soundPos() {
            Vec3[] c = corners(0.5);
            return c[0].add(c[2]).scale(0.5);
        }

        /** Центр картинки (на поверхности блоков). */
        public Vec3 center() {
            Vec3[] c = corners(0);
            return c[0].add(c[2]).scale(0.5);
        }

        /**
         * Углы картинки по порядку: левый верхний, правый верхний, правый нижний, левый нижний (как видит смотрящий на
         * экран), над поверхностью блоков на offset.
         */
        public Vec3[] corners(double offset) {
            Direction f = faceDir();
            Direction u = upDir();
            double minX = Math.min(x1, x2);
            double minY = Math.min(y1, y2);
            double minZ = Math.min(z1, z2);
            double maxX = Math.max(x1, x2) + 1;
            double maxY = Math.max(y1, y2) + 1;
            double maxZ = Math.max(z1, z2) + 1;
            Vec3 center = new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
            Vec3 n = new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
            double half = switch (f.getAxis()) {
                case X -> (maxX - minX) / 2;
                case Y -> (maxY - minY) / 2;
                case Z -> (maxZ - minZ) / 2;
            };
            Vec3 plane = center.add(n.scale(half + offset));
            Vec3 up = new Vec3(u.getStepX(), u.getStepY(), u.getStepZ());
            Vec3 right = up.cross(n); // для смотрящего на сторону f «вправо»
            Vec3 r = right.scale(widthBlocks() / 2.0);
            Vec3 t = up.scale(heightBlocks() / 2.0);
            return new Vec3[]{plane.subtract(r).add(t), plane.add(r).add(t), plane.add(r).subtract(t), plane.subtract(r).subtract(t)};
        }

        public Screen copy() {
            Screen s = new Screen();
            s.id = id;
            s.name = name;
            s.dim = dim;
            s.x1 = x1;
            s.y1 = y1;
            s.z1 = z1;
            s.x2 = x2;
            s.y2 = y2;
            s.z2 = z2;
            s.face = face;
            s.up = up;
            s.url = url;
            s.on = on;
            s.paused = paused;
            s.pos = pos;
            s.at = at;
            s.phone = phone;
            s.radius = radius;
            s.volume = volume;
            s.loop = loop;
            s.mirror = mirror;
            s.fit = fit;
            s.actions = new ArrayList<>(actions == null ? List.of() : actions);
            s.rev = rev;
            s.app = app;
            s.appTitle = appTitle;
            return s;
        }
    }

    /** Колонка: блок-источник звука экрана или телефона ({@link #screen}); у каждой — имя, вкл/выкл, своя громкость. */
    public static final class Speaker {
        public String id = "";
        public String name = "";
        public boolean on = true;
        /** Своя громкость 0..100 (умножается на громкость источника). */
        public int volume = 100;
        /** Какой канал дорожки играет: mix (всё, моно), L, R, C, LFE, SL, SR ({@link ScreenAudio.Pick}). */
        public String channel = "mix";
        /** Блок колонки «качается» от басов, как динамик. */
        public boolean pulse = true;
        public String dim = "";
        public int x;
        public int y;
        public int z;
        /** id экрана, чей звук играет колонка, или {@link #PHONE} — звук телефона владельца. */
        public String screen = "";

        public Vec3 center() {
            return new Vec3(x + 0.5, y + 0.5, z + 0.5);
        }
    }

    /** Колонки телефона: музыка телефона владельца звучит из них для всех рядом. */
    public static final class PhoneOut {
        public boolean on = true;
        public int radius = 24;
        /** Громкость 0..100. */
        public int volume = 100;
        /** Владелец слышит музыку и в «наушниках» (иначе — только из колонок, как все). */
        public boolean local;
    }

    /**
     * Группа колонок или экранов: общие вкл/выкл и громкость (умножаются на свои у каждого). У группы экранов — «объединить»
     * (sync): ссылка, пауза, перемотка, повтор, звук — общие, и все экраны показывают одну вкладку браузера (кадр в кадр).
     */
    public static final class Group {
        public String id = "";
        public String name = "";
        /** «screens» или «speakers». */
        public String kind = "speakers";
        public List<String> members = new ArrayList<>();
        public boolean on = true;
        public int volume = 100;
        public boolean sync;

        public boolean screens() {
            return "screens".equals(kind);
        }
    }

    /** Все экраны и колонки одного владельца. */
    public static final class Set {
        public List<Screen> screens = new ArrayList<>();
        public List<Speaker> speakers = new ArrayList<>();
        public PhoneOut phone = new PhoneOut();
        public List<Group> groups = new ArrayList<>();

        /** Экран включён сам и во всех своих группах. */
        public boolean screenOn(Screen s) {
            if (!s.on) {
                return false;
            }
            for (Group g : groups) {
                if (g.screens() && !g.on && g.members.contains(s.id)) {
                    return false;
                }
            }
            return true;
        }

        /** Множитель громкости экрана от его групп (0..1). */
        public double screenGain(Screen s) {
            double k = 1;
            for (Group g : groups) {
                if (g.screens() && g.members.contains(s.id)) {
                    k *= g.volume / 100.0;
                }
            }
            return k;
        }

        public boolean speakerOn(Speaker sp) {
            if (!sp.on) {
                return false;
            }
            for (Group g : groups) {
                if (!g.screens() && !g.on && g.members.contains(sp.id)) {
                    return false;
                }
            }
            return true;
        }

        /** Множитель громкости колонки от её групп (0..1). */
        public double speakerGain(Speaker sp) {
            double k = 1;
            for (Group g : groups) {
                if (!g.screens() && g.members.contains(sp.id)) {
                    k *= g.volume / 100.0;
                }
            }
            return k;
        }

        /** Объединённая группа, в которой экран (null — показывает своё). */
        public @org.jetbrains.annotations.Nullable Group syncGroup(Screen s) {
            for (Group g : groups) {
                if (g.screens() && g.sync && g.members.contains(s.id)) {
                    return g;
                }
            }
            return null;
        }

        public @org.jetbrains.annotations.Nullable Group group(String id) {
            for (Group g : groups) {
                if (g.id.equals(id)) {
                    return g;
                }
            }
            return null;
        }

        public @org.jetbrains.annotations.Nullable Screen screen(String id) {
            for (Screen s : screens) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return null;
        }

        /** Скопировать общее (ссылка, проигрывание, звук) с экрана на остальные экраны его объединённой группы. */
        public void propagate(Screen from) {
            Group g = syncGroup(from);
            if (g == null) {
                return;
            }
            for (String id : g.members) {
                Screen s = screen(id);
                if (s != null && s != from) {
                    s.url = from.url;
                    s.paused = from.paused;
                    s.pos = from.pos;
                    s.at = from.at;
                    s.loop = from.loop;
                    s.mirror = from.mirror;
                    s.fit = from.fit;
                    s.actions = new ArrayList<>(from.actions);
                    s.rev = from.rev;
                    s.phone = from.phone;
                    s.radius = from.radius;
                    s.volume = from.volume;
                }
            }
        }

        /** Убрать экран или колонку из всех групп (удалены). */
        public void forget(String id) {
            for (Group g : groups) {
                g.members.remove(id);
            }
        }
    }

    private ScreenData() {
    }
}

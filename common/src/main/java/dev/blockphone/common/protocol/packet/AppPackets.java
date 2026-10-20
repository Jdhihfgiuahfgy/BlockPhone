package dev.blockphone.common.protocol.packet;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.PacketReader;
import dev.blockphone.common.protocol.PacketWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиентские приложения и их серверные части (формат — docs/CUSTOM_APPS.md). Телефон не сообщает серверу список своих
 * приложений: сервер присылает политику, а телефон сам выключает запрещённые.
 */
public final class AppPackets {
    private static final int MAX_LIST = 256;
    private static final int MAX_VARS = 128;

    static Map<String, String> readMap(PacketReader r, int maxValue) {
        List<String> keys = r.readList(MAX_VARS, rr -> rr.readString(64));
        List<String> values = r.readList(MAX_VARS, rr -> rr.readString(maxValue));
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
            map.put(keys.get(i), values.get(i));
        }
        return map;
    }

    static void writeMap(PacketWriter w, Map<String, String> map) {
        w.writeList(new ArrayList<>(map.keySet()), PacketWriter::writeString);
        w.writeList(new ArrayList<>(map.values()), PacketWriter::writeString);
    }

    /**
     * S→C (после рукопожатия и при изменении): какие приложения работают на этом сервере и у каких есть серверная часть.
     * mode: blacklist | whitelist | none; записи — id или «sha256:хэш».
     */
    public record Policy(String mode, List<String> blacklist, List<String> whitelist, List<String> serverApps) implements Packet {
        public static Policy read(PacketReader r) {
            return new Policy(r.readString(16), r.readList(MAX_LIST, rr -> rr.readString(80)), r.readList(MAX_LIST, rr -> rr.readString(80)),
                    r.readList(MAX_LIST, rr -> rr.readString(32)));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(mode).writeList(blacklist, PacketWriter::writeString).writeList(whitelist, PacketWriter::writeString)
                    .writeList(serverApps, PacketWriter::writeString);
        }
    }

    /** C→S: переменные серверной части для страницы (страница с "server": true). */
    public record RequestVariables(String appId, String page) implements Packet {
        public static RequestVariables read(PacketReader r) {
            return new RequestVariables(r.readString(32), r.readString(64));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(appId).writeString(page);
        }
    }

    /** S→C: переменные для страницы. */
    public record Variables(String appId, String page, Map<String, String> values) implements Packet {
        public static Variables read(PacketReader r) {
            return new Variables(r.readString(32), r.readString(64), readMap(r, 2048));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(appId).writeString(page);
            writeMap(w, values);
        }
    }

    /** C→S: действие {"server": "функция"}; inputs — поля текущей страницы. */
    public record Call(String appId, String function, String page, Map<String, String> inputs) implements Packet {
        public static Call read(PacketReader r) {
            return new Call(r.readString(32), r.readString(64), r.readString(64), readMap(r, 1024));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(appId).writeString(function).writeString(page);
            writeMap(w, inputs);
        }
    }

    /** S→C: итог функции: сообщение, ошибка, куда перейти и новые значения переменных. */
    public record CallResult(String appId, String toast, boolean error, String navigate, Map<String, String> values) implements Packet {
        public static CallResult read(PacketReader r) {
            return new CallResult(r.readString(32), r.readString(1024), r.readBoolean(), r.readString(64), readMap(r, 2048));
        }

        @Override
        public void write(PacketWriter w) {
            w.writeString(appId).writeString(toast).writeBoolean(error).writeString(navigate);
            writeMap(w, values);
        }
    }

    private AppPackets() {
    }
}

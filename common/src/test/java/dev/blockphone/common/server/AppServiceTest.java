package dev.blockphone.common.server;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.apps.AppResult;
import dev.blockphone.common.apps.AppsSettings;
import dev.blockphone.common.apps.BlockPhoneApps;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.packet.AppPackets;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Серверные части приложений: политика, переменные, функции с проверкой полей и командами, Java-обработчик. */
class AppServiceTest {
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    FakePlatform platform;
    BlockPhoneServer server;

    @BeforeEach
    void setUp() throws Exception {
        Path apps = dir.resolve("apps");
        Files.createDirectories(apps);
        Files.writeString(apps.resolve("bank.json"), """
                {"id": "bank",
                 "variables": {"balance": "%test_balance% for {player}"},
                 "functions": {
                   "transfer": {"inputs": {"to": {"kind": "player", "required": true},
                                           "amount": {"kind": "number", "min": 1, "required": true}},
                                "command": "pay {to} {amount}", "toast": "Sent {amount}", "then": "main"},
                   "broken": {"command": "fail now", "error": "Nope"},
                   "reward": {"as": "console", "command": "give {player} diamond {count}"}
                 }}""");
        Files.writeString(apps.resolve("secret.json"), "{\"id\": \"secret\", \"permission\": \"test.denied\", \"variables\": {\"x\": \"1\"}}");
        AppsSettings policy = new AppsSettings();
        policy.blacklist = List.of("blocked");
        platform = new FakePlatform();
        ServerSettings.Storage storage = new ServerSettings.Storage("h2", dir.resolve("db"), "", 0, "", "", "", "bp_");
        ServerSettings settings = new ServerSettings(false, true, storage, new ServerSettings.Social(false,
                dev.blockphone.common.protocol.data.ImageRef.PhotoPolicy.ANY, 100_000, 1000, 1), List.of(), ServerSettings.Voice.defaults(),
                null, policy);
        server = new BlockPhoneServer(platform, settings);
        server.setAppsDirectory(apps);
        server.start();
        Thread.sleep(300); // первая загрузка папки — в фоне
        server.onChannelReady(STEVE);
        server.onMessage(STEVE, "Steve", Packets.encode(new ClientHelloPacket(BlockPhone.PROTOCOL_VERSION, "test")));
    }

    @AfterEach
    void tearDown() {
        BlockPhoneApps.unregister("blocked");
        server.stop();
    }

    @Test
    void policyListsServerParts() {
        AppPackets.Policy policy = platform.last(STEVE, AppPackets.Policy.class);
        assertEquals("blacklist", policy.mode());
        assertEquals(List.of("blocked"), policy.blacklist());
        assertTrue(policy.serverApps().contains("bank"));
    }

    /** От консоли подставляются только описанные поля: недописанное «count» телефон подменить не может. */
    @Test
    void consoleIgnoresUndeclaredInputs() {
        platform.allowedWarnings.add("[apps] bank.reward");
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.Call("bank", "reward", "main",
                Map.of("count", "64\nop Steve"))));
        assertTrue(platform.commands.isEmpty(), platform.commands.toString());
        assertTrue(platform.last(STEVE, AppPackets.CallResult.class).error());
        assertEquals(1, platform.warnings.size());
    }

    @Test
    void variablesAndPermission() {
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.RequestVariables("bank", "main")));
        assertEquals("500 for Steve", platform.last(STEVE, AppPackets.Variables.class).values().get("balance"));
        int before = platform.of(STEVE, AppPackets.Variables.class).size();
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.RequestVariables("secret", "main")));
        assertEquals(before, platform.of(STEVE, AppPackets.Variables.class).size());
    }

    @Test
    void functionValidatesInputsAndRunsCommand() {
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.Call("bank", "transfer", "transfer",
                Map.of("to", "Alex; op Steve", "amount", "5"))));
        AppPackets.CallResult bad = platform.last(STEVE, AppPackets.CallResult.class);
        assertTrue(bad.error());
        assertEquals("@app.error.player|to", bad.toast());
        assertTrue(platform.commands.isEmpty());

        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.Call("bank", "transfer", "transfer",
                Map.of("to", "Alex", "amount", "2,5"))));
        AppPackets.CallResult ok = platform.last(STEVE, AppPackets.CallResult.class);
        assertFalse(ok.error());
        assertEquals("Sent 2.5", ok.toast());
        assertEquals("main", ok.navigate());
        assertEquals("500 for Steve", ok.values().get("balance"));
        assertEquals(List.of("player /pay Alex 2.5"), platform.commands);

        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.Call("bank", "broken", "main", Map.of())));
        assertEquals("Nope", platform.last(STEVE, AppPackets.CallResult.class).toast());
    }

    @Test
    void javaHandlerAndBlacklist() {
        BlockPhoneApps.handler("blocked", new BlockPhoneApps.AppHandler() {
            @Override
            public AppResult call(UUID player, String function, Map<String, String> inputs) {
                return AppResult.ok("hi");
            }
        });
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.Call("blocked", "x", "main", Map.of())));
        AppPackets.CallResult result = platform.last(STEVE, AppPackets.CallResult.class);
        assertTrue(result.error());
        assertEquals("@app.error.no_server", result.toast());
    }

    @Test
    void relaysMessagesBetweenPlayersOfTheSameApp() {
        UUID alex = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        server.onChannelReady(alex);
        server.onMessage(alex, "Alex", Packets.encode(new ClientHelloPacket(BlockPhone.PROTOCOL_VERSION, "test")));
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.Relay("durak", "alex", "{\"move\":1}")));
        AppPackets.Relayed got = platform.last(alex, AppPackets.Relayed.class);
        assertEquals("durak", got.appId());
        assertEquals("Steve", got.fromName());
        assertEquals("{\"move\":1}", got.payload());
        assertTrue(platform.of(STEVE, AppPackets.Relayed.class).isEmpty(), "no echo to the sender");
        // Приложение, запрещённое на сервере, не пересылается.
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.Relay("blocked", "", "x")));
        assertEquals("durak", platform.last(alex, AppPackets.Relayed.class).appId());
        server.onMessage(STEVE, "Steve", Packets.encode(new AppPackets.RequestPlayers("durak")));
        assertEquals(List.of("Alex"), platform.last(STEVE, AppPackets.Players.class).names());
    }
}

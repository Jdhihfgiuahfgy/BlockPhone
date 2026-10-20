package dev.blockphone.common.apps;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Формат клиентских приложений: загрузка, разрешения, поля, подстановки, политика сервера. */
class AppLoaderTest {

    private static final String TAPPER = """
            {"id": "tapper", "name": {"ru_ru": "Кликер", "en_us": "Tapper"}, "permissions": ["storage"],
             "pages": {"main": {"elements": [
               {"type": "text", "text": "{taps|0}"},
               {"type": "button", "label": "+", "action": {"add": {"taps": 1}}},
               {"type": "card", "elements": [{"type": "row", "label": "x", "action": {"open": "about"}}]}
             ]}, "about": {"elements": []}}}
            """;

    @Test
    void parsesPagesActionsAndHash() throws Exception {
        AppDefinition app = AppLoader.parse(TAPPER, null, "x");
        assertEquals("tapper", app.id());
        assertEquals("Кликер", app.name("ru_ru"));
        assertEquals("Tapper", app.name("de_de"));
        assertEquals("main", app.start());
        assertTrue(app.has(AppPermission.STORAGE));
        assertEquals(2, app.actions().size());
        assertTrue(app.actions().containsKey("main:0"));
        assertEquals(64, app.sha256().length());
        assertEquals(AppLoader.sha256(TAPPER), app.sha256());
    }

    @Test
    void actionsNeedDeclaredPermissions() {
        String noPermission = TAPPER.replace("\"permissions\": [\"storage\"],", "");
        AppLoader.AppException e = assertThrows(AppLoader.AppException.class, () -> AppLoader.parse(noPermission, null, "x"));
        assertTrue(e.getMessage().contains("storage"));
        assertThrows(AppLoader.AppException.class, () -> AppLoader.parse("""
                {"pages": {"main": {"elements": [{"type": "button", "action": {"command": "spawn"}}]}}}""", null, "x"));
        assertThrows(AppLoader.AppException.class, () -> AppLoader.parse("""
                {"pages": {"main": {"server": true, "elements": []}}}""", null, "x"));
        assertThrows(AppLoader.AppException.class, () -> AppLoader.parse("""
                {"permissions": ["xray"], "pages": {"main": {}}}""", null, "x"));
        assertThrows(AppLoader.AppException.class, () -> AppLoader.parse("""
                {"pages": {"main": {"elements": [{"type": "button", "action": {"open": "missing"}}]}}}""", null, "x"));
        assertThrows(AppLoader.AppException.class, () -> AppLoader.parse("{\"id\": \"Bad Id\", \"pages\": {\"m\": {}}}", null, "x"));
    }

    @Test
    void loadsFolderAndSkipsBrokenApps(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("tapper"));
        Files.writeString(dir.resolve("tapper").resolve("app.json"), TAPPER);
        Files.writeString(dir.resolve("broken.json"), "{oops");
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        AppLoader.Result result = AppLoader.loadDirectory(dir);
        assertEquals(1, result.apps().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).startsWith("broken.json"));
    }

    @Test
    void substitutesWithDefaults() {
        assertEquals("Привет, Steve! 0", AppText.substitute("Привет, {player}! {taps|0}", Map.of("player", "Steve")));
        assertEquals("{unknown}", AppText.substitute("{unknown}", Map.of("a", "b")));
    }

    @Test
    void inputRules() {
        JsonObject number = JsonParser.parseString("{\"kind\": \"number\", \"min\": 1, \"max\": 100, \"required\": true}").getAsJsonObject();
        assertEquals("@app.error.required", InputRules.validate(number, " "));
        assertEquals("@app.error.number", InputRules.validate(number, "12abc"));
        assertEquals("@app.error.min", InputRules.validate(number, "0"));
        assertEquals("@app.error.max", InputRules.validate(number, "101"));
        assertNull(InputRules.validate(number, "2,5"));
        assertEquals("2.5", InputRules.normalize(number, "2,5"));
        JsonObject player = JsonParser.parseString("{\"kind\": \"player\"}").getAsJsonObject();
        assertEquals("@app.error.player", InputRules.validate(player, "Steve; op Steve"));
        assertNull(InputRules.validate(player, "Steve_1"));
        assertEquals("a b", InputRules.clean("a\nb§c".replace("§c", "")));
    }

    @Test
    void serverPolicy() {
        List<String> black = List.of("tapper", "sha256:abc");
        assertFalse(AppsSettings.allowed("blacklist", black, List.of(), "tapper", "zzz"));
        assertFalse(AppsSettings.allowed("blacklist", black, List.of(), "renamed", "abc"));
        assertTrue(AppsSettings.allowed("blacklist", black, List.of(), "bank", "def"));
        assertTrue(AppsSettings.allowed("whitelist", List.of(), List.of("bank"), "bank", "def"));
        assertFalse(AppsSettings.allowed("whitelist", List.of(), List.of("bank"), "tapper", "def"));
        assertFalse(AppsSettings.allowed("none", List.of(), List.of(), "bank", "def"));
    }
}

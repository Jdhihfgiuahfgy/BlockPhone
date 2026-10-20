package dev.blockphone.fabric.client.telegram;

import it.tdlight.ClientFactory;
import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.TelegramClient;
import it.tdlight.UpdatesHandler;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDLib (TDLight) загружается из встроенной нативной библиотеки и доходит до «введите номер телефона».
 * Ключи ненастоящие — до проверки на серверах Telegram дело не доходит. Только Windows x64.
 */
class TdlibSmokeTest {
    @TempDir
    Path dir;

    @Test
    void reachesWaitPhoneNumber() throws Exception {
        Assumptions.assumeTrue(Telegram.supported(), "TDLib natives are bundled for Windows x64 only");
        Init.init();
        Log.setVerbosityLevel(0);
        CompletableFuture<TdApi.AuthorizationState> waitPhone = new CompletableFuture<>();
        try (ClientFactory factory = ClientFactory.create()) {
            TelegramClient client = factory.createClient();
            UpdatesHandler handler = updates -> {
                for (TdApi.Object update : updates) {
                    if (update instanceof TdApi.UpdateAuthorizationState state) {
                        if (state.authorizationState instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
                            TdApi.SetTdlibParameters p = new TdApi.SetTdlibParameters();
                            p.databaseDirectory = dir.resolve("db").toString();
                            p.filesDirectory = dir.resolve("files").toString();
                            p.databaseEncryptionKey = new byte[0];
                            p.apiId = 1;
                            p.apiHash = "0123456789abcdef0123456789abcdef";
                            p.systemLanguageCode = "ru";
                            p.deviceModel = "BlockPhone test";
                            p.systemVersion = "test";
                            p.applicationVersion = "test";
                            client.send(p, result -> {
                                if (result instanceof TdApi.Error error) {
                                    waitPhone.completeExceptionally(new AssertionError(error.message));
                                }
                            }, waitPhone::completeExceptionally);
                        } else if (state.authorizationState instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
                            waitPhone.complete(state.authorizationState);
                        }
                    }
                }
            };
            client.initialize(handler, waitPhone::completeExceptionally, waitPhone::completeExceptionally);
            assertTrue(waitPhone.get(30, TimeUnit.SECONDS) instanceof TdApi.AuthorizationStateWaitPhoneNumber);
            client.send(new TdApi.Close(), r -> {
            }, e -> {
            });
        }
    }
}

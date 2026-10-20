package com.example.bankphone;

import dev.blockphone.common.apps.AppResult;
import dev.blockphone.common.apps.BlockPhoneApps;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Пример серверной части приложения «Банк» на Java (плагин Paper + Vault). Клиентская часть — docs/examples/bank-client.
 *
 * plugin.yml:  depend: [BlockPhone, Vault]
 * build.gradle: compileOnly files("BlockPhone-paper-<версия>.jar"), compileOnly "com.github.MilkBowl:VaultAPI:1.7"
 *
 * Без своего плагина то же самое делает файл docs/examples/bank-server/bank.json (команда /pay + PlaceholderAPI).
 */
public final class BankPhoneApp extends JavaPlugin {
    private Economy economy;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().warning("Vault economy not found");
            return;
        }
        economy = provider.getProvider();

        BlockPhoneApps.handler("bank", new BlockPhoneApps.AppHandler() {
            /** Переменные страницы: {balance} — подставится в app.json. Вызывается в главном потоке сервера. */
            @Override
            public Map<String, String> variables(UUID player, String page) {
                OfflinePlayer p = Bukkit.getOfflinePlayer(player);
                return Map.of("balance", economy.format(economy.getBalance(p)));
            }

            /** Действие {"server": "transfer"}: поля to и amount уже проверены, если описаны в bank.json (иначе — проверьте сами). */
            @Override
            public AppResult call(UUID player, String function, Map<String, String> inputs) {
                if (!function.equals("transfer")) {
                    return null; // не наша функция
                }
                Player from = Bukkit.getPlayer(player);
                Player to = Bukkit.getPlayerExact(inputs.getOrDefault("to", ""));
                BigDecimal amount;
                try {
                    amount = new BigDecimal(inputs.getOrDefault("amount", ""));
                } catch (NumberFormatException e) {
                    return AppResult.error("Неверная сумма");
                }
                if (from == null || to == null || to.equals(from)) {
                    return AppResult.error("Игрок не найден");
                }
                double value = amount.doubleValue();
                if (value <= 0 || !economy.has(from, value)) {
                    return AppResult.error("Недостаточно денег");
                }
                economy.withdrawPlayer(from, value);
                economy.depositPlayer(to, value);
                to.sendMessage("Вам перевели " + economy.format(value) + " от " + from.getName());
                return AppResult.ok("Отправлено " + economy.format(value)).then("main");
            }
        });
    }

    @Override
    public void onDisable() {
        BlockPhoneApps.unregister("bank");
    }
}

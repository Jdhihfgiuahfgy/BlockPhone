package dev.blockphone.paper;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.ProtocolException;
import dev.blockphone.common.protocol.packet.ClientHelloPacket;
import dev.blockphone.common.protocol.packet.ServerHelloPacket;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Точка входа Paper-плагина. */
public final class BlockPhonePaper extends JavaPlugin implements Listener, PluginMessageListener {
    /** Игроки, у которых подтверждённо стоит мод (прислали ClientHello). */
    private final Set<UUID> moddedPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, BlockPhone.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, BlockPhone.CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BlockPhone " + getPluginMeta().getVersion() + " enabled (protocol " + BlockPhone.PROTOCOL_VERSION + ")");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        moddedPlayers.clear();
    }

    /** Клиент с модом объявил, что слушает наш канал — здороваемся. */
    @EventHandler
    public void onChannelRegister(PlayerRegisterChannelEvent event) {
        if (BlockPhone.CHANNEL.equals(event.getChannel())) {
            send(event.getPlayer(), new ServerHelloPacket(
                    BlockPhone.PROTOCOL_VERSION,
                    "paper",
                    getConfig().getBoolean("messenger.enabled", true),
                    getConfig().getBoolean("phone-in-hand.visible", true)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        moddedPlayers.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BlockPhone.CHANNEL.equals(channel)) {
            return;
        }
        Packet packet;
        try {
            packet = Packets.decode(message);
        } catch (ProtocolException e) {
            getLogger().warning("Bad packet from " + player.getName() + ": " + e.getMessage());
            return;
        }
        if (packet instanceof ClientHelloPacket hello) {
            moddedPlayers.add(player.getUniqueId());
            getLogger().info(player.getName() + " joined with BlockPhone " + hello.modVersion()
                    + " (protocol " + hello.protocolVersion() + ")");
        }
    }

    public void send(Player player, Packet packet) {
        player.sendPluginMessage(this, BlockPhone.CHANNEL, Packets.encode(packet));
    }

    public boolean hasMod(Player player) {
        return moddedPlayers.contains(player.getUniqueId());
    }
}

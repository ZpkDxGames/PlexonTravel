package com.plexon.travel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TravelMessages {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
    private final Set<String> warnedInvalidMiniMessage = ConcurrentHashMap.newKeySet();
    private YamlConfiguration messages;

    TravelMessages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
            }
        } catch (Exception failure) {
            plugin.getLogger().warning("Unable to load embedded messages.yml defaults: " + failure.getMessage());
        }
        warnedInvalidMiniMessage.clear();
    }

    Component raw(String miniMessageText) {
        String text = miniMessageText == null ? "" : miniMessageText;
        try {
            return miniMessage.deserialize(text);
        } catch (RuntimeException invalid) {
            if (warnedInvalidMiniMessage.add(text)) {
                plugin.getLogger().warning("Invalid MiniMessage text was rendered literally instead of failing a command: " + invalid.getMessage());
            }
            return Component.text(text);
        }
    }

    Component render(String path, String fallback) {
        return raw(messages.getString(path, fallback));
    }

    Component render(String path, String fallback, Map<String, ?> placeholders) {
        String text = messages.getString(path, fallback);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", escape(String.valueOf(entry.getValue())));
        }
        return raw(text);
    }

    void send(CommandSender sender, String path, String fallback) {
        sender.sendMessage(prefix().append(Component.space()).append(render(path, fallback)));
    }

    void send(CommandSender sender, String path, String fallback, Map<String, ?> placeholders) {
        sender.sendMessage(prefix().append(Component.space()).append(render(path, fallback, placeholders)));
    }

    void sendRaw(CommandSender sender, String text) {
        sender.sendMessage(prefix().append(Component.space()).append(raw(text)));
    }

    String string(String path, String fallback) {
        return messages.getString(path, fallback);
    }

    String legacy(Component component) {
        return legacy.serialize(component);
    }

    private Component prefix() {
        return raw(messages.getString("prefix", "<gradient:#22d3ee:#38bdf8><bold>PlexonTravel</bold></gradient><dark_gray> »</dark_gray>"));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("<", "\\<");
    }
}

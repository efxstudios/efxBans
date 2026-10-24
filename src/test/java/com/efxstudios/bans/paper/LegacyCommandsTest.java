package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyCommandsTest {
    @Test
    void oldPaperKeepsNamespacedCommandsAndUsesTheSamePermissionsAndTargetSyntax() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var commandMap = new SimpleCommandMap(mock(Server.class), new java.util.HashMap<>());
            bukkit.when(Bukkit::getCommandMap).thenReturn(commandMap);
            PluginCommand foreign = mock(PluginCommand.class);
            when(foreign.getName()).thenReturn("ban");
            when(foreign.getLabel()).thenReturn("ban");
            when(foreign.getAliases()).thenReturn(List.of());
            when(foreign.getPlugin()).thenReturn(mock(Plugin.class));
            commandMap.register("essentials", foreign);
            BansPlugin plugin = mock(BansPlugin.class);
            Permissions permissions = mock(Permissions.class);
            Settings settings = mock(Settings.class);
            when(plugin.permissions()).thenReturn(permissions);
            when(plugin.settings()).thenReturn(settings);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(settings.presets()).thenReturn(Map.of("küfür",
                    new Settings.Preset("küfür", List.of("kufur"), Kind.MUTE, 1000, "Reason", "efxbans.preset.kufur", true)));
            when(plugin.online()).thenReturn(Map.of(UUID.randomUUID(), new BansPlugin.Online(UUID.randomUUID(), "Alice", "127.0.0.1", 0)));
            LegacyCommands registrar = new LegacyCommands(plugin, commandMap);
            new CommandTree<CommandSender>(plugin).register(registrar);
            assertSame(foreign, commandMap.getCommand("essentials:ban"));
            assertNotSame(foreign, commandMap.getCommand("ban"));
            for (String label : List.of("ban", "unban", "lookup", "sorgu", "küfür", "kufur"))
                assertSame(commandMap.getCommand(label), commandMap.getCommand("efxbans:" + label), label);
            CommandSender sender = mock(CommandSender.class);
            assertTrue(commandMap.getCommand("ban").tabComplete(sender, "ban", new String[]{"A"}).isEmpty());
            when(permissions.canIssue(sender, Kind.BAN)).thenReturn(true);
            assertEquals(List.of("Alice"), commandMap.getCommand("ban").tabComplete(sender, "ban", new String[]{"A"}));
            when(permissions.has(eq(sender), anyString())).thenReturn(true);
            for (String command : List.of("unban #123", "unban 2001:db8::1", "unmute Alice", "sorgu #5")) {
                var parsed = registrar.getDispatcher().parse(command, sender);
                assertFalse(parsed.getReader().canRead(), command);
                assertNotNull(parsed.getContext().getCommand(), command);
            }
        }
    }
}

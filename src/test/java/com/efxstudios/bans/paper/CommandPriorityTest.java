package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandPriorityTest {
    private static final List<String> COLLIDING = List.of("ban", "unban", "mute", "küfür", "kufur");

    @Test
    void registrarReceivesCollidingRootsAndPresetAliasesWithBansPermissions() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            CommandMap map = mock(CommandMap.class);
            bukkit.when(Bukkit::getCommandMap).thenReturn(map);
            PluginCommand foreign = mock(PluginCommand.class);
            when(foreign.getPlugin()).thenReturn(mock(Plugin.class));
            when(map.getCommand(anyString())).thenReturn(foreign);

            BansPlugin plugin = mock(BansPlugin.class);
            Settings settings = mock(Settings.class);
            when(plugin.settings()).thenReturn(settings);
            when(plugin.permissions()).thenReturn(mock(Permissions.class));
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(settings.presets()).thenReturn(Map.of("küfür",
                    new Settings.Preset("küfür", List.of("kufur"), Kind.MUTE, 1000, "Sebep", "efxbans.preset.kufur", true)));

            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            for (String name : COLLIDING)
                dispatcher.register(Commands.literal(name).executes(c -> 99).then(Commands.literal("foreign").executes(c -> 99)));
            var namespaced = dispatcher.register(Commands.literal("essentials:ban").executes(c -> 99));
            Commands registrar = mock(Commands.class);
            when(registrar.getDispatcher()).thenReturn(dispatcher);
            Map<String, LiteralCommandNode<CommandSourceStack>> registered = new HashMap<>();
            doAnswer(call -> {
                LiteralCommandNode<CommandSourceStack> node = call.getArgument(0);
                registered.put(node.getLiteral(), node);
                return Set.of(node.getLiteral());
            }).when(registrar).register(any(LiteralCommandNode.class), eq("efxBans"), eq(List.of()));

            new CommandTree<>(plugin, CommandSourceStack::getSender).register(new ModernCommands(registrar));

            for (String name : COLLIDING) {
                var node = registered.get(name);
                assertNotNull(node, name);
                assertFalse(node.getRequirement().test(mock(CommandSourceStack.class)), name);
                assertNull(node.getChild("foreign"), name);
            }
            assertSame(namespaced, dispatcher.getRoot().getChild("essentials:ban"));
            verify(plugin.getLogger()).warning(contains("/kufur"));
        }
    }
}

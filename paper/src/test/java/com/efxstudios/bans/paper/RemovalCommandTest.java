package com.efxstudios.bans.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RemovalCommandTest {
    @Test
    void removalCommandsAcceptOnlyTargetAndUndoNoLongerRequiresReason() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCommandMap).thenReturn(mock(CommandMap.class));
            BansPlugin plugin = mock(BansPlugin.class);
            Settings settings = mock(Settings.class);
            Permissions permissions = mock(Permissions.class);
            when(plugin.settings()).thenReturn(settings);
            when(settings.presets()).thenReturn(Map.of());
            when(plugin.permissions()).thenReturn(permissions);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(permissions.has(any(), anyString())).thenReturn(true);

            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            Commands registrar = mock(Commands.class);
            when(registrar.getDispatcher()).thenReturn(dispatcher);
            doAnswer(call -> {
                LiteralCommandNode<CommandSourceStack> node = call.getArgument(0);
                dispatcher.getRoot().addChild(node);
                return Set.of(node.getLiteral());
            }).when(registrar).register(any(LiteralCommandNode.class), eq("efxBans"), anyList());
            new CommandTree(plugin).register(registrar);

            CommandSourceStack source = mock(CommandSourceStack.class);
            for (String command : List.of("unban Alice", "unmute Alice", "undo #1")) {
                var parsed = dispatcher.parse(command, source);
                assertFalse(parsed.getReader().canRead(), command);
                assertNotNull(parsed.getContext().getCommand(), command);
                // A trailing reason is not part of the syntax.
                assertTrue(dispatcher.parse(command + " extra reason", source).getReader().canRead(), command);
            }
        }
    }
}

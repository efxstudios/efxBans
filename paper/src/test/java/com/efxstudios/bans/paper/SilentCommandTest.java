package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Problem;
import com.efxstudios.bans.common.Punishment;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SilentCommandTest {
    @Test
    void silentConfirmationStillInvokesDiscordAndRequiresSilentPermission() throws Exception {
        BansPlugin plugin = mock(BansPlugin.class);
        Permissions permissions = mock(Permissions.class);
        Settings settings = mock(Settings.class);
        CommandSender sender = mock(CommandSender.class);
        when(plugin.permissions()).thenReturn(permissions);
        when(plugin.settings()).thenReturn(settings);
        when(settings.messages()).thenReturn(new Messages("", Map.of(), "UTC"));
        when(sender.getName()).thenReturn("Staff");
        UUID uuid = UUID.randomUUID();
        Punishment punishment = new Punishment(1, Kind.MUTE, uuid, "OfflineTarget", null, "CONSOLE", "Staff", "Sebep", 1, 0, "ACTIVE", 0,
                null, null, null, true);
        doAnswer(call -> {
            Consumer<Punishment> callback = call.getArgument(2);
            callback.accept(punishment);
            return null;
        }).when(plugin).async(eq(sender), any(), any());

        Method persist = CommandTree.class.getDeclaredMethod("persist", CommandSender.class, Kind.class, long.class, String.class, UUID.class,
                String.class, String.class, int.class, String.class, boolean.class);
        persist.setAccessible(true);
        persist.invoke(new CommandTree(plugin), sender, Kind.MUTE, 0L, "Sebep", uuid, "OfflineTarget", null, 0, null, true);

        verify(permissions).require(sender, "efxbans.mute.silent");
        verify(plugin).tell(eq(sender), eq("confirmation.silent"), anyMap());
        verify(plugin).discord("mute", punishment, "Staff", "Sebep");
    }

    @Test
    void offlineKickStopsBeforeDatabaseWrite() throws Exception {
        BansPlugin plugin = mock(BansPlugin.class);
        when(plugin.permissions()).thenReturn(mock(Permissions.class));
        when(plugin.online()).thenReturn(Map.of());
        Method issue = CommandTree.class.getDeclaredMethod("issue", CommandSender.class, String.class, Kind.class, long.class, String.class,
                String.class, boolean.class);
        issue.setAccessible(true);

        var exception = assertThrows(InvocationTargetException.class,
                () -> issue.invoke(new CommandTree(plugin), mock(CommandSender.class), "OfflineTarget", Kind.KICK, 0L, "Sebep", null, false));

        assertInstanceOf(Problem.class, exception.getCause());
        assertEquals("errors.player-offline", ((Problem) exception.getCause()).key());
        verify(plugin, never()).async(any(), any(), any());
    }
}

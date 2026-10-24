package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnforcementTest {
    @Test
    void mutedChatIsCancelledAndFeedbackRunsOnThePlayerRegion() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
            BansPlugin plugin = mock(BansPlugin.class);
            when(plugin.settings()).thenReturn(mock(Settings.class));
            var messages = new com.efxstudios.bans.common.Messages("", java.util.Map.of(), "UTC");
            when(plugin.settings().messages()).thenReturn(messages);
            var mute = new Punishment(1, Kind.MUTE, player.getUniqueId(), "Muted", null, "CONSOLE", "Staff", "Reason", 1, 0,
                    "ACTIVE", 0, null, null, null, false);
            when(plugin.punishment(eq(player), any())).thenReturn(Optional.of(mute));
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(plugin).player(eq(player), any());
            AsyncChatEvent event = mock(AsyncChatEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.message()).thenReturn(Component.text("Blocked message"));

            new Enforcement(plugin).chat(event);

            verify(event).setCancelled(true);
            verify(plugin).player(eq(player), any());
            verify(plugin).tell(eq(player), eq("muted"), anyMap());
            bukkit.verify(Bukkit::getPluginManager, never());
        }
    }

    @Test
    void healthyUnmutedPlayerCanChatButUnavailableDatabaseBlocksChat() {
        BansPlugin plugin = mock(BansPlugin.class);
        Player player = mock(Player.class);
        when(plugin.punishment(eq(player), any())).thenReturn(Optional.empty());
        when(plugin.healthy()).thenReturn(true);
        AsyncChatEvent event = mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.message()).thenReturn(Component.text("Message"));
        new Enforcement(plugin).chat(event);
        verify(event, never()).setCancelled(true);

        when(plugin.healthy()).thenReturn(false);
        new Enforcement(plugin).chat(event);
        verify(event).setCancelled(true);
        verify(plugin).player(eq(player), any());
    }
}

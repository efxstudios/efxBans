package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Punishment;
import com.efxstudios.efxCore.control.ControlManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreChatTest {
    /** Stands in for the efxCore main class; the fixture ControlManager lives in src/test. */
    public static class FakeCore extends JavaPlugin {
        public ControlManager getControlManager() {
            return null;
        }
    }

    @Test
    void mutedControlMessageIsCancelledRoutedOnceAndNeverProducesMuteFeedback() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            PluginManager manager = mock(PluginManager.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            FakeCore core = mock(FakeCore.class);
            when(core.isEnabled()).thenReturn(true);
            when(manager.getPlugin("efxCore")).thenReturn(core);
            var routing = new ControlManager();
            routing.control = true;
            when(core.getControlManager()).thenReturn(routing);

            BansPlugin plugin = mock(BansPlugin.class);
            Settings settings = mock(Settings.class);
            when(plugin.settings()).thenReturn(settings);
            when(settings.coreIntegration()).thenReturn(true);
            when(settings.messages()).thenReturn(new Messages("", Map.of(), "UTC"));
            doAnswer(call -> {
                ((Runnable) call.getArgument(0)).run();
                return null;
            }).when(plugin).main(any());

            Player player = mock(Player.class);
            when(player.isOnline()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            Punishment mute = new Punishment(1, Kind.MUTE, player.getUniqueId(), "Target", null, "CONSOLE", "Console", "Test",
                    System.currentTimeMillis(), 0, "ACTIVE", 0, null, null, null, false);
            when(plugin.punishment(eq(player), any())).thenReturn(Optional.of(mute));
            AsyncChatEvent event = mock(AsyncChatEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.message()).thenReturn(Component.text("Kontrol mesajı"));

            new Enforcement(plugin).chat(event);
            verify(event).setCancelled(true);
            assertEquals(List.of("Kontrol mesajı"), routing.routed);
            verify(plugin, never()).tell(any(), anyString(), anyMap());

            // The same mute outside a control session blocks the message and gives feedback.
            routing.control = false;
            new Enforcement(plugin).chat(event);
            assertEquals(1, routing.routed.size());
            verify(plugin).tell(eq(player), eq("muted"), anyMap());
        }
    }

    @Test
    void absentCoreLeavesMuteEnforcementWorking() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            BansPlugin plugin = mock(BansPlugin.class);
            Settings settings = mock(Settings.class);
            when(settings.coreIntegration()).thenReturn(true);
            when(plugin.settings()).thenReturn(settings);

            assertFalse(new CoreBridge(plugin).route(mock(Player.class), "Test"));
        }
    }
}

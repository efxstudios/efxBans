package com.efxstudios.bans.paper;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulerTest {
    @Test
    void playerCallbackWaitsForOwningRegionInsteadOfGlobalScheduler() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            Player player = mock(Player.class);
            EntityScheduler entity = mock(EntityScheduler.class);
            when(player.getScheduler()).thenReturn(entity);
            Runnable action = mock(Runnable.class);
            when(entity.execute(eq(plugin), any(), isNull(), eq(1L))).thenReturn(true);

            new Scheduler(plugin).sender(player, action);

            verify(entity).execute(plugin, action, null, 1);
            verify(action, never()).run();
            bukkit.verify(Bukkit::getGlobalRegionScheduler, never());
        }
    }

    @Test
    void alreadyOwnedPlayerRunsImmediatelyAndRetiredTargetCompletesFallback() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            Player player = mock(Player.class);
            Runnable action = mock(Runnable.class);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
            new Scheduler(plugin).entity(player, action);
            verify(action).run();
            verify(player, never()).getScheduler();

            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);
            EntityScheduler entity = mock(EntityScheduler.class);
            when(player.getScheduler()).thenReturn(entity);
            Runnable retired = mock(Runnable.class);
            new Scheduler(plugin).entity(player, action, retired);
            verify(retired).run();
            verify(action, times(1)).run();
        }
    }

    @Test
    void consoleUsesGlobalSchedulerAndDisabledPluginDoesNotSchedule() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            Runnable action = mock(Runnable.class);
            new Scheduler(plugin).sender(mock(CommandSender.class), action);
            verify(global).execute(eq(plugin), any(Runnable.class));
            when(plugin.isEnabled()).thenReturn(false);
            new Scheduler(plugin).global(action);
            verifyNoMoreInteractions(global);
        }
    }
}

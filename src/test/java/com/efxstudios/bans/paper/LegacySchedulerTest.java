package com.efxstudios.bans.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacySchedulerTest {
    @Test
    void oldPaperMovesAsyncEntityWorkToMainThreadAndRetiresOfflinePlayers() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            BukkitScheduler bukkitScheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(bukkitScheduler);
            Player player = mock(Player.class);
            Runnable action = mock(Runnable.class);
            Runnable retired = mock(Runnable.class);
            new Scheduler(plugin).entity(player, action, retired);
            verify(bukkitScheduler).runTask(eq(plugin), any(Runnable.class));
            verifyNoInteractions(action, retired);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            when(player.isValid()).thenReturn(true);
            new Scheduler(plugin).entity(player, action, retired);
            verify(retired).run();
            when(player.isOnline()).thenReturn(true);
            new Scheduler(plugin).entity(player, action, retired);
            verify(action).run();
        }
    }
}

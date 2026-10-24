package com.efxstudios.bans.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/** Uses the same ownership-aware schedulers on both Paper and Folia. */
final class Scheduler {
    private static final ThreadLocal<Boolean> GLOBAL = ThreadLocal.withInitial(() -> false);
    private final Plugin plugin;

    Scheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    void global(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                GLOBAL.set(true);
                try {
                    action.run();
                } finally {
                    GLOBAL.remove();
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // Plugin is shutting down.
        }
    }

    void entity(Entity entity, Runnable action) {
        entity(entity, action, null);
    }

    void entity(Entity entity, Runnable action, Runnable retired) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            action.run();
            return;
        }
        try {
            if (!entity.getScheduler().execute(plugin, action, retired, 1) && retired != null) retired.run();
        } catch (IllegalPluginAccessException ignored) {
            // Plugin is shutting down; retired entities silently drop pending work.
        }
    }

    void sender(CommandSender sender, Runnable action) {
        if (sender instanceof Entity entity) entity(entity, action);
        else if (sender instanceof BlockCommandSender block) {
            if (!plugin.isEnabled()) return;
            if (Bukkit.isOwnedByCurrentRegion(block.getBlock())) {
                action.run();
                return;
            }
            try {
                Bukkit.getRegionScheduler().execute(plugin, block.getBlock().getLocation(), action);
            } catch (IllegalPluginAccessException ignored) {
                // Plugin is shutting down.
            }
        } else if (plugin.isEnabled() && GLOBAL.get()) action.run();
        else global(action);
    }
}

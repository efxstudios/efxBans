package com.efxstudios.bans.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

final class FoliaScheduler extends Scheduler {
    private static final ThreadLocal<Boolean> GLOBAL = ThreadLocal.withInitial(() -> false);

    FoliaScheduler(Plugin plugin) {
        super(plugin);
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

            }
        } else if (plugin.isEnabled() && GLOBAL.get()) action.run();
        else global(action);
    }
    @Override
    void track(org.bukkit.entity.Player player, Runnable action, long period) {
        player.getScheduler().runAtFixedRate(plugin, task -> action.run(), null, period, period);
    }

    @Override
    void async(Runnable action) { Bukkit.getAsyncScheduler().runNow(plugin, task -> action.run()); }

    @Override
    Runnable repeatAsync(Runnable action, long delaySeconds, long periodSeconds) {
        var task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> action.run(), delaySeconds, periodSeconds, java.util.concurrent.TimeUnit.SECONDS);
        return task::cancel;
    }

    @Override
    void cancel() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }
}

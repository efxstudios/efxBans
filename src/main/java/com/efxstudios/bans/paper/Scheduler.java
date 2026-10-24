package com.efxstudios.bans.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

class Scheduler {
    protected final Plugin plugin;

    Scheduler(Plugin plugin) { this.plugin = plugin; }

    static Scheduler create(Plugin plugin) {
        if (!Platform.available("io.papermc.paper.threadedregions.RegionizedServer")) return new Scheduler(plugin);
        try {
            return (Scheduler) Class.forName("com.efxstudios.bans.paper.FoliaScheduler").getDeclaredConstructor(Plugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not initialize the Folia scheduler", e);
        }
    }

    void global(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (IllegalPluginAccessException ignored) {
        }
    }

    void entity(Entity entity, Runnable action) { entity(entity, action, null); }

    void entity(Entity entity, Runnable action, Runnable retired) {
        Runnable guarded = () -> {
            if (entity.isValid() && (!(entity instanceof Player player) || player.isOnline())) action.run();
            else if (retired != null) retired.run();
        };
        if (plugin.isEnabled() && Bukkit.isPrimaryThread()) guarded.run();
        else global(guarded);
    }

    void sender(CommandSender sender, Runnable action) {
        if (plugin.isEnabled() && Bukkit.isPrimaryThread()) action.run();
        else global(action);
    }

    void track(Player player, Runnable action, long period) {
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (player.isOnline()) action.run();
            else task.cancel();
        }, period, period);
    }

    void async(Runnable action) { Bukkit.getScheduler().runTaskAsynchronously(plugin, action); }

    Runnable repeatAsync(Runnable action, long delaySeconds, long periodSeconds) {
        var task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, action, delaySeconds * 20, periodSeconds * 20);
        return task::cancel;
    }

    void cancel() { Bukkit.getScheduler().cancelTasks(plugin); }
}

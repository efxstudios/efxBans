package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Applies bans at login and mutes in chat. Every path fails closed when the database can't be verified. */
public final class Enforcement implements Listener {
    private static final Set<Kind> BANS = Set.of(Kind.BAN, Kind.IPBAN);
    private static final Set<Kind> MUTES = Set.of(Kind.MUTE);
    private static final long FEEDBACK_COOLDOWN_MILLIS = 1500;

    private final BansPlugin plugin;
    private final CoreBridge core;
    private final Map<UUID, Long> feedback = new HashMap<>(); // Main thread only.

    public Enforcement(BansPlugin plugin) {
        this.plugin = plugin;
        this.core = new CoreBridge(plugin);
    }

    /** Checks the database itself, not the cache: a ban issued on another server a moment ago still applies. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        var messages = plugin.settings().messages();
        try {
            if (plugin.engine() == null) throw new IllegalStateException("DB unavailable");
            String address = event.getAddress().getHostAddress();
            List<Punishment> rows = plugin.engine().call(store -> store.activeFor(event.getUniqueId(), address)).get(8, TimeUnit.SECONDS);
            Optional<Punishment> ban = rows.stream().filter(p -> BANS.contains(p.kind())).findFirst();
            ban.ifPresent(p -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, messages.render("screen." + p.kind().key(), messages.values(p))));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.render("unavailable", Map.of()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.remember(player);
        plugin.deliverWarnings(player);
        // Closes the race between the login check and a ban committed right after it.
        // Permissions never bypass an already recorded punishment.
        var messages = plugin.settings().messages();
        plugin.punishment(player, BANS).ifPresent(p -> player.kick(messages.render("screen." + p.kind().key(), messages.values(p))));
        if (plugin.updates() != null) plugin.updates().notify(player);
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        plugin.online().remove(event.getPlayer().getUniqueId());
        feedback.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Optional<Punishment> mute = plugin.punishment(player, MUTES);
        if (mute.isEmpty() && plugin.healthy()) return;
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.main(() -> {
            if (!player.isOnline()) return;
            // Route before feedback: a muted control-session message never produces a mute warning.
            if (core.route(player, raw)) return;
            warn(player, mute.orElse(null));
        });
    }

    /** Blocks private-message style commands (mute.blocked-commands) for muted players, namespaced or not. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!plugin.settings().muteCommands().contains(label)) return;
        Optional<Punishment> mute = plugin.punishment(event.getPlayer(), MUTES);
        if (mute.isEmpty() && plugin.healthy()) return;
        event.setCancelled(true);
        warn(event.getPlayer(), mute.orElse(null));
    }

    private void warn(Player player, Punishment mute) {
        long now = System.currentTimeMillis();
        if (now - feedback.getOrDefault(player.getUniqueId(), 0L) < FEEDBACK_COOLDOWN_MILLIS) return;
        feedback.put(player.getUniqueId(), now);
        if (mute != null) plugin.tell(player, "muted", plugin.settings().messages().values(mute));
        else plugin.tell(player, "unavailable", Map.of());
    }
}

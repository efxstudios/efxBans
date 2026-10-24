package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Engine;
import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Problem;
import com.efxstudios.bans.common.Punishment;
import com.efxstudios.bans.common.Store;
import com.efxstudios.bans.common.Webhook;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class BansPlugin extends JavaPlugin {
    /** Last known identity of an online player, refreshed every 30 seconds. */
    public record Online(UUID uuid, String name, String ip, int exemptions) {}

    private static final long REFRESH_TICKS = 600;

    private volatile Settings settings;
    private Engine engine;
    private Webhook webhook;
    private Permissions permissions;
    private UpdateChecker updates;
    private final Map<UUID, Online> online = new ConcurrentHashMap<>();
    private final Set<UUID> warningDeliveryInFlight = new HashSet<>(); // Main thread only.
    private Map<String, List<String>> registeredTopology;

    public Settings settings() { return settings; }
    public Engine engine() { return engine; }
    public Permissions permissions() { return permissions; }
    public Map<UUID, Online> online() { return online; }
    public UpdateChecker updates() { return updates; }

    @Override
    public void onEnable() {
        try {
            settings = Settings.load(this);
        } catch (Exception e) {
            getLogger().severe("Ayarlar yüklenemedi: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        permissions = new Permissions(this);
        registeredTopology = settings.topology();
        webhook = new Webhook(getLogger()::warning);
        updates = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(new Enforcement(this), this);
        // Runs after ordinary handlers; the public registrar replaces colliding roots.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS
                .newHandler(event -> new CommandTree(this).register(event.registrar()))
                .priority(Integer.MAX_VALUE));
        openDatabase();
        for (Player p : Bukkit.getOnlinePlayers()) remember(p);
        Bukkit.getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(this::remember), REFRESH_TICKS, REFRESH_TICKS);
        // Also detects commands registered by plugins after this plugin's lifecycle handler.
        Bukkit.getScheduler().runTask(this, () -> CommandTree.audit(this));
        updates.configure(settings.updates());
        getLogger().info("efxBans " + getPluginMeta().getVersion() + " hazır. Paper " + Bukkit.getMinecraftVersion() + "; "
                + settings.database().type() + "; preset komut değişiklikleri yeniden başlatma gerektirir.");
    }

    private void openDatabase() {
        Store store = null;
        try {
            store = new Store(settings.database());
            engine = new Engine(store, settings.pollMillis(), this::changes,
                    e -> getLogger().severe("Veritabanı güncellemesi başarısız; eski ceza önbelleği korundu: " + e.getClass().getSimpleName()));
        } catch (Exception e) {
            if (store != null) store.close();
            // Without a database every login is refused (fail closed) instead of letting banned players in.
            getLogger().severe("Veritabanı açılamadı (" + e.getClass().getSimpleName() + "). Girişler güvenli biçimde reddedilecek. "
                    + "Ayarları düzeltip sunucuyu yeniden başlatın.");
        }
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (engine != null) engine.close();
        if (webhook != null) webhook.close();
    }

    public boolean healthy() {
        return engine != null && engine.healthy(settings.staleMillis());
    }

    /** Runs on the main thread: immediately if already there, otherwise on the next tick. */
    public void main(Runnable action) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, action);
        } catch (IllegalPluginAccessException ignored) {
            // Plugin is shutting down.
        }
    }

    public void tell(CommandSender sender, String key, Map<String, String> values) {
        sender.sendMessage(settings.messages().render(key, values));
    }

    public void error(CommandSender sender, Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof Problem problem) {
            tell(sender, problem.key(), problem.values());
        } else if (cause instanceof IllegalArgumentException) {
            String fallback = settings.messages().text("texts.invalid-operation", "Geçersiz işlem");
            tell(sender, "error", Map.of("error", Objects.requireNonNullElse(cause.getMessage(), fallback)));
        } else {
            tell(sender, "database-error", Map.of());
            getLogger().warning("İşlem tamamlanamadı: " + cause.getClass().getSimpleName());
        }
    }

    /** Runs database work off the main thread, then the callback (or error message) on the main thread. */
    public <T> void async(CommandSender sender, Engine.Operation<T> work, Consumer<T> complete) {
        if (engine == null) {
            tell(sender, "database-error", Map.of());
            return;
        }
        engine.call(work).whenComplete((result, error) -> main(() -> {
            try {
                if (error != null) error(sender, error);
                else complete.accept(result);
            } catch (Exception e) {
                error(sender, e);
            }
        }));
    }

    public void remember(Player player) {
        Online identity = new Online(player.getUniqueId(), player.getName(), address(player), permissions.exemptions(player));
        online.put(identity.uuid(), identity);
        if (engine != null) engine.call(store -> {
            store.remember(identity.uuid(), identity.name(), identity.ip(), identity.exemptions());
            return null;
        });
    }

    public static String address(Player p) {
        return p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
    }

    /** Shows warnings issued while the player was offline. They stay pending until shown. */
    public void deliverWarnings(Player player) {
        if (engine == null || !warningDeliveryInFlight.add(player.getUniqueId())) return;
        UUID uuid = player.getUniqueId();
        engine.call(s -> s.pendingWarnings(uuid)).whenComplete((rows, error) -> main(() -> {
            warningDeliveryInFlight.remove(uuid);
            if (error != null || !player.isOnline()) return;
            for (Punishment warning : rows) tell(player, "warned", settings.messages().values(warning));
            if (!rows.isEmpty()) engine.call(s -> {
                s.acknowledgeWarnings(uuid, rows.stream().map(Punishment::id).toList());
                return null;
            });
        }));
    }

    public Optional<Punishment> punishment(Player p, Set<Kind> kinds) {
        return engine == null ? Optional.empty() : engine.cache.find(p.getUniqueId(), address(p), kinds);
    }

    public void reload(CommandSender sender) {
        try {
            Settings next = Settings.load(this);
            boolean restart = !next.database().equals(settings.database())
                    || next.pollMillis() != settings.pollMillis()
                    || !registeredTopology.equals(next.topology());
            // Database and poll interval stay fixed until restart. Everything else swaps at once, after validation.
            settings = new Settings(settings.database(), settings.pollMillis(), next.staleMillis(), next.publicBroadcast(), next.defaultMax(),
                    next.limits(), next.muteCommands(), next.consolePermissions(), next.coreIntegration(), next.presets(), next.messages(),
                    next.webhook(), next.updates());
            updates.configure(settings.updates());
            Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
            tell(sender, restart ? "reload-restart" : "reload", Map.of());
            if (restart) getLogger().warning("Veritabanı, sorgulama aralığı veya preset komut adları/alias/açık durumu değişti: tam sunucu yeniden başlatması gerekli.");
        } catch (Exception e) {
            error(sender, new IllegalArgumentException("Ayarlar uygulanmadı; önceki ayarlar korunuyor: " + e.getMessage()));
        }
    }

    /** Change-feed observer: kicks/notifies affected players and announces the change. */
    private void changes(List<Store.Change> changes) {
        main(() -> {
            for (Store.Change change : changes) {
                Punishment p = change.punishment();
                Map<String, String> values = settings.messages().values(p);
                values.put("staff", change.actorName());
                values.put("change_reason", change.reason());
                if (change.action().equals("ISSUE") && !p.state().equals("REVOKED")) enforce(p, values);
                if (change.action().equals("ISSUE") && p.silent()) continue;
                String key = switch (change.action()) {
                    case "ISSUE" -> "broadcast." + p.kind().key();
                    case "REMOVE" -> p.kind() == Kind.MUTE ? "broadcast.unmute" : "broadcast.unban";
                    default -> "broadcast.undo";
                };
                for (Player recipient : Bukkit.getOnlinePlayers())
                    if (settings.publicBroadcast() || permissions.has(recipient, "efxbans.notify")) tell(recipient, key, values);
                tell(Bukkit.getConsoleSender(), key, values);
            }
        });
    }

    private void enforce(Punishment p, Map<String, String> values) {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!p.matches(player.getUniqueId(), address(player))) continue;
            switch (p.kind()) {
                case BAN, IPBAN -> {
                    if (p.active(now)) player.kick(settings.messages().render("screen." + p.kind().key() + "-issued", values));
                }
                case KICK -> player.kick(settings.messages().render("screen.kick", values));
                case MUTE -> {
                    if (!p.silent() && p.active(now)) tell(player, "muted-notice", values);
                }
                case WARN -> deliverWarnings(player);
            }
        }
    }

    public void discord(String action, Punishment p, String staff, String reason) {
        webhook.send(settings.webhook(), settings.messages(), action, p, staff, reason);
    }
}

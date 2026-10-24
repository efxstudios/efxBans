package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Engine;
import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Problem;
import com.efxstudios.bans.common.Punishment;
import com.efxstudios.bans.common.Store;
import com.efxstudios.bans.common.Webhook;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

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

    public record Online(UUID uuid, String name, String ip, int exemptions) {}

    private static final long REFRESH_TICKS = 600;

    private volatile Settings settings;
    private volatile Engine engine;
    private Webhook webhook;
    private Permissions permissions;
    private UpdateChecker updates;
    private final Map<UUID, Online> online = new ConcurrentHashMap<>();
    private final Map<UUID, Object> warningDeliveryInFlight = new ConcurrentHashMap<>();
    private final Scheduler scheduler = Scheduler.create(this);
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
            getLogger().severe("Could not load the configuration: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        permissions = new Permissions(this);
        registeredTopology = settings.topology();
        webhook = new Webhook(getLogger()::warning);
        updates = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(new Enforcement(this), this);

        CommandRegistration.install(this);
        openDatabase();
        for (Player p : Bukkit.getOnlinePlayers()) player(p, () -> track(p));

        global(() -> CommandTree.audit(this));
        updates.configure(settings.updates());
        getLogger().info("efxBans " + getDescription().getVersion() + " ready. Paper " + Platform.minecraftVersion() + "; "
                + settings.database().type() + "; changes to preset commands need a restart.");
    }

    private void openDatabase() {
        Store store = null;
        try {
            store = new Store(settings.database());
            engine = new Engine(store, settings.pollMillis(), this::changes,
                    e -> getLogger().severe("Database refresh failed; keeping the previous punishment cache: " + e.getClass().getSimpleName()));
        } catch (Exception e) {
            if (store != null) store.close();

            getLogger().severe("Could not open the database (" + e.getClass().getSimpleName() + "). Logins will be refused until it works. "
                    + "Fix the settings and restart the server.");
        }
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        scheduler.cancel();
        if (engine != null) engine.close();
        if (webhook != null) webhook.close();
    }

    public boolean healthy() {
        return engine != null && engine.healthy(settings.staleMillis());
    }

    Scheduler scheduler() { return scheduler; }

    public void global(Runnable action) { scheduler.global(action); }

    public void player(Player player, Runnable action) { scheduler.entity(player, action); }

    public void player(Player player, Runnable action, Runnable retired) { scheduler.entity(player, action, retired); }

    public void sender(CommandSender sender, Runnable action) { scheduler.sender(sender, action); }

    public void tell(CommandSender sender, String key, Map<String, String> values) {
        var message = settings.messages().render(key, values);
        sender(sender, () -> Platform.send(sender, message));
    }

    public void error(CommandSender sender, Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof Problem problem) {
            tell(sender, problem.key(), problem.values());
        } else if (cause instanceof IllegalArgumentException) {
            String fallback = settings.messages().text("texts.invalid-operation", "Invalid operation");
            tell(sender, "error", Map.of("error", Objects.requireNonNullElse(cause.getMessage(), fallback)));
        } else {
            tell(sender, "database-error", Map.of());
            getLogger().warning("Operation failed: " + cause.getClass().getSimpleName());
        }
    }

    public <T> void async(CommandSender sender, Engine.Operation<T> work, Consumer<T> complete) {
        if (engine == null) {
            tell(sender, "database-error", Map.of());
            return;
        }
        engine.call(work).whenComplete((result, error) -> sender(sender, () -> {
            try {
                if (error != null) error(sender, error);
                else complete.accept(result);
            } catch (Exception e) {
                error(sender, e);
            }
        }));
    }

    public void remember(Player player) {
        if (!player.isOnline()) return;
        Online identity = new Online(player.getUniqueId(), player.getName(), address(player), permissions.exemptions(player));
        online.put(identity.uuid(), identity);
        if (engine != null) engine.call(store -> {
            store.remember(identity.uuid(), identity.name(), identity.ip(), identity.exemptions());
            return null;
        });
    }

    public void track(Player player) {
        remember(player);
        scheduler.track(player, () -> remember(player), REFRESH_TICKS);
    }

    public static String address(Player p) {
        return p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
    }

    public void deliverWarnings(Player player) {
        Object delivery = new Object();
        if (engine == null || warningDeliveryInFlight.putIfAbsent(player.getUniqueId(), delivery) != null) return;
        UUID uuid = player.getUniqueId();
        engine.call(s -> s.pendingWarnings(uuid)).whenComplete((rows, error) -> {

            if (error != null) { warningDeliveryInFlight.remove(uuid, delivery); return; }
            player(player, () -> {
                try {
                    if (!player.isOnline()) return;
                    for (Punishment warning : rows) tell(player, "warned", settings.messages().values(warning));
                    if (!rows.isEmpty()) engine.call(s -> {
                        s.acknowledgeWarnings(uuid, rows.stream().map(Punishment::id).toList());
                        return null;
                    });
                } finally {
                    warningDeliveryInFlight.remove(uuid, delivery);
                }
            }, () -> warningDeliveryInFlight.remove(uuid, delivery));
        });
    }

    public void forget(Player player) {
        online.remove(player.getUniqueId());
        warningDeliveryInFlight.remove(player.getUniqueId());
    }

    public Optional<Punishment> punishment(Player p, Set<Kind> kinds) {
        Online identity = online.get(p.getUniqueId());
        return engine == null ? Optional.empty() : engine.cache.find(p.getUniqueId(), identity == null ? null : identity.ip(), kinds);
    }

    public synchronized void reload(CommandSender sender) {
        try {
            Settings next = Settings.load(this);
            boolean restart = !next.database().equals(settings.database())
                    || next.pollMillis() != settings.pollMillis()
                    || !registeredTopology.equals(next.topology());

            settings = new Settings(settings.database(), settings.pollMillis(), next.staleMillis(), next.publicBroadcast(), next.defaultMax(),
                    next.limits(), next.muteCommands(), next.consolePermissions(), next.presets(), next.messages(),
                    next.webhook(), next.updates());
            updates.configure(settings.updates());
            Bukkit.getOnlinePlayers().forEach(p -> player(p, p::updateCommands));
            tell(sender, restart ? "reload-restart" : "reload", Map.of());
            if (restart) getLogger().warning("The database, poll interval or preset command names/aliases/enabled state changed: a full server restart is needed.");
        } catch (Exception e) {
            error(sender, new IllegalArgumentException("Settings were not applied; the previous settings stay active: " + e.getMessage()));
        }
    }

    private void changes(List<Store.Change> changes) {
        global(() -> {
            for (Store.Change change : changes) {
                Punishment p = change.punishment();
                if (change.action().equals("IMPORT")) {

                    if ((p.kind() == Kind.BAN || p.kind() == Kind.IPBAN) && p.active(System.currentTimeMillis()))
                        for (Player player : Bukkit.getOnlinePlayers())
                            player(player, () -> {
                                if (p.active(System.currentTimeMillis()) && p.matches(player.getUniqueId(), address(player)))
                                    Platform.kick(player, settings.messages().render("screen." + p.kind().key(), settings.messages().values(p)));
                            });
                    continue;
                }
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
                    player(recipient, () -> {
                        if (settings.publicBroadcast() || permissions.has(recipient, "efxbans.notify")) tell(recipient, key, values);
                    });
                tell(Bukkit.getConsoleSender(), key, values);
            }
        });
    }

    private void enforce(Punishment p, Map<String, String> values) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player(player, () -> {
                if (!p.matches(player.getUniqueId(), address(player))) return;
                long now = System.currentTimeMillis();
                switch (p.kind()) {
                case BAN, IPBAN -> {
                    if (p.active(now)) Platform.kick(player, settings.messages().render("screen." + p.kind().key() + "-issued", values));
                }
                case KICK -> Platform.kick(player, settings.messages().render("screen.kick", values));
                case MUTE -> {
                    if (!p.silent() && p.active(now)) tell(player, "muted-notice", values);
                }
                case WARN -> deliverWarnings(player);
                }
            });
        }
    }

    public void discord(String action, Punishment p, String staff, String reason) {
        webhook.send(settings.webhook(), settings.messages(), action, p, staff, reason);
    }
}

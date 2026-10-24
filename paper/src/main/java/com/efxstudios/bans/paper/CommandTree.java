package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Addresses;
import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Problem;
import com.efxstudios.bans.common.Punishment;
import com.efxstudios.bans.common.Store;
import com.efxstudios.bans.common.Times;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * All Brigadier commands. Permission checks happen twice: in {@code requires} (hides the command) and again
 * when the command runs, because settings and permissions can change between tab completion and execution.
 */
public final class CommandTree {
    /** Built-in commands in help order; usage text comes from the language file (usages.*). */
    private static final List<String> COMMANDS = List.of("ban", "ipban", "mute", "kick", "warn", "unban", "unmute", "undo",
            "history", "punishment", "lookup", "banlist", "mutelist", "alts");
    /** Built-in aliases, kept for servers upgrading from releases where these were the command names. */
    private static final Map<String, List<String>> ALIASES = Map.of("lookup", List.of("sorgu"));
    private static final List<String> DURATION_SUGGESTIONS = List.of("30m", "2h", "1d", "7d", "30d");

    private final BansPlugin plugin;
    private final Permissions permissions;

    public CommandTree(BansPlugin plugin) {
        this.plugin = plugin;
        this.permissions = plugin.permissions();
    }

    // ---------------------------------------------------------------- registration

    public void register(Commands registrar) {
        for (Kind kind : Kind.values()) registerPunishment(registrar, kind);
        Map<String, Set<Kind>> removable = Map.of("unban", Set.of(Kind.BAN, Kind.IPBAN), "unmute", Set.of(Kind.MUTE));
        for (String label : List.of("unban", "unmute", "undo")) {
            var node = Commands.literal(label)
                    .requires(s -> permissions.has(s.getSender(), "efxbans." + label))
                    .executes(c -> usage(c, label))
                    .then(target(removable.get(label)).executes(c -> run(c, () -> remove(c, label))));
            register(registrar, node.build(), List.of());
        }
        for (String label : List.of("banlist", "mutelist")) {
            var node = Commands.literal(label)
                    .requires(s -> permissions.has(s.getSender(), "efxbans." + label))
                    .executes(c -> run(c, () -> list(sender(c), label, 1)))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 100_000_000))
                            .executes(c -> run(c, () -> list(sender(c), label, page(c)))));
            register(registrar, node.build(), List.of());
        }
        var inquiry = Commands.literal("lookup")
                .requires(s -> permissions.has(s.getSender(), "efxbans.lookup"))
                .executes(c -> usage(c, "lookup"))
                .then(target().executes(c -> run(c, () -> inquiry(sender(c), targetValue(c), 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100_000_000))
                                .executes(c -> run(c, () -> inquiry(sender(c), targetValue(c), page(c))))));
        register(registrar, inquiry.build(), ALIASES.get("lookup"));
        var history = Commands.literal("history")
                .requires(s -> permissions.has(s.getSender(), "efxbans.history"))
                .executes(c -> usage(c, "history"))
                .then(target().executes(c -> run(c, () -> history(c, 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100_000))
                                .executes(c -> run(c, () -> history(c, page(c))))));
        register(registrar, history.build(), List.of());
        var detail = Commands.literal("punishment")
                .requires(s -> permissions.has(s.getSender(), "efxbans.history"))
                .executes(c -> usage(c, "punishment"))
                .then(target().executes(c -> run(c, () -> detail(c))));
        register(registrar, detail.build(), List.of());
        var alts = Commands.literal("alts")
                .requires(s -> permissions.has(s.getSender(), "efxbans.alts"))
                .executes(c -> usage(c, "alts"))
                .then(target().executes(c -> run(c, () -> alts(c))));
        register(registrar, alts.build(), List.of());
        registerAdmin(registrar);
        // Trees keep only the registered label; values and permissions are read from the current config.
        for (Settings.Preset preset : plugin.settings().presets().values()) {
            if (!preset.enabled()) continue;
            String label = preset.command();
            var node = Commands.literal(label)
                    .requires(s -> canPreset(s.getSender(), label))
                    .executes(c -> usage(c, label))
                    .then(target().executes(c -> run(c, () -> preset(c, label))));
            register(registrar, node.build(), preset.aliases());
        }
    }

    /** /ban, /ipban, /mute, /kick, /warn with an optional -s (silent) flag before the target. */
    private void registerPunishment(Commands registrar, Kind kind) {
        String label = kind.key();
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(label)
                .requires(s -> permissions.canIssue(s.getSender(), kind))
                .executes(c -> usage(c, label));
        node.then(target().executes(c -> usage(c, label))
                .then(details(kind).executes(c -> run(c, () -> issue(c, kind, false)))));
        if (kind != Kind.WARN) {
            node.then(Commands.literal("-s")
                    .requires(s -> permissions.has(s.getSender(), "efxbans." + label + ".silent"))
                    .executes(c -> usage(c, label))
                    .then(target().executes(c -> usage(c, label))
                            .then(details(kind).executes(c -> run(c, () -> issue(c, kind, true))))));
        }
        register(registrar, node.build(), List.of());
    }

    private void registerAdmin(Commands registrar) {
        var admin = Commands.literal("efxbans")
                .requires(s -> canAdmin(s.getSender()))
                .executes(this::help);
        admin.then(Commands.literal("help").executes(this::help));
        admin.then(Commands.literal("reload")
                .requires(s -> permissions.has(s.getSender(), "efxbans.reload"))
                .executes(c -> run(c, () -> {
                    permissions.require(sender(c), "efxbans.reload");
                    plugin.reload(sender(c));
                })));
        admin.then(Commands.literal("status")
                .requires(s -> permissions.has(s.getSender(), "efxbans.admin"))
                .executes(c -> run(c, () -> status(sender(c)))));
        register(registrar, admin.build(), List.of());
    }

    private void register(Commands registrar, LiteralCommandNode<CommandSourceStack> node, List<String> aliases) {
        registerRoot(registrar, node);
        for (String alias : aliases) {
            // Paper's alias registration keeps aliases that are already taken. Register each alias as a full
            // literal instead, so it gets the same replacement and permission rules as the command itself.
            var copy = Commands.literal(alias).requires(node.getRequirement()).executes(node.getCommand());
            node.getChildren().forEach(copy::then);
            registerRoot(registrar, copy.build());
        }
    }

    private void registerRoot(Commands registrar, LiteralCommandNode<CommandSourceStack> node) {
        String label = node.getLiteral();
        Command existing = Bukkit.getCommandMap().getCommand(label);
        boolean own = existing instanceof PluginIdentifiableCommand identifiable && identifiable.getPlugin() == plugin;
        if (!own && (existing != null || registrar.getDispatcher().getRoot().getChild(label) != null))
            plugin.getLogger().warning("/" + label + " was taken over by efxBans. The other plugin's namespaced command still works.");
        registrar.register(node, "efxBans", List.of());
    }

    /** After startup: warns if another plugin replaced one of our labels later on. */
    public static void audit(BansPlugin plugin) {
        Set<String> labels = new TreeSet<>(COMMANDS);
        ALIASES.values().forEach(labels::addAll);
        labels.add("efxbans");
        plugin.settings().presets().values().stream().filter(Settings.Preset::enabled).forEach(p -> {
            labels.add(p.command());
            labels.addAll(p.aliases());
        });
        for (String label : labels) {
            Command command = Bukkit.getCommandMap().getCommand(label);
            if (command instanceof PluginIdentifiableCommand owner && owner.getPlugin() != plugin)
                plugin.getLogger().warning("/" + label + " was replaced after efxBans registered it, by " + owner.getPlugin().getName() + ". /efxbans:"
                        + label + " still works; check the other plugin's command settings.");
        }
    }

    // ---------------------------------------------------------------- arguments

    /** Player name, UUID, IP or #ID. Suggests online players; any other valid name can still be typed. */
    private RequiredArgumentBuilder<CommandSourceStack, String> target() {
        return target(null);
    }

    /**
     * With {@code punished} set, suggests the players who currently have such a punishment (online or not)
     * instead of everyone online: /unban lists banned players, /unmute muted ones. The names come from the
     * in-memory punishment cache, so a keystroke never runs SQL.
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> target(Set<Kind> punished) {
        Supplier<String> error = () -> messages().text("texts.target-required", "A player, IP or #ID is required.");
        return Commands.argument("target", new TargetArgument(error)).suggests((c, b) -> {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (punished == null) plugin.online().values().forEach(player -> names.add(player.name()));
            else if (plugin.engine() != null) names.addAll(plugin.engine().cache.names(punished));
            String remaining = b.getRemainingLowerCase();
            names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(remaining)).limit(80).forEach(b::suggest);
            return b.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> details(Kind kind) {
        return Commands.argument("details", StringArgumentType.greedyString()).suggests((c, b) -> durationSuggestions(c, b, kind));
    }

    /** Only durations the sender may actually use are suggested. */
    private CompletableFuture<Suggestions> durationSuggestions(CommandContext<CommandSourceStack> c, SuggestionsBuilder b, Kind kind) {
        if (!kind.timed() || !permissions.has(sender(c), "efxbans." + kind.key() + ".temporary")) return b.buildFuture();
        for (String time : DURATION_SUGGESTIONS) {
            try {
                permissions.issue(sender(c), kind, Times.parse(time));
                if (time.startsWith(b.getRemainingLowerCase())) b.suggest(time);
            } catch (IllegalArgumentException ignored) {
                // Above the sender's maximum.
            }
        }
        return b.buildFuture();
    }

    // ---------------------------------------------------------------- issuing

    /** "[duration] reason": a first word that starts with a digit must be a valid duration. */
    private void issue(CommandContext<CommandSourceStack> c, Kind kind, boolean silent) {
        String details = StringArgumentType.getString(c, "details").strip();
        long duration = 0;
        String reason = details;
        String[] parts = details.split("\\s+", 2);
        if (kind.timed() && Times.looksLikeDuration(parts[0])) {
            duration = Times.parse(parts[0]);
            if (parts.length < 2 || parts[1].isBlank()) throw new Problem("reason-required", Map.of("usage", usageText(kind.key())));
            reason = parts[1];
        }
        issue(sender(c), targetValue(c), kind, duration, reason, null, silent);
    }

    private void preset(CommandContext<CommandSourceStack> c, String label) {
        Settings.Preset preset = plugin.settings().presets().get(label);
        if (preset == null || !preset.enabled()) throw new Problem("preset-disabled");
        permissions.require(sender(c), preset.permission());
        issue(sender(c), targetValue(c), preset.kind(), preset.duration(), preset.reason(), label, false);
    }

    private void issue(CommandSender sender, String input, Kind kind, long duration, String reason, String presetLabel, boolean silent) {
        permissions.issue(sender, kind, duration);
        validateReason(reason);
        if (silent) permissions.require(sender, "efxbans." + kind.key() + ".silent");
        if (kind == Kind.KICK) {
            var current = plugin.online().values().stream()
                    .filter(p -> p.name().equalsIgnoreCase(input) || p.uuid().toString().equalsIgnoreCase(input))
                    .findFirst();
            if (current.isEmpty()) throw new Problem("player-offline", Map.of("player", input));
            var p = current.get();
            persist(sender, kind, duration, reason, p.uuid(), p.name(), p.ip(), p.exemptions(), presetLabel, silent);
            return;
        }
        String ip = Addresses.parse(input);
        if (ip != null) {
            if (kind != Kind.IPBAN) throw new Problem("account-required");
            permissions.require(sender, "efxbans.ipban.address");
            persist(sender, kind, duration, reason, null, messages().text("texts.ip-target", "IP target"), ip, 0, presetLabel, silent);
            return;
        }
        resolve(sender, input, profile -> {
            try {
                permissions.issue(sender, kind, duration);
                BansPlugin.Online current = plugin.online().get(profile.uuid());
                int exemptions = current != null ? current.exemptions() : profile.exemptions();
                Player live = Bukkit.getPlayer(profile.uuid());
                if (live != null) exemptions = permissions.exemptions(live);
                String address = current != null ? current.ip() : profile.ip();
                persist(sender, kind, duration, reason, profile.uuid(), profile.name(), address, exemptions, presetLabel, silent);
            } catch (Exception e) {
                plugin.error(sender, e);
            }
        });
    }

    /** Last checks on the main thread, then the database write. Permissions are re-checked after async lookups. */
    private void persist(CommandSender sender, Kind kind, long duration, String reason, UUID uuid, String name, String ip, int exemptions,
                         String presetLabel, boolean silent) {
        permissions.issue(sender, kind, duration);
        if (silent) permissions.require(sender, "efxbans." + kind.key() + ".silent");
        if (kind == Kind.KICK && !plugin.online().containsKey(uuid)) throw new Problem("player-offline", Map.of("player", name));
        if (presetLabel != null) {
            var preset = plugin.settings().presets().get(presetLabel);
            if (preset == null || !preset.enabled()) throw new Problem("preset-disabled");
            permissions.require(sender, preset.permission());
        }
        if ((exemptions & kind.bit()) != 0 && !permissions.has(sender, "efxbans.bypass.exempt")) throw new Problem("exempt");
        String actor = actor(sender);
        String staff = sender.getName();
        plugin.async(sender, s -> {
            if (uuid != null && s.profile(uuid.toString()).isEmpty()) s.remember(uuid, name, null, exemptions);
            return s.issue(kind, uuid, name, ip, actor, staff, reason, duration, silent);
        }, p -> {
            plugin.tell(sender, silent ? "confirmation.silent" : "confirmation." + kind.key(), messages().values(p));
            plugin.discord(kind.key(), p, staff, reason);
        });
    }

    // ---------------------------------------------------------------- removal

    private void remove(CommandContext<CommandSourceStack> c, String command) {
        CommandSender sender = sender(c);
        permissions.require(sender, "efxbans." + command);
        String input = targetValue(c);
        Long id = input.startsWith("#") ? id(input) : null;
        boolean undo = command.equals("undo");
        if (undo && id == null) throw new Problem("id-required");
        String ip = Addresses.parse(input);
        if (ip != null && command.equals("unmute")) throw new Problem("account-required");
        if (ip != null) permissions.require(sender, "efxbans.unban.address");
        Set<Kind> kinds = undo ? EnumSet.allOf(Kind.class)
                : command.equals("unmute") ? Set.of(Kind.MUTE)
                : ip != null ? Set.of(Kind.IPBAN)
                : Set.of(Kind.BAN, Kind.IPBAN);
        if (id != null || ip != null) remove(sender, id, null, ip, kinds, undo, command);
        else resolve(sender, input, p -> remove(sender, null, p.uuid(), null, kinds, undo, command));
    }

    /** Removal takes no reason; the stored close reason stays empty. */
    private void remove(CommandSender sender, Long id, UUID uuid, String ip, Set<Kind> kinds, boolean undo, String command) {
        permissions.require(sender, "efxbans." + command);
        String actor = actor(sender);
        String staff = sender.getName();
        boolean addressAccess = permissions.has(sender, "efxbans.unban.address");
        plugin.async(sender, s -> s.remove(id, uuid, ip, kinds, undo, actor, staff, "", addressAccess), rows -> {
            if (rows.isEmpty()) plugin.tell(sender, "not-found", Map.of());
            for (Punishment p : rows) {
                var values = messages().values(p);
                values.put("staff", staff);
                plugin.tell(sender, "confirmation." + command, values);
                plugin.discord(undo ? "undo" : "remove", p, staff, p.reason());
            }
        });
    }

    // ---------------------------------------------------------------- lookups

    private void history(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = sender(c);
        permissions.require(sender, "efxbans.history");
        resolve(sender, targetValue(c), profile -> plugin.async(sender, s -> s.history(profile.uuid(), page), rows -> {
            plugin.tell(sender, "history-header", Map.of("player", profile.name(), "page", String.valueOf(page)));
            if (rows.isEmpty()) plugin.tell(sender, "not-found", Map.of());
            for (Punishment p : rows) plugin.tell(sender, "history-entry", messages().values(p));
        }));
    }

    private void detail(CommandContext<CommandSourceStack> c) {
        CommandSender sender = sender(c);
        permissions.require(sender, "efxbans.history");
        long id = id(targetValue(c));
        plugin.async(sender, s -> s.get(id), result -> {
            if (result.isEmpty()) {
                plugin.tell(sender, "not-found", Map.of());
                return;
            }
            var p = result.get();
            plugin.tell(sender, "detail", messages().values(p));
            if (permissions.has(sender, "efxbans.ip.view") && p.ip() != null) plugin.tell(sender, "ip-detail", Map.of("ip", p.ip()));
            plugin.async(sender, s -> s.audit(id), rows -> {
                for (Store.Change row : rows)
                    plugin.tell(sender, "audit-entry", Map.of("action", row.action(), "staff", row.actorName(), "reason", row.reason(),
                            "created", messages().date(row.at()), "id", String.valueOf(id)));
            });
        });
    }

    private void alts(CommandContext<CommandSourceStack> c) {
        CommandSender sender = sender(c);
        permissions.require(sender, "efxbans.alts");
        resolve(sender, targetValue(c), profile -> plugin.async(sender, s -> s.alts(profile.uuid()), rows -> {
            plugin.tell(sender, "alts-header", Map.of("player", profile.name()));
            if (rows.isEmpty()) plugin.tell(sender, "not-found", Map.of());
            for (var row : rows) plugin.tell(sender, "alts-entry", Map.of("player", row.name()));
        }));
    }

    /**
     * Name or UUID to account: online players first, then known profiles, then (for accounts that never
     * joined) the offline-mode UUID or Paper's authenticated profile lookup.
     */
    private void resolve(CommandSender sender, String input, Consumer<Store.Profile> done) {
        if (!input.matches("[A-Za-z0-9_]{1,16}|[0-9a-fA-F-]{36}")) throw new Problem("account-required");
        for (var p : plugin.online().values()) {
            if (p.name().equalsIgnoreCase(input) || p.uuid().toString().equalsIgnoreCase(input)) {
                done.accept(new Store.Profile(p.uuid(), p.name(), p.ip(), p.exemptions()));
                return;
            }
        }
        plugin.async(sender, s -> s.profile(input), known -> {
            if (known.isPresent()) {
                done.accept(known.get());
                return;
            }
            try {
                UUID explicit = UUID.fromString(input);
                done.accept(new Store.Profile(explicit, input, null, 0));
                return;
            } catch (IllegalArgumentException ignored) {
                // A name, continue below.
            }
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
            if (cached != null && cached.getName() != null) {
                done.accept(new Store.Profile(cached.getUniqueId(), cached.getName(), null, 0));
                return;
            }
            if (!Bukkit.getOnlineMode()) {
                UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + input).getBytes(StandardCharsets.UTF_8));
                done.accept(new Store.Profile(offline, input, null, 0));
                return;
            }
            Bukkit.createPlayerProfile(input).update().orTimeout(6, TimeUnit.SECONDS).whenComplete((profile, error) -> plugin.main(() -> {
                if (error != null || profile.getUniqueId() == null) {
                    plugin.tell(sender, "unknown-player", Map.of("player", input));
                    return;
                }
                done.accept(new Store.Profile(profile.getUniqueId(), Objects.requireNonNullElse(profile.getName(), input), null, 0));
            }));
        });
    }

    /** /banlist and /mutelist with clickable entries and page buttons. */
    private void list(CommandSender sender, String label, int page) {
        permissions.require(sender, "efxbans." + label);
        Set<Kind> kinds = label.equals("banlist") ? Set.of(Kind.BAN, Kind.IPBAN) : Set.of(Kind.MUTE);
        plugin.async(sender, s -> s.activePage(kinds, page), result -> {
            if (!permissions.has(sender, "efxbans." + label)) return;
            Messages messages = messages();
            plugin.tell(sender, "lists." + label, Map.of("page", String.valueOf(result.number()), "pages", String.valueOf(result.pages()),
                    "total", String.valueOf(result.total())));
            if (result.entries().isEmpty()) plugin.tell(sender, "lists.empty", Map.of());
            boolean canInspect = permissions.has(sender, "efxbans.lookup");
            for (var p : result.entries()) {
                var values = messages.values(p);
                Component line = messages.render("lists.entry", values);
                if (canInspect) line = line.hoverEvent(messages.render("lists.hover", values)).clickEvent(ClickEvent.runCommand("/efxbans:lookup #" + p.id()));
                sender.sendMessage(line);
            }
            sender.sendMessage(navigation(result, "/efxbans:" + label + " "));
        });
    }

    /** /lookup (alias /sorgu): active bans, IP bans and mutes for a player, an IP or an #ID. */
    private void inquiry(CommandSender sender, String input, int page) {
        permissions.require(sender, "efxbans.lookup");
        Long id = input.startsWith("#") ? id(input) : null;
        String ip = Addresses.parse(input);
        if (id != null || ip != null) inquiry(sender, input, id, null, ip, page);
        else resolve(sender, input, p -> inquiry(sender, p.name(), null, p.uuid(), null, page));
    }

    private void inquiry(CommandSender sender, String input, Long id, UUID uuid, String ip, int page) {
        plugin.async(sender, s -> s.inquiry(id, uuid, ip, page), result -> {
            if (!permissions.has(sender, "efxbans.lookup")) return;
            plugin.tell(sender, "inquiry.header", Map.of("target", input, "total", String.valueOf(result.total())));
            if (result.entries().isEmpty()) {
                plugin.tell(sender, "inquiry.empty", Map.of());
                return;
            }
            for (var p : result.entries()) plugin.tell(sender, "inquiry.entry", messages().values(p));
            sender.sendMessage(navigation(result, "/efxbans:lookup " + input + " "));
        });
    }

    private Component navigation(Store.Page result, String command) {
        Messages messages = messages();
        Component nav = Component.empty();
        if (result.number() > 1)
            nav = nav.append(messages.render("lists.previous", Map.of()).clickEvent(ClickEvent.runCommand(command + (result.number() - 1))));
        nav = nav.append(messages.render("lists.page", Map.of("page", String.valueOf(result.number()), "pages", String.valueOf(result.pages()))));
        if (result.number() < result.pages())
            nav = nav.append(messages.render("lists.next", Map.of()).clickEvent(ClickEvent.runCommand(command + (result.number() + 1))));
        return nav;
    }

    // ---------------------------------------------------------------- admin and help

    private void status(CommandSender sender) {
        permissions.require(sender, "efxbans.admin");
        Messages messages = messages();
        String health = plugin.healthy() ? messages.text("texts.health-ready", "Ready") : messages.text("texts.health-unavailable", "Database unreachable");
        plugin.tell(sender, "health", Map.of("health", health, "version", Bukkit.getMinecraftVersion(), "plugin", plugin.getPluginMeta().getVersion(),
                "database", plugin.settings().database().type()));
    }

    private boolean canPreset(CommandSender sender, String label) {
        var p = plugin.settings().presets().get(label);
        if (p == null || !p.enabled() || !permissions.has(sender, p.permission())) return false;
        try {
            permissions.issue(sender, p.kind(), p.duration());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean canAdmin(CommandSender sender) {
        return List.of("reload", "admin", "history", "unban", "unmute", "undo", "alts", "banlist", "mutelist", "lookup").stream()
                .anyMatch(p -> permissions.has(sender, "efxbans." + p))
                || Arrays.stream(Kind.values()).anyMatch(k -> permissions.canIssue(sender, k));
    }

    /** Lists only the commands the sender may use. */
    private int help(CommandContext<CommandSourceStack> c) {
        CommandSender sender = sender(c);
        plugin.tell(sender, "help-header", Map.of());
        for (String label : COMMANDS) {
            boolean allowed = Arrays.stream(Kind.values()).anyMatch(k -> k.key().equals(label))
                    ? permissions.canIssue(sender, Kind.valueOf(label.toUpperCase(Locale.ROOT)))
                    : permissions.has(sender, "efxbans." + (label.equals("punishment") ? "history" : label));
            if (allowed) plugin.tell(sender, "help-entry", Map.of("usage", usageText(label)));
        }
        for (var p : plugin.settings().presets().values())
            if (canPreset(sender, p.command())) plugin.tell(sender, "help-entry", Map.of("usage", usageText(p.command())));
        if (permissions.has(sender, "efxbans.reload")) plugin.tell(sender, "help-entry", Map.of("usage", messages().text("usages.reload", "/efxbans reload")));
        return 1;
    }

    private int usage(CommandContext<CommandSourceStack> c, String label) {
        plugin.tell(sender(c), "usage", Map.of("usage", usageText(label)));
        return 0;
    }

    /** Built-in commands have their own line; presets share usages.preset. Inserted as literal text. */
    private String usageText(String label) {
        if (COMMANDS.contains(label)) return messages().text("usages." + label, "/" + label);
        return messages().text("usages.preset", "/{command} <player>").replace("{command}", label);
    }

    // ---------------------------------------------------------------- helpers

    private int run(CommandContext<CommandSourceStack> c, Runnable work) {
        try {
            work.run();
            return 1;
        } catch (Exception e) {
            plugin.error(sender(c), e);
            return 0;
        }
    }

    private Messages messages() {
        return plugin.settings().messages();
    }

    private static void validateReason(String reason) {
        if (reason.isBlank() || reason.length() > 1000) throw new Problem("invalid-reason");
    }

    private static long id(String value) {
        try {
            long id = Long.parseLong(value.startsWith("#") ? value.substring(1) : value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Fall through to the user error.
        }
        throw new Problem("id-required");
    }

    private static int page(CommandContext<CommandSourceStack> c) {
        return IntegerArgumentType.getInteger(c, "page");
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> c) {
        return c.getSource().getSender();
    }

    private static String targetValue(CommandContext<CommandSourceStack> c) {
        return c.getArgument("target", String.class);
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    }

    /** One token up to the next space. StringArgumentType.word() would reject the ':' of IPv6 and the '#' of IDs. */
    public static final class TargetArgument implements CustomArgumentType<String, String> {
        private final Supplier<String> error;

        public TargetArgument(Supplier<String> error) {
            this.error = error;
        }

        @Override
        public ArgumentType<String> getNativeType() {
            return StringArgumentType.greedyString();
        }

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
            String value = reader.getString().substring(start, reader.getCursor());
            if (value.isEmpty() || value.length() > 64)
                throw new SimpleCommandExceptionType(new LiteralMessage(error.get())).createWithContext(reader);
            return value;
        }

        @Override
        public Collection<String> getExamples() {
            return List.of("efx1st", "#123", "127.0.0.1");
        }
    }
}

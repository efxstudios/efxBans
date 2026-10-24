package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Version;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checks Modrinth's published stable versions for this server's Minecraft version and loader.
 * The anonymous request includes no server/player data. Can be disabled with update-checker.enabled.
 */
public final class UpdateChecker {
    public static final String PERMISSION = "efxbans.update.notify";
    public static final String PROJECT_URL = "https://modrinth.com/project/efxbans/";
    private static final URI VERSIONS_URL = URI.create("https://api.modrinth.com/v2/project/efxbans/version?include_changelog=false");

    /** The download page is fixed, never taken from the remote response. */
    record Release(Version version, String url) {}

    private final BansPlugin plugin;
    private final Version current;
    private final String gameVersion;
    private final String loader;
    private final AtomicLong generation = new AtomicLong();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Release available;
    private ScheduledTask task;
    private volatile Settings.Updates active = Settings.Updates.DISABLED;

    public UpdateChecker(BansPlugin plugin) {
        this.plugin = plugin;
        this.current = parse(plugin.getPluginMeta().getVersion()).orElse(null);
        this.gameVersion = Bukkit.getMinecraftVersion();
        this.loader = folia() ? "folia" : "paper";
    }

    /** (Re)starts or stops the periodic check. Serialized by config loading. */
    public synchronized void configure(Settings.Updates settings) {
        if (settings.equals(active) && (task != null) == settings.enabled()) return;
        stop();
        active = settings;
        if (!settings.enabled()) return;
        if (current == null) {
            plugin.getLogger().warning("Update checker disabled: the build has no valid version.");
            return;
        }
        long period = TimeUnit.HOURS.toSeconds(settings.intervalHours());
        long revision = generation.get();
        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> check(revision), 10, period, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        generation.incrementAndGet();
        if (task != null) task.cancel();
        task = null;
        available = null;
        active = Settings.Updates.DISABLED;
    }

    /** Join notification for staff, if a newer release was found and notify-staff is on. */
    public void notify(Player player) {
        Release release = available;
        if (release == null || !active.enabled() || !active.notifyStaff() || !plugin.permissions().has(player, PERMISSION)) return;
        var messages = plugin.settings().messages();
        player.sendMessage(messages.render("update.available", Map.of("current", current.toString(), "latest", release.version().toString()))
                .clickEvent(ClickEvent.openUrl(release.url())));
    }

    private void check(long revision) {
        HttpRequest request = HttpRequest.newBuilder(VERSIONS_URL)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "efxBans/" + current + " (" + PROJECT_URL + ")")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // 404: the project has not been published yet.
            if (response.statusCode() == 404) return;
            if (response.statusCode() != 200) {
                plugin.getLogger().fine("Update check: Modrinth answered HTTP " + response.statusCode());
                return;
            }
            publish(newerRelease(response.body(), current, gameVersion, loader), revision);
        } catch (IOException e) {
            plugin.getLogger().info("Update check failed (" + e.getClass().getSimpleName() + "). It will be retried later.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            plugin.getLogger().info("Could not read the Modrinth response: " + e.getClass().getSimpleName());
        }
    }

    private synchronized void publish(Optional<Release> newer, long revision) {
        if (revision != generation.get() || !active.enabled() || !plugin.isEnabled()) return;
        Release previous = available;
        available = newer.orElse(null);
        if (newer.isEmpty() || (previous != null && previous.version().equals(newer.get().version()))) return;
        Release release = newer.get();
        plugin.getLogger().warning("A new efxBans version is available: " + release.version() + " (installed: " + current + "). Download: " + release.url());
        plugin.global(() -> Bukkit.getOnlinePlayers().forEach(player -> plugin.player(player, () -> notify(player))));
    }

    /** Selects the highest compatible stable version, regardless of response order. */
    static Optional<Release> newerRelease(String json, Version current, String gameVersion, String loader) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return Optional.empty();
        Version latest = null;
        for (JsonElement entry : root.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject release = entry.getAsJsonObject();
            if (!"release".equals(string(release, "version_type")) || !"listed".equals(string(release, "status"))) continue;
            if (!contains(release.get("game_versions"), gameVersion) || !contains(release.get("loaders"), loader)) continue;
            Optional<Version> version = parse(string(release, "version_number"));
            if (version.isEmpty() || version.get().isPreRelease() || !version.get().isNewerThan(current)) continue;
            if (latest == null || version.get().isNewerThan(latest)) latest = version.get();
        }
        return latest == null ? Optional.empty() : Optional.of(new Release(latest, PROJECT_URL));
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static boolean contains(JsonElement value, String expected) {
        if (value == null || !value.isJsonArray()) return false;
        for (JsonElement item : value.getAsJsonArray())
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && expected.equals(item.getAsString())) return true;
        return false;
    }

    private static Optional<Version> parse(String text) {
        try {
            return Optional.of(Version.parse(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean folia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, UpdateChecker.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

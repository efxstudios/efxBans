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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Compares the running version with the latest GitHub release. Read-only: one anonymous GET request to the
 * GitHub API, no server or player data is sent. Can be turned off with update-checker.enabled.
 */
public final class UpdateChecker {
    public static final String PERMISSION = "efxbans.update.notify";
    private static final String REPOSITORY_PATTERN = "[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}";

    /** A newer release; the URL is built from the configured repository, never taken from the response. */
    record Release(Version version, String url) {}

    private final BansPlugin plugin;
    private final String repository;
    private final Version current;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Release available;
    private ScheduledTask task;
    private volatile Settings.Updates active = Settings.Updates.DISABLED;

    public UpdateChecker(BansPlugin plugin) {
        this.plugin = plugin;
        this.repository = repository(plugin);
        this.current = parse(plugin.getPluginMeta().getVersion()).orElse(null);
    }

    /** (Re)starts or stops the periodic check. Serialized by config loading. */
    public void configure(Settings.Updates settings) {
        if (settings.equals(active) && (task != null) == settings.enabled()) return;
        stop();
        active = settings;
        if (!settings.enabled()) return;
        if (repository == null || current == null) {
            plugin.getLogger().warning("Update checker disabled: the build has no valid version or GitHub repository.");
            return;
        }
        long period = TimeUnit.HOURS.toSeconds(settings.intervalHours());
        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> check(), 10, period, TimeUnit.SECONDS);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        available = null;
    }

    /** Join notification for staff, if a newer release was found and notify-staff is on. */
    public void notify(Player player) {
        Release release = available;
        if (release == null || !active.notifyStaff() || !plugin.permissions().has(player, PERMISSION)) return;
        var messages = plugin.settings().messages();
        player.sendMessage(messages.render("update.available", Map.of("current", current.toString(), "latest", release.version().toString()))
                .clickEvent(ClickEvent.openUrl(release.url())));
    }

    private void check() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "efxBans/" + current)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // 404: no published release yet, or the repository is private.
            if (response.statusCode() == 404) return;
            if (response.statusCode() != 200) {
                plugin.getLogger().fine("Update check: GitHub answered HTTP " + response.statusCode());
                return;
            }
            Optional<Release> newer = newerRelease(response.body(), current, repository);
            Release previous = available;
            available = newer.orElse(null);
            if (newer.isEmpty() || (previous != null && previous.version().equals(newer.get().version()))) return;
            Release release = newer.get();
            plugin.getLogger().warning("A new efxBans version is available: " + release.version() + " (installed: " + current + "). Download: " + release.url());
            plugin.global(() -> Bukkit.getOnlinePlayers().forEach(player -> plugin.player(player, () -> notify(player))));
        } catch (IOException e) {
            plugin.getLogger().info("Update check failed (" + e.getClass().getSimpleName() + "). It will be retried later.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            plugin.getLogger().info("Could not read the GitHub response: " + e.getClass().getSimpleName());
        }
    }

    /** Parses a releases/latest response. Drafts and pre-releases are ignored. */
    static Optional<Release> newerRelease(String json, Version current, String repository) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return Optional.empty();
        JsonObject release = root.getAsJsonObject();
        if (flag(release, "draft") || flag(release, "prerelease")) return Optional.empty();
        JsonElement tag = release.get("tag_name");
        if (tag == null || !tag.isJsonPrimitive()) return Optional.empty();
        Optional<Version> version = parse(tag.getAsString());
        if (version.isEmpty() || !version.get().isNewerThan(current)) return Optional.empty();
        return Optional.of(new Release(version.get(), "https://github.com/" + repository + "/releases/latest"));
    }

    private static boolean flag(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static Optional<Version> parse(String text) {
        try {
            return Optional.of(Version.parse(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** owner/name from the build, see github.repository in pom.xml. */
    private static String repository(BansPlugin plugin) {
        try (InputStream stream = plugin.getResource("efxbans-build.properties")) {
            if (stream == null) return null;
            Properties properties = new Properties();
            properties.load(stream);
            String value = properties.getProperty("repository", "").strip();
            return value.matches(REPOSITORY_PATTERN) ? value : null;
        } catch (IOException e) {
            return null;
        }
    }
}

package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Problem;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.Map;

public final class Permissions {
    private final BansPlugin plugin;

    public Permissions(BansPlugin plugin) {
        this.plugin = plugin;
    }

    /** Console and RCON get no implicit bypass: they also need permissions.console-allowed to list the node. */
    public boolean has(CommandSender sender, String permission) {
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            boolean allowed = plugin.settings().consolePermissions().stream().anyMatch(p -> p.equals("*") || p.equals(permission)
                    || p.endsWith(".*") && permission.startsWith(p.substring(0, p.length() - 1)));
            if (!allowed) return false;
        }
        return sender.hasPermission(permission);
    }

    public void require(CommandSender sender, String permission) {
        if (!has(sender, permission)) throw new Problem("no-permission", Map.of("permission", permission));
    }

    /**
     * Full check for issuing a punishment: base node, temporary/permanent node and maximum duration.
     * The highest matching duration limit applies; efxbans.duration.unlimited removes the limit only.
     */
    public void issue(CommandSender sender, Kind kind, long duration) {
        require(sender, "efxbans." + kind.key());
        if (!kind.timed()) return;
        require(sender, "efxbans." + kind.key() + (duration == 0 ? ".permanent" : ".temporary"));
        if (duration <= 0 || has(sender, "efxbans.duration.unlimited")) return;
        long maximum = plugin.settings().defaultMax();
        for (var entry : plugin.settings().limits().entrySet()) if (has(sender, entry.getKey())) maximum = Math.max(maximum, entry.getValue());
        if (duration > maximum) throw new Problem("maximum-duration", Map.of("maximum", plugin.settings().messages().duration(maximum)));
    }

    public boolean canIssue(CommandSender sender, Kind kind) {
        if (!has(sender, "efxbans." + kind.key())) return false;
        return !kind.timed() || has(sender, "efxbans." + kind.key() + ".temporary") || has(sender, "efxbans." + kind.key() + ".permanent");
    }

    /** Bit mask of the kinds this player is exempt from. */
    public int exemptions(CommandSender player) {
        int mask = 0;
        for (Kind k : Kind.values()) if (has(player, "efxbans.exempt.all") || has(player, "efxbans.exempt." + k.key())) mask |= k.bit();
        return mask;
    }
}

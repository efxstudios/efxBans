package com.efxstudios.bans.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Optional efxCore integration: a muted player's message in an active control (inspection) session is
 * routed to that session instead of being dropped. Uses public efxCore methods only, on the main thread.
 */
public final class CoreBridge {
    private final BansPlugin plugin;
    private boolean warned;

    public CoreBridge(BansPlugin plugin) {
        this.plugin = plugin;
    }

    /** True if efxCore took the message. The caller has already cancelled the chat event either way. */
    public boolean route(Player player, String message) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("CoreBridge must run on the main thread");
        if (!plugin.settings().coreIntegration()) return false;
        Plugin core = Bukkit.getPluginManager().getPlugin("efxCore");
        if (core == null || !core.isEnabled()) return false;
        try {
            ClassLoader loader = core.getClass().getClassLoader();
            Class<?> modules = Class.forName("com.efxstudios.efxCore.config.PluginManager", true, loader);
            Method enabled = modules.getMethod("isModuleEnabled", String.class);
            if (!(boolean) enabled.invoke(null, "control") || !(boolean) enabled.invoke(null, "chat-channels")) return false;
            // Class.getMethod would resolve every getter return type on EfxCore, including optional
            // PlaceholderAPI classes. An exact MethodHandle lookup also works when those plugins are absent.
            Class<?> managerType = Class.forName("com.efxstudios.efxCore.control.ControlManager", false, loader);
            Object manager = MethodHandles.publicLookup()
                    .findVirtual(core.getClass(), "getControlManager", MethodType.methodType(managerType))
                    .invoke(core);
            if (manager == null) return false;
            if (!(boolean) manager.getClass().getMethod("shouldRouteToControl", Player.class).invoke(manager, player)) return false;
            // Never uncancel the chat event: the text must not leak to general chat.
            if (!message.isBlank()) manager.getClass().getMethod("routeControlMessage", Player.class, String.class).invoke(manager, player, message);
            return true;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError fatal) throw fatal;
            if (!warned) {
                warned = true;
                plugin.getLogger().severe("The efxCore control chat API is not available; the current ControlManager methods are required. "
                        + "The message was not released to public chat. " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return false;
        }
    }
}

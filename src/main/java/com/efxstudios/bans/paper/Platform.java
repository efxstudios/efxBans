package com.efxstudios.bans.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class Platform {
    private static final GsonComponentSerializer JSON = GsonComponentSerializer.builder()
            .options(JSONOptions.compatibility())
            .editOptions(options -> options.value(JSONOptions.EMIT_RGB, true))
            .build();
    private Platform() {}

    static boolean available(String name) {
        try {
            Class.forName(name, false, Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static String minecraftVersion() {
        return Bukkit.getBukkitVersion().split("-", 2)[0];
    }

    static CommandMap commandMap() {
        return Bukkit.getCommandMap();
    }

    static void send(CommandSender sender, Component message) {
        invoke(Native.SEND, sender, nativeComponent(message));
    }

    static void kick(Player player, Component message) {
        invoke(Native.KICK, player, nativeComponent(message));
    }

    static void disallow(AsyncPlayerPreLoginEvent event, AsyncPlayerPreLoginEvent.Result result, Component message) {
        invoke(Native.DISALLOW, event, result, nativeComponent(message));
    }

    private static Object nativeComponent(Component message) {
        return invoke(Native.DESERIALIZE, Native.SERIALIZER, JSON.serialize(message));
    }

    private static Object invoke(Method method, Object target, Object... values) {
        try {
            return method.invoke(target, values);
        } catch (ReflectiveOperationException e) {
            throw failure(e);
        }
    }

    private static IllegalStateException failure(ReflectiveOperationException e) {
        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
        return new IllegalStateException("Paper API call failed", cause);
    }

    private static final class Native {
        static final Object SERIALIZER;
        static final Method DESERIALIZE;
        static final Method SEND;
        static final Method KICK;
        static final Method DISALLOW;

        static {
            try {
                ClassLoader loader = Bukkit.class.getClassLoader();
                String namespace = "net".concat(".kyori.adventure.text.");
                Class<?> component = Class.forName(namespace + "Component", true, loader);
                Class<?> serializer = Class.forName(namespace + "serializer.gson.GsonComponentSerializer", true, loader);
                SERIALIZER = serializer.getMethod("gson").invoke(null);
                DESERIALIZE = serializer.getMethod("deserialize", Object.class);
                SEND = CommandSender.class.getMethod("sendMessage", component);
                KICK = Player.class.getMethod("kick", component);
                DISALLOW = AsyncPlayerPreLoginEvent.class.getMethod("disallow", AsyncPlayerPreLoginEvent.Result.class, component);
            } catch (ReflectiveOperationException e) {
                throw failure(e);
            }
        }
    }
}

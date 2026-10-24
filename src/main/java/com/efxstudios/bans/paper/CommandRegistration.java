package com.efxstudios.bans.paper;

final class CommandRegistration {
    private CommandRegistration() {}

    static void install(BansPlugin plugin) {
        if (!Platform.available("io.papermc.paper.command.brigadier.Commands")) {
            LegacyCommands.install(plugin);
            return;
        }
        try {
            Class.forName("com.efxstudios.bans.paper.ModernCommands").getDeclaredMethod("install", BansPlugin.class).invoke(null, plugin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not register Paper commands", e);
        }
    }
}

package com.efxstudios.bans.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LegacyCommands implements CommandTree.Registrar<CommandSender> {
    private final BansPlugin plugin;
    private final CommandDispatcher<CommandSender> dispatcher = new CommandDispatcher<>();
    private final CommandMap commandMap;

    LegacyCommands(BansPlugin plugin, CommandMap commandMap) {
        this.plugin = plugin;
        this.commandMap = commandMap;
    }

    static void install(BansPlugin plugin) {
        new CommandTree<CommandSender>(plugin).register(new LegacyCommands(plugin, Platform.commandMap()));
    }

    @Override
    public CommandDispatcher<CommandSender> getDispatcher() { return dispatcher; }

    @Override
    public Set<String> register(LiteralCommandNode<CommandSender> node, String description, List<String> aliases) {
        dispatcher.getRoot().addChild(node);
        Root command = new Root(node);
        commandMap.register("efxbans", command);
        knownCommands().put(node.getLiteral(), command);
        return Set.of(node.getLiteral(), "efxbans:" + node.getLiteral());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> knownCommands() {
        for (Class<?> type = commandMap.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("knownCommands");
                field.setAccessible(true);
                return (Map<String, Command>) field.get(commandMap);
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not register commands", e);
            }
        }
        throw new IllegalStateException("Paper command map is unavailable");
    }

    private final class Root extends Command implements PluginIdentifiableCommand {
        private final LiteralCommandNode<CommandSender> node;

        Root(LiteralCommandNode<CommandSender> node) {
            super(node.getLiteral(), "efxBans", "/" + node.getLiteral(), List.of());
            this.node = node;
        }

        @Override
        public Plugin getPlugin() { return plugin; }

        @Override
        public boolean testPermissionSilent(CommandSender sender) { return node.canUse(sender); }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!node.canUse(sender)) return true;
            try {
                dispatcher.execute(input(args), sender);
            } catch (CommandSyntaxException e) {
                plugin.tell(sender, "error", Map.of("error", e.getRawMessage().getString()));
            } catch (Exception e) {
                plugin.error(sender, e);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!node.canUse(sender)) return List.of();
            return dispatcher.getCompletionSuggestions(dispatcher.parse(input(args), sender)).join().getList().stream()
                    .map(s -> s.getText()).toList();
        }

        private String input(String[] args) { return node.getLiteral() + (args.length == 0 ? "" : " " + String.join(" ", args)); }
    }
}

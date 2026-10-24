package com.efxstudios.bans.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;
import java.util.Set;

final class ModernCommands implements CommandTree.Registrar<CommandSourceStack> {
    private final Commands registrar;

    ModernCommands(Commands registrar) { this.registrar = registrar; }

    static void install(BansPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS
                .newHandler(event -> new CommandTree<>(plugin, CommandSourceStack::getSender).register(new ModernCommands(event.registrar())))
                .priority(Integer.MAX_VALUE));
    }

    @Override
    public CommandDispatcher<CommandSourceStack> getDispatcher() { return registrar.getDispatcher(); }

    @Override
    public Set<String> register(LiteralCommandNode<CommandSourceStack> node, String description, List<String> aliases) {
        return registrar.register((LiteralCommandNode<CommandSourceStack>) adapt(node), description, aliases);
    }

    private static CommandNode<CommandSourceStack> adapt(CommandNode<CommandSourceStack> node) {
        CommandNode<CommandSourceStack> copy;
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            ArgumentType<?> type = argument.getType();
            if (type instanceof CommandTree.TargetArgument target) type = new Target(target);
            copy = argument(argument, type);
        } else {
            copy = new LiteralCommandNode<>(node.getName(), node.getCommand(), node.getRequirement(), node.getRedirect(), node.getRedirectModifier(), node.isFork());
        }
        node.getChildren().forEach(child -> copy.addChild(adapt(child)));
        return copy;
    }

    private static <T> ArgumentCommandNode<CommandSourceStack, T> argument(ArgumentCommandNode<CommandSourceStack, ?> node, ArgumentType<T> type) {
        return new ArgumentCommandNode<>(node.getName(), type, node.getCommand(), node.getRequirement(), node.getRedirect(), node.getRedirectModifier(), node.isFork(), node.getCustomSuggestions());
    }

    private record Target(CommandTree.TargetArgument target) implements CustomArgumentType<String, String> {
        @Override
        public ArgumentType<String> getNativeType() { return StringArgumentType.greedyString(); }

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException { return target.parse(reader); }
    }
}

package com.efxstudios.bans.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class PlatformTest {
    @Test
    void nativeAdventureConversionKeepsColorsClickAndHoverEventsAndBanScreens() {
        Component message = Component.text("Banned", TextColor.color(0xFF5656))
                .clickEvent(ClickEvent.runCommand("/efxbans:lookup #123"))
                .hoverEvent(Component.text("View punishment"));
        CommandSender sender = mock(CommandSender.class);
        Player player = mock(Player.class);
        AsyncPlayerPreLoginEvent login = mock(AsyncPlayerPreLoginEvent.class);
        Platform.send(sender, message);
        Platform.kick(player, message);
        Platform.disallow(login, AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message);
        verify(sender).sendMessage(message);
        verify(player).kick(message);
        verify(login).disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message);
    }
}

package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Punishment;
import com.efxstudios.bans.common.Store;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;

import java.util.Map;
import java.util.Optional;

final class AltsView {
    static final TextColor BANNED = TextColor.color(0xFF5656);
    static final TextColor ONLINE = TextColor.color(0x90EE90);
    static final TextColor OFFLINE = TextColor.color(0xD3D3D3);

    private AltsView() {}

    static Component entry(Messages messages, Store.Profile profile, boolean online, Optional<Punishment> ban, boolean canLookup) {
        Component name = Component.text(Messages.hideUuid(profile.name()), ban.isPresent() ? BANNED : online ? ONLINE : OFFLINE);
        if (canLookup && ban.isPresent()) {
            Punishment punishment = ban.get();
            name = name.hoverEvent(messages.render("lists.hover", messages.values(punishment)))
                    .clickEvent(ClickEvent.runCommand("/efxbans:lookup #" + punishment.id()));
        }
        return messages.render("alts-entry", Map.of(), Map.of("player", name));
    }
}

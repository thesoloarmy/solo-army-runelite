package com.soloarmy.bingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(SoloArmyBingoConfig.GROUP)
public interface SoloArmyBingoConfig extends Config
{
    String GROUP = "soloarmybingo";

    @ConfigItem(
        keyName = "enabled",
        name = "Automatic Bingo tracking",
        description = "When enabled, this plugin connects to soloarmy.info to retrieve Bingo boards, bounties, standings and tracked item IDs. It sends your RuneScape name and an installation identifier to link your device, and sends matching loot item IDs, quantities, loot sources, timestamps and plugin version for Bingo submissions. Your device authorization token is sent with authenticated requests.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
    )
    default boolean enabled()
    {
        return false;
    }
}

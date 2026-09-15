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
        description = "When enabled, this plugin sends your RuneScape name and only loot matching the active Solo Army Bingo board (item id, quantity, loot source, timestamp, plugin version, installation identifier) to soloarmy.info.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
    )
    default boolean enabled()
    {
        return false;
    }
}

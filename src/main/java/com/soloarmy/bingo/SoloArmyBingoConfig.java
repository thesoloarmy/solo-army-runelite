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
        description = "When enabled, this plugin sends your RuneScape name and only loot matching the active Solo Army Bingo board (item id, quantity, loot source, timestamp, plugin version) to soloarmy.info."
    )
    default boolean enabled()
    {
        return true;
    }
}

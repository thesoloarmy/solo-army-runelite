package com.soloarmy.bingo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SoloArmyBingoPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(SoloArmyBingoPlugin.class);
        RuneLite.main(args);
    }
}

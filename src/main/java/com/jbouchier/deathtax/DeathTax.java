package com.jbouchier.deathtax;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class DeathTax extends JavaPlugin {

    @Override
    public void onEnable() {
        new DeathTaxLogic(this);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
    }
}
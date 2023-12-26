package com.jbouchier.deathtax;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

public class DeathTaxLogic implements Listener {

    private Economy econ;
    private final Plugin plugin;
    private OfflinePlayer banker;

    public DeathTaxLogic(Plugin plugin) {
        this.plugin = plugin;
        var pm = plugin.getServer().getPluginManager();

        if (!pm.isPluginEnabled("Vault")) {
            plugin.getLogger().severe("Vault wasn't detected! DeathTax will be disabled!");
            pm.disablePlugin(plugin);
            return;
        }

        var rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) this.econ = rsp.getProvider();

        if (this.econ == null) {
            plugin.getLogger().severe("Vault-Compatible Economy wasn't detected! DeathTax will be disabled!");
            pm.disablePlugin(plugin);
            return;
        }

        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        try {
            banker = plugin.getServer().getOfflinePlayer(UUID.fromString(plugin.getConfig().getString("tax-collector-uuid", "")));
            pm.registerEvents(this, plugin);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().severe("Unknown Tax Collector UUID has been configured! DeathTax will be disabled!");
            pm.disablePlugin(plugin);
        }
    }

    private void notifyPlayer(String rawMessage, OfflinePlayer player, double taxAmount) {
        if (player == null || player.getPlayer() == null || rawMessage == null || rawMessage.isBlank()) return;
        player.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                rawMessage.replace("{TAX}", "%.2f".formatted(taxAmount))));
    }

    @EventHandler
    private void onPlayerDeath(PlayerDeathEvent event) {
        final var config = plugin.getConfig();
        var player = event.getEntity();

        var balance = econ.getBalance(player);
        if (balance < Math.max(0, config.getDouble("minimum-balance", 0))) return;

        var taxAmount = Math.max(0, Math.min(config.getDouble("death-tax-amount", 0), balance));
        if (taxAmount <= 0) return;

        var response = econ.withdrawPlayer(player, taxAmount);

        if (response.type != EconomyResponse.ResponseType.SUCCESS) {
            plugin.getLogger().warning("Unable to process DeathTax of %s: %s"
                    .formatted(player.getName(), response.errorMessage));
            return;
        }

        notifyPlayer(config.getString("victim-notification"), player, taxAmount);

        if (player.getKiller() != null) {
            player = player.getKiller();
            balance = Math.max(0, Math.min(taxAmount, config.getDouble("amount-to-killer", 0)));
            response = econ.depositPlayer(player, balance);
            if (response.type == EconomyResponse.ResponseType.SUCCESS) {
                notifyPlayer(config.getString("killer-notification"), player, balance);
                if ((taxAmount = Math.max(0, taxAmount - balance)) <= 0) return;
            } else {
                plugin.getLogger().warning("Unable to process DeathTax Killer Reward for %s: %s"
                        .formatted(player.getName(), response.errorMessage));
            }
        }

        response = econ.depositPlayer(banker, taxAmount);
        if (response.type == EconomyResponse.ResponseType.SUCCESS)
            notifyPlayer(config.getString("banker-notification"), banker, taxAmount);
        else plugin.getLogger().warning("Unable to process DeathTax Banker Deposit: " + response.errorMessage);
    }
}
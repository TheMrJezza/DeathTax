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
import java.util.logging.Logger;

public class DeathTaxLogic implements Listener {

    private Economy econ;
    private final Plugin plugin;
    private OfflinePlayer banker;
    private final Logger log;

    public DeathTaxLogic(Plugin plugin) {
        this.plugin = plugin;
        var pm = plugin.getServer().getPluginManager();
        this.log = plugin.getLogger();

        String error = null;
        if (pm.isPluginEnabled("Vault")) {
            final var rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.econ = rsp.getProvider();
                plugin.saveDefaultConfig();
                plugin.reloadConfig();
                try {
                    final var bankerID = plugin.getConfig().getString("tax-collector-uuid", "");
                    banker = plugin.getServer().getOfflinePlayer(UUID.fromString(bankerID));
                    pm.registerEvents(this, plugin);
                } catch (IllegalArgumentException ex) {
                    error = "Unknown Tax Collector UUID has been configured";
                }
            } else error = "Vault-Compatible Economy wasn't detected";
        } else error = "Vault wasn't detected";

        if (error == null) return;
        plugin.getLogger().severe("%s! DeathTax will be disabled!".formatted(error));
        pm.disablePlugin(plugin);
    }

    private void notifyPlayer(String rawMessage, OfflinePlayer player, double taxAmount) {
        if (player == null || player.getPlayer() == null || rawMessage == null || rawMessage.isBlank()) return;
        player.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                rawMessage.replace("{TAX}", "%.2f".formatted(taxAmount))));
    }

    private boolean handleCash(boolean deposit, double amount, OfflinePlayer player, String err) {
        final var er = deposit ? this.econ.depositPlayer(player, amount) : this.econ.withdrawPlayer(player, amount);
        if (er.type != EconomyResponse.ResponseType.SUCCESS) {
            this.log.warning(err + ":");
            this.log.warning("Account Holder: " + player.getName());
            this.log.warning("Caused By: " + er.errorMessage);
            return false;
        }
        notifyPlayer(this.plugin.getConfig().getString(deposit ? "tax-received-notification" : "taxed-notification"), player, amount);
        return true;
    }

    @EventHandler
    private void handlePlayerDeath(PlayerDeathEvent event) {
        final var config = this.plugin.getConfig();

        final var taxAmount = Math.max(0, config.getDouble("death-tax-amount", 0));
        if (taxAmount <= 0) return;

        final var player = event.getEntity();
        var balance = econ.getBalance(player);
        if (balance < Math.max(config.getDouble("minimum-balance", 0), 0)) return;

        var taxPaidFromVictim = taxAmount;
        var taxPaidFromKiller = 0.0;

        if (player.getKiller() != null) {
            final var killer = player.getKiller();
            final var killerBalance = econ.getBalance(killer);
            var killerContribution = Math.min(Math.max(0, config.getDouble("amount-from-killer", 0)), taxAmount);
            taxPaidFromVictim = Math.max(taxPaidFromVictim - killerContribution, 0);
            killerContribution = Math.min(Math.max(Math.min(taxAmount, killerContribution), 0), killerBalance);
            taxPaidFromKiller = handleCash(false, killerContribution, killer, "Unable to charge killer contribution tax") ? killerContribution : 0;
        }

        if (taxPaidFromVictim > 0) {
            var victimContribution = Math.max(0, Math.min(taxPaidFromVictim, balance));
            taxPaidFromVictim = handleCash(false, victimContribution, player, "Unable to charge victim DeathTax") ? victimContribution : 0;
        }

        var taxCollected = taxPaidFromKiller + taxPaidFromVictim;
        if (taxCollected <= 0) return;

        if (taxCollected >= taxAmount && player.getKiller() != null) {
            final var killerReward = Math.min(Math.max(0, config.getDouble("amount-to-killer", 0)), taxCollected);
            if (killerReward > 0) {
                if (handleCash(true, killerReward, player.getKiller(), "Unable to pay killer reward tax"))
                    taxCollected = Math.max(0, taxCollected - killerReward);
            }
        }

        if (taxCollected > 0) {
            if (handleCash(true, taxCollected, banker, "Unable to pay banker collected DeathTax")) {
                if (taxCollected != taxAmount) {
                    if (player.getKiller() != null) {
                        log.info("Tax Collection Incomplete: ");
                        log.info("Collected: %.02f".formatted(taxCollected));
                        log.info("Owed: %.02f".formatted(taxAmount));
                        log.info("Killer: " + player.getKiller().getName());
                    }
                }
            }
        }
    }
}
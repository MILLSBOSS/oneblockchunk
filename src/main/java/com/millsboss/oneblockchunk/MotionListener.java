package com.millsboss.oneblockchunk;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Plays the real one's one-off animations (swings, hits, deaths) on its copies. */
public final class MotionListener implements Listener {

    private final OneBlockChunkPlugin plugin;

    public MotionListener(OneBlockChunkPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent e) {
        for (LivingEntity c : plugin.bridge().copiesOf(e.getPlayer().getUniqueId())) {
            if (e.getHand() == EquipmentSlot.OFF_HAND) c.swingOffHand();
            else c.swingMainHand();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        Entity attacker = e.getDamager();
        if (attacker instanceof Projectile p && p.getShooter() instanceof Entity shooter) attacker = shooter;
        if (attacker instanceof org.bukkit.entity.Player || ViewBridge.isCopy(attacker)) return; // players swing via onSwing
        for (LivingEntity c : plugin.bridge().copiesOf(attacker.getUniqueId())) c.swingMainHand();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (ViewBridge.isCopy(e.getEntity()) || e.getFinalDamage() <= 0) return;
        for (LivingEntity c : plugin.bridge().copiesOf(e.getEntity().getUniqueId())) {
            c.playHurtAnimation(0f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (ViewBridge.isCopy(e.getEntity())) {
            // a copy finishing its death animation: drop nothing
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        plugin.bridge().killCopies(e.getEntity().getUniqueId());
    }
}

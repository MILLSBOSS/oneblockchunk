package com.millsboss.oneblockchunk;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Jump on the block to shrink in; walk off the pocket's edge to grow back out. */
public final class PlayerListener implements Listener {

    private final OneBlockChunkPlugin plugin;

    public PlayerListener(OneBlockChunkPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld().equals(plugin.pocketWorld()) || plugin.travel().isBusy(p)) return;
        Location from = e.getFrom();
        Block under = from.clone().subtract(0, 0.2, 0).getBlock();
        Integer id = plugin.store().idAt(under);
        if (id == null) return;
        if (!p.hasPermission("oneblockchunk.use")) return;
        plugin.travel().enter(p, id);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!p.getWorld().equals(plugin.pocketWorld())) return;
        Location to = e.getTo();
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockZ() == to.getBlockZ()
                && to.getY() >= plugin.pocketWorld().getMinHeight()) return;
        int id = plugin.travel().insideId(p);
        if (id < 0) {
            // Somehow in the pocket world with no pocket: send them home.
            if (!plugin.travel().isBusy(p)) plugin.travel().exit(p, 0, 0);
            return;
        }
        int minX = PocketGrid.chunkX(id) << 4, minZ = PocketGrid.chunkZ(id) << 4;
        int dx = to.getBlockX() < minX ? -1 : to.getBlockX() > minX + 15 ? 1 : 0;
        int dz = to.getBlockZ() < minZ ? -1 : to.getBlockZ() > minZ + 15 ? 1 : 0;
        boolean fellOut = to.getY() < plugin.pocketWorld().getMinHeight();
        if (dx != 0 || dz != 0 || fellOut) plugin.travel().exit(p, dx, dz);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (e.getFrom().getWorld().equals(plugin.pocketWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        // Teleported out of the pocket world some other way (/spawn, /tp...): forget the pocket.
        if (e.getFrom().getWorld().equals(plugin.pocketWorld())
                && !e.getTo().getWorld().equals(plugin.pocketWorld())
                && plugin.travel().insideId(e.getPlayer()) >= 0
                && !plugin.travel().isBusy(e.getPlayer())) {
            plugin.travel().clear(e.getPlayer());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!e.getRespawnLocation().getWorld().equals(plugin.pocketWorld())) {
            plugin.travel().clear(e.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Undo a shrink interrupted by a disconnect or crash.
        if (Travel.scale(p) < 0.99 && Travel.scale(p) >= Travel.TINY - 1e-6
                && (p.getWorld().equals(plugin.pocketWorld()) || plugin.travel().insideId(p) < 0)) {
            Travel.setScale(p, 1.0);
        }
    }
}

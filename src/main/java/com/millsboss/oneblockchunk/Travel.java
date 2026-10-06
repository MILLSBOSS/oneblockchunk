package com.millsboss.oneblockchunk;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Shrinking into a pocket and growing back out. */
public final class Travel {

    /** Smallest scale the game allows, exactly 1/16: one block = one chunk. */
    public static final double TINY = 0.0625;
    private static final int ANIM_TICKS = 16;

    private final OneBlockChunkPlugin plugin;
    private final NamespacedKey insideKey;
    private final NamespacedKey returnKey;
    private final Set<UUID> busy = new HashSet<>();

    public Travel(OneBlockChunkPlugin plugin) {
        this.plugin = plugin;
        this.insideKey = new NamespacedKey(plugin, "inside_pocket");
        this.returnKey = new NamespacedKey(plugin, "return_location");
    }

    public boolean isBusy(Player p) {
        return busy.contains(p.getUniqueId());
    }

    /** @return the pocket id the player is inside, or -1. */
    public int insideId(Player p) {
        Integer id = p.getPersistentDataContainer().get(insideKey, PersistentDataType.INTEGER);
        return id == null ? -1 : id;
    }

    public void enter(Player p, int id) {
        if (busy.contains(p.getUniqueId()) || insideId(p) >= 0) return;
        busy.add(p.getUniqueId());
        Location from = p.getLocation();
        World pw = plugin.pocketWorld();
        p.playSound(from, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.6f);
        // Start loading the pocket chunk while the shrink plays.
        var chunkFuture = pw.getChunkAtAsync(PocketGrid.chunkX(id), PocketGrid.chunkZ(id));
        animateScale(p, scale(p), TINY, () -> chunkFuture.whenComplete((chunk, err) -> {
            if (!p.isOnline()) {
                busy.remove(p.getUniqueId());
                return;
            }
            if (err != null) {
                busy.remove(p.getUniqueId());
                setScale(p, 1.0);
                p.sendMessage(Component.text("That chunk could not be loaded.", NamedTextColor.RED));
                return;
            }
            int bx = (PocketGrid.chunkX(id) << 4) + 8;
            int bz = (PocketGrid.chunkZ(id) << 4) + 8;
            int top = pw.getHighestBlockYAt(bx, bz);
            if (top < pw.getMinHeight()) top = pw.getMinHeight() + 64;
            Location target = new Location(pw, bx + 0.5, top + 1, bz + 0.5, from.getYaw(), from.getPitch());
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            pdc.set(insideKey, PersistentDataType.INTEGER, id);
            pdc.set(returnKey, PersistentDataType.STRING, serialize(from));
            p.teleportAsync(target).whenComplete((ok, e2) -> {
                busy.remove(p.getUniqueId());
                setScale(p, 1.0);
                p.setFallDistance(0);
                if (!Boolean.TRUE.equals(ok)) {
                    pdc.remove(insideKey);
                    pdc.remove(returnKey);
                    return;
                }
                p.showTitle(Title.title(Component.text("OneBlock Chunk #" + id, NamedTextColor.GREEN),
                        Component.text("Walk off the edge to grow back", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
                p.playSound(target, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            });
        }));
    }

    /**
     * @param dx,dz which edge the player walked off (-1/0/1), used to drop them
     *               beside the block on that side. Zero means "on top".
     */
    public void exit(Player p, int dx, int dz) {
        int id = insideId(p);
        if (id < 0 || busy.contains(p.getUniqueId())) return;
        busy.add(p.getUniqueId());
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Location back = deserialize(pdc.get(returnKey, PersistentDataType.STRING));
        PocketStore.BlockKey placed = plugin.store().placement(id);
        if (placed != null) {
            Location beside = besideBlock(placed, dx, dz, p);
            if (beside != null) back = beside;
        }
        if (back == null) back = Bukkit.getWorlds().getFirst().getSpawnLocation();
        pdc.remove(insideKey);
        pdc.remove(returnKey);
        plugin.renderer().markDirty(id);
        setScale(p, TINY);
        Location dest = back;
        p.teleportAsync(dest).whenComplete((ok, err) -> {
            p.setFallDistance(0);
            dest.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, dest.clone().add(0, 0.3, 0), 12, 0.3, 0.2, 0.3);
            p.playSound(dest, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.4f);
            animateScale(p, TINY, 1.0, () -> busy.remove(p.getUniqueId()));
        });
    }

    /** Clear pocket state without teleporting (death, admin). */
    public void clear(Player p) {
        p.getPersistentDataContainer().remove(insideKey);
        p.getPersistentDataContainer().remove(returnKey);
        busy.remove(p.getUniqueId());
        setScale(p, 1.0);
    }

    private Location besideBlock(PocketStore.BlockKey k, int dx, int dz, Player p) {
        World w = Bukkit.getWorld(k.world());
        if (w == null) return null;
        if (dx == 0 && dz == 0) dz = 1;
        int x = k.x() + dx, z = k.z() + dz;
        int y = k.y();
        Block feet = w.getBlockAt(x, y, z);
        Block head = feet.getRelative(0, 1, 0);
        if (!feet.isPassable() || !head.isPassable()) {
            y = w.getHighestBlockYAt(x, z) + 1;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)); // face away from the block
        return new Location(w, x + 0.5, y, z + 0.5, yaw, p.getLocation().getPitch());
    }

    private void animateScale(Player p, double from, double to, Runnable done) {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    busy.remove(p.getUniqueId());
                    return;
                }
                t++;
                double f = Math.min(1.0, t / (double) ANIM_TICKS);
                double eased = f * f * (3 - 2 * f);
                setScale(p, from + (to - from) * eased);
                if (to < from) {
                    p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 0.2, 0), 2, 0.2, 0.1, 0.2, 0.01);
                }
                if (f >= 1.0) {
                    cancel();
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static double scale(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        return a == null ? 1.0 : a.getBaseValue();
    }

    public static void setScale(Player p, double s) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null) a.setBaseValue(s);
    }

    private static String serialize(Location l) {
        return l.getWorld().getUID() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private static Location deserialize(String s) {
        if (s == null) return null;
        try {
            String[] a = s.split(";");
            World w = Bukkit.getWorld(UUID.fromString(a[0]));
            if (w == null) return null;
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    Float.parseFloat(a[4]), Float.parseFloat(a[5]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}

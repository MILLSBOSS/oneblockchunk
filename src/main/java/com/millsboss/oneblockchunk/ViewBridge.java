package com.millsboss.oneblockchunk;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Links each pocket to the world around its OneBlock Chunk, both ways.
 *
 * <p>Outside looking in: every mob and player inside a pocket (on the surface) is
 * mirrored as a 1/16 scale copy walking on the mini model.
 *
 * <p>Inside looking out: while someone is in a pocket, the blocks around the OneBlock
 * Chunk are rebuilt around the pocket at 16x scale (one outside block = one chunk), and
 * nearby outside players and mobs are mirrored as 16x giants. It is the same mapping
 * in reverse, so a player standing beside the block looks like a giant beside the chunk.
 */
public final class ViewBridge {

    public static final String TAG = "oneblockchunk_proxy";
    private static final double SMALL = 1.0 / 16.0;
    private static final double GIANT = 16.0;
    /** Outside blocks shown around the pocket, each direction. */
    private static final int RADIUS = 12;
    private static final int BELOW = 6;
    private static final int ABOVE = 16;
    private static final int MAX_CUBES = 6000;

    private final OneBlockChunkPlugin plugin;

    /** source entity -> its copy */
    private final Map<UUID, LivingEntity> small = new HashMap<>();
    private final Map<UUID, LivingEntity> giant = new HashMap<>();

    private static final class Surroundings {
        final Map<Long, BlockDisplay> cubes = new HashMap<>();
        final Map<Long, BlockData> shown = new HashMap<>();
        final Set<Long> ticketed = new HashSet<>();
        UUID ticketWorld;
    }

    private final Map<Integer, Surroundings> surroundings = new HashMap<>();
    private final Set<Integer> pocketTickets = new HashSet<>();
    private final Set<Integer> pendingTickets = new HashSet<>();
    private int tick;

    public ViewBridge(OneBlockChunkPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        tick++;
        World pw = plugin.pocketWorld();
        Set<UUID> seenSmall = new HashSet<>();
        Set<UUID> seenGiant = new HashSet<>();

        // pockets someone is standing in right now
        Set<Integer> occupied = new HashSet<>();
        for (Player p : pw.getPlayers()) {
            int id = plugin.travel().insideId(p);
            if (id >= 0) occupied.add(id);
        }

        for (Map.Entry<Integer, PocketStore.BlockKey> entry : plugin.store().allPlaced().entrySet()) {
            int id = entry.getKey();
            PocketStore.BlockKey key = entry.getValue();
            World ow = Bukkit.getWorld(key.world());
            if (ow == null) continue;
            Integer base = plugin.renderer().baseY(id);
            boolean outsideLoaded = ow.isChunkLoaded(key.x() >> 4, key.z() >> 4);

            if (occupied.contains(id)) {
                holdOutside(id, ow, key);
            } else {
                clearSurroundings(id);
            }
            if (!outsideLoaded && !occupied.contains(id)) {
                releasePocket(id);
                continue;
            }
            holdPocket(id);
            if (base == null || !pw.isChunkLoaded(PocketGrid.chunkX(id), PocketGrid.chunkZ(id))) continue;

            mirrorInsideOut(id, key, ow, base, seenSmall);
            if (occupied.contains(id) && ow.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                mirrorOutsideIn(id, key, ow, base, seenGiant);
                if (tick % 40 == 0 || surroundings.get(id) == null || surroundings.get(id).cubes.isEmpty()) {
                    buildSurroundings(id, key, ow, base);
                }
            }
        }

        // pockets no longer placed
        surroundings.keySet().removeIf(id -> {
            if (plugin.store().placement(id) != null) return false;
            Surroundings s = surroundings.get(id);
            removeCubes(s);
            releaseOutside(s);
            return true;
        });
        for (Integer id : new HashSet<>(pocketTickets)) {
            if (plugin.store().placement(id) == null) releasePocket(id);
        }

        prune(small, seenSmall);
        prune(giant, seenGiant);
    }

    // ---------- outside looking in: tiny copies on the mini model ----------

    private void mirrorInsideOut(int id, PocketStore.BlockKey key, World ow, int base, Set<UUID> seen) {
        if (!ow.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return;
        Chunk pc = plugin.pocketWorld().getChunkAt(PocketGrid.chunkX(id), PocketGrid.chunkZ(id));
        int minX = PocketGrid.minX(id), minZ = PocketGrid.minZ(id);
        for (Entity e : pc.getEntities()) {
            if (!(e instanceof LivingEntity le) || le.isDead() || le.getScoreboardTags().contains(TAG)) continue;
            Location l = le.getLocation();
            double lx = l.getX() - minX, lz = l.getZ() - minZ, ly = l.getY() - base;
            if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16 || ly < 0) continue; // underground stays hidden
            Location target = new Location(ow, key.x() + lx * SMALL, key.y() + 1 + ly * SMALL, key.z() + lz * SMALL,
                    l.getYaw(), l.getPitch());
            follow(small, le, target, SMALL);
            seen.add(le.getUniqueId());
        }
    }

    // ---------- inside looking out: giant world and giant players ----------

    private void mirrorOutsideIn(int id, PocketStore.BlockKey key, World ow, int base, Set<UUID> seen) {
        World pw = plugin.pocketWorld();
        int minX = PocketGrid.minX(id), minZ = PocketGrid.minZ(id);
        Location center = new Location(ow, key.x() + 0.5, key.y() + 1, key.z() + 0.5);
        for (Entity e : ow.getNearbyEntities(center, RADIUS + 1, ABOVE, RADIUS + 1)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || le.getScoreboardTags().contains(TAG)) continue;
            if (e instanceof Player p && plugin.travel().isBusy(p)) continue; // mid-shrink
            Location l = le.getLocation();
            if (l.getY() < key.y() - BELOW) continue;
            Location target = new Location(pw,
                    minX + (l.getX() - key.x()) * GIANT,
                    base + (l.getY() - (key.y() + 1)) * GIANT,
                    minZ + (l.getZ() - key.z()) * GIANT,
                    l.getYaw(), l.getPitch());
            if (!pw.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) continue;
            follow(giant, le, target, GIANT);
            seen.add(le.getUniqueId());
        }
    }

    private void buildSurroundings(int id, PocketStore.BlockKey key, World ow, int base) {
        World pw = plugin.pocketWorld();
        Surroundings s = surroundings.computeIfAbsent(id, k -> new Surroundings());
        int minX = PocketGrid.minX(id), minZ = PocketGrid.minZ(id);
        Map<Long, BlockData> want = new HashMap<>();
        // nearest rings first, so if the cap is hit it is the far edge that gets dropped
        outer:
        for (int ring = 0; ring <= RADIUS; ring++)
        for (int ox = -ring; ox <= ring; ox++) {
            for (int oz = -ring; oz <= ring; oz++) {
                if (Math.max(Math.abs(ox), Math.abs(oz)) != ring) continue;
                for (int oy = -BELOW; oy <= ABOVE; oy++) {
                    if (ox == 0 && oz == 0 && oy <= 0) continue; // that column is the pocket itself
                    Block b = ow.getBlockAt(key.x() + ox, key.y() + oy, key.z() + oz);
                    Material t = b.getType();
                    if (t.isAir() || t == Material.LIGHT || t == Material.BARRIER) continue;
                    if (!exposed(b, ox, oy, oz)) continue;
                    BlockData data = switch (t) {
                        case WATER, BUBBLE_COLUMN -> MiniChunkRenderer.WATER_STANDIN;
                        case LAVA -> MiniChunkRenderer.LAVA_STANDIN;
                        default -> b.getBlockData();
                    };
                    want.put(pack(ox, oy, oz), data);
                    if (want.size() >= MAX_CUBES) break outer;
                }
            }
        }
        Iterator<Map.Entry<Long, BlockDisplay>> it = s.cubes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, BlockDisplay> e = it.next();
            BlockData target = want.get(e.getKey());
            BlockDisplay d = e.getValue();
            if (target == null || !d.isValid()) {
                if (d.isValid()) d.remove();
                it.remove();
                s.shown.remove(e.getKey());
            } else if (!target.equals(s.shown.get(e.getKey()))) {
                d.setBlock(target);
                s.shown.put(e.getKey(), target);
            }
        }
        for (Map.Entry<Long, BlockData> e : want.entrySet()) {
            if (s.cubes.containsKey(e.getKey())) continue;
            long p = e.getKey();
            int ox = unpackX(p), oy = unpackY(p), oz = unpackZ(p);
            // All cubes live at the pocket's centre and are pushed out with a translation, so the
            // server keeps sending them to players inside no matter how far out they reach.
            Location anchor = new Location(pw, minX + 8, base, minZ + 8);
            Vector3f offset = new Vector3f(ox * 16 - 8, (oy - 1) * 16, oz * 16 - 8);
            BlockData data = e.getValue();
            BlockDisplay d = pw.spawn(anchor, BlockDisplay.class, disp -> {
                disp.setPersistent(false);
                disp.setBlock(data);
                disp.setViewRange(16f);
                disp.setDisplayWidth(1024f);
                disp.setDisplayHeight(1024f);
                disp.setBrightness(new org.bukkit.entity.Display.Brightness(0, 15));
                disp.setTransformation(new Transformation(offset, new AxisAngle4f(),
                        new Vector3f(16f, 16f, 16f), new AxisAngle4f()));
                disp.addScoreboardTag(TAG);
            });
            s.cubes.put(p, d);
            s.shown.put(p, data);
        }
    }

    private static boolean exposed(Block b, int ox, int oy, int oz) {
        if (oy == ABOVE) return true;
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            if (oy == -BELOW && d[1] == -1) continue;
            if (!b.getRelative(d[0], d[1], d[2]).getType().isOccluding()) return true;
        }
        return false;
    }

    // ---------- copies ----------

    private void follow(Map<UUID, LivingEntity> copies, LivingEntity src, Location target, double scale) {
        LivingEntity copy = copies.get(src.getUniqueId());
        if (copy != null && (!copy.isValid() || !copy.getWorld().equals(target.getWorld()))) {
            copy.remove();
            copy = null;
        }
        if (copy == null) {
            copy = spawnCopy(src, target, scale);
            if (copy == null) return;
            copies.put(src.getUniqueId(), copy);
            return;
        }
        Location cur = copy.getLocation();
        if (cur.distanceSquared(target) > 1e-6 || cur.getYaw() != target.getYaw() || cur.getPitch() != target.getPitch()) {
            copy.teleport(target, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        copy.setBodyYaw(src.getBodyYaw());
        if (copy instanceof Mannequin m && src instanceof Player p) {
            if (Mannequin.validPoses().contains(p.getPose()) && m.getPose() != p.getPose()) m.setPose(p.getPose(), true);
            if (tick % 10 == 0) copyGear(p, m);
        }
    }

    private LivingEntity spawnCopy(LivingEntity src, Location at, double scale) {
        LivingEntity copy;
        if (src instanceof Player p) {
            copy = at.getWorld().spawn(at, Mannequin.class, m -> {
                m.setProfile(ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
                m.setDescription(null);
                m.setImmovable(true);
                m.setMainHand(p.getMainHand());
                prepare(m, scale);
                copyGear(p, m);
            });
        } else {
            Entity c;
            try {
                c = src.copy(at);
            } catch (RuntimeException ex) {
                c = null;
            }
            if (!(c instanceof LivingEntity lc)) {
                if (c != null) c.remove();
                return null;
            }
            for (Entity passenger : lc.getPassengers()) passenger.remove();
            copy = lc;
            prepare(copy, scale);
        }
        return copy;
    }

    private static void prepare(LivingEntity e, double scale) {
        e.addScoreboardTag(TAG);
        e.setPersistent(false);
        e.setAI(false);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setGravity(false);
        e.setCollidable(false);
        e.setCanPickupItems(false);
        e.setFireTicks(0);
        if (e instanceof Mob m) m.setRemoveWhenFarAway(false);
        AttributeInstance a = e.getAttribute(Attribute.SCALE);
        if (a != null) {
            a.getModifiers().forEach(a::removeModifier);
            a.setBaseValue(scale);
        }
    }

    private static void copyGear(Player p, Mannequin m) {
        EntityEquipment from = p.getEquipment();
        EntityEquipment to = m.getEquipment();
        to.setArmorContents(from.getArmorContents());
        to.setItemInMainHand(from.getItemInMainHand());
        to.setItemInOffHand(from.getItemInOffHand());
    }

    private static void prune(Map<UUID, LivingEntity> copies, Set<UUID> seen) {
        Iterator<Map.Entry<UUID, LivingEntity>> it = copies.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, LivingEntity> e = it.next();
            if (!seen.contains(e.getKey())) {
                if (e.getValue().isValid()) e.getValue().remove();
                it.remove();
            }
        }
    }

    // ---------- keeping the right chunks loaded ----------

    /** Keep a pocket loaded (so its mobs live and show) while its block is in a loaded area. */
    private void holdPocket(int id) {
        if (pocketTickets.contains(id) || !pendingTickets.add(id)) return;
        World pw = plugin.pocketWorld();
        pw.getChunkAtAsync(PocketGrid.chunkX(id), PocketGrid.chunkZ(id)).whenComplete((c, err) -> {
            pendingTickets.remove(id);
            if (err != null || c == null || !plugin.isEnabled()) return;
            c.addPluginChunkTicket(plugin);
            pocketTickets.add(id);
        });
    }

    private void releasePocket(int id) {
        if (!pocketTickets.remove(id)) return;
        plugin.pocketWorld().removePluginChunkTicket(PocketGrid.chunkX(id), PocketGrid.chunkZ(id), plugin);
    }

    /** Keep the area around the block loaded while someone inside is looking out at it. */
    private void holdOutside(int id, World ow, PocketStore.BlockKey key) {
        Surroundings s = surroundings.computeIfAbsent(id, k -> new Surroundings());
        if (!s.ticketed.isEmpty()) return;
        s.ticketWorld = ow.getUID();
        for (int cx = (key.x() - RADIUS - 1) >> 4; cx <= (key.x() + RADIUS + 1) >> 4; cx++) {
            for (int cz = (key.z() - RADIUS - 1) >> 4; cz <= (key.z() + RADIUS + 1) >> 4; cz++) {
                int fx = cx, fz = cz;
                s.ticketed.add(Chunks.key(cx, cz));
                ow.getChunkAtAsync(fx, fz).whenComplete((c, err) -> {
                    if (c != null && plugin.isEnabled() && surroundings.get(id) == s) c.addPluginChunkTicket(plugin);
                });
            }
        }
    }

    private void releaseOutside(Surroundings s) {
        World w = s.ticketWorld == null ? null : Bukkit.getWorld(s.ticketWorld);
        if (w != null) {
            for (long k : s.ticketed) w.removePluginChunkTicket((int) k, (int) (k >> 32), plugin);
        }
        s.ticketed.clear();
    }

    private void clearSurroundings(int id) {
        Surroundings s = surroundings.remove(id);
        if (s == null) return;
        removeCubes(s);
        releaseOutside(s);
    }

    private static void removeCubes(Surroundings s) {
        for (BlockDisplay d : s.cubes.values()) if (d.isValid()) d.remove();
        s.cubes.clear();
        s.shown.clear();
    }

    public void shutdown() {
        for (LivingEntity e : small.values()) if (e.isValid()) e.remove();
        for (LivingEntity e : giant.values()) if (e.isValid()) e.remove();
        small.clear();
        giant.clear();
        for (Surroundings s : surroundings.values()) {
            removeCubes(s);
            releaseOutside(s);
        }
        surroundings.clear();
        for (Integer id : new HashSet<>(pocketTickets)) releasePocket(id);
    }

    public static boolean isCopy(Entity e) {
        return e.getScoreboardTags().contains(TAG);
    }

    private static long pack(int ox, int oy, int oz) {
        return ((long) (ox + 64) << 16) | ((long) (oy + 64) << 8) | (oz + 64);
    }

    private static int unpackX(long p) {
        return (int) ((p >> 16) & 0xff) - 64;
    }

    private static int unpackY(long p) {
        return (int) ((p >> 8) & 0xff) - 64;
    }

    private static int unpackZ(long p) {
        return (int) (p & 0xff) - 64;
    }
}

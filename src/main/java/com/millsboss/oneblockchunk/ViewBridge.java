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
import org.bukkit.entity.Pose;
import org.bukkit.entity.Sittable;
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
                Surroundings sur = surroundings.get(id);
                if (sur == null || sur.cubes.isEmpty() || tick % 200 == 0) {
                    buildSurroundings(id, key, ow, base); // full pass now and then catches anything missed
                } else {
                    applyOutsideChanges(id, key, ow, base);
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

    /** Outside blocks that changed since last tick, per pocket (packed offsets). */
    private final Map<Integer, Set<Long>> outsideChanges = new HashMap<>();

    /** A block changed in the outside world: queue it if some pocket is looking at it. */
    public void outsideChanged(Block b) {
        for (Map.Entry<Integer, PocketStore.BlockKey> e : plugin.store().allPlaced().entrySet()) {
            PocketStore.BlockKey k = e.getValue();
            if (!surroundings.containsKey(e.getKey()) || !k.world().equals(b.getWorld().getUID())) continue;
            int ox = b.getX() - k.x(), oy = b.getY() - k.y(), oz = b.getZ() - k.z();
            if (Math.abs(ox) > RADIUS || Math.abs(oz) > RADIUS || oy < -BELOW - 1 || oy > ABOVE + 1) continue;
            outsideChanges.computeIfAbsent(e.getKey(), x -> new HashSet<>()).add(pack(ox, oy, oz));
        }
    }

    /** Redraw just the changed outside blocks and their neighbours (their faces may now show). */
    private void applyOutsideChanges(int id, PocketStore.BlockKey key, World ow, int base) {
        Set<Long> changes = outsideChanges.remove(id);
        Surroundings s = surroundings.get(id);
        if (changes == null || s == null) return;
        Set<Long> todo = new HashSet<>();
        for (long p : changes) {
            int ox = unpackX(p), oy = unpackY(p), oz = unpackZ(p);
            todo.add(p);
            todo.add(pack(ox + 1, oy, oz));
            todo.add(pack(ox - 1, oy, oz));
            todo.add(pack(ox, oy + 1, oz));
            todo.add(pack(ox, oy - 1, oz));
            todo.add(pack(ox, oy, oz + 1));
            todo.add(pack(ox, oy, oz - 1));
        }
        for (long p : todo) {
            int ox = unpackX(p), oy = unpackY(p), oz = unpackZ(p);
            if (Math.abs(ox) > RADIUS || Math.abs(oz) > RADIUS || oy < -BELOW || oy > ABOVE) continue;
            setCube(s, id, base, p, cubeAt(ow, key, ox, oy, oz));
        }
    }

    /** What the giant cube at this offset should show, or null for nothing. */
    private static BlockData cubeAt(World ow, PocketStore.BlockKey key, int ox, int oy, int oz) {
        if (ox == 0 && oz == 0 && oy <= 0) return null; // that column is the pocket itself
        Block b = ow.getBlockAt(key.x() + ox, key.y() + oy, key.z() + oz);
        Material t = b.getType();
        if (t.isAir() || t == Material.LIGHT || t == Material.BARRIER) return null;
        if (!exposed(b, ox, oy, oz)) return null;
        return switch (t) {
            case WATER, BUBBLE_COLUMN -> MiniChunkRenderer.WATER_STANDIN;
            case LAVA -> MiniChunkRenderer.LAVA_STANDIN;
            default -> b.getBlockData();
        };
    }

    private void setCube(Surroundings s, int id, int base, long p, BlockData data) {
        BlockDisplay d = s.cubes.get(p);
        if (d != null && !d.isValid()) {
            s.cubes.remove(p);
            s.shown.remove(p);
            d = null;
        }
        if (data == null) {
            if (d != null) {
                d.remove();
                s.cubes.remove(p);
                s.shown.remove(p);
            }
            return;
        }
        if (d != null) {
            if (!data.equals(s.shown.get(p))) {
                d.setBlock(data);
                s.shown.put(p, data);
            }
            return;
        }
        World pw = plugin.pocketWorld();
        int ox = unpackX(p), oy = unpackY(p), oz = unpackZ(p);
        // All cubes live at the pocket's centre and are pushed out with a translation, so the
        // server keeps sending them to players inside no matter how far out they reach.
        Location anchor = new Location(pw, PocketGrid.minX(id) + 8, base, PocketGrid.minZ(id) + 8);
        Vector3f offset = new Vector3f(ox * 16 - 8, (oy - 1) * 16, oz * 16 - 8);
        BlockDisplay nd = pw.spawn(anchor, BlockDisplay.class, disp -> {
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
        s.cubes.put(p, nd);
        s.shown.put(p, data);
    }

    private void buildSurroundings(int id, PocketStore.BlockKey key, World ow, int base) {
        Surroundings s = surroundings.computeIfAbsent(id, k -> new Surroundings());
        outsideChanges.remove(id); // covered by the full pass
        Map<Long, BlockData> want = new HashMap<>();
        // nearest rings first, so if the cap is hit it is the far edge that gets dropped
        outer:
        for (int ring = 0; ring <= RADIUS; ring++) {
            for (int ox = -ring; ox <= ring; ox++) {
                for (int oz = -ring; oz <= ring; oz++) {
                    if (Math.max(Math.abs(ox), Math.abs(oz)) != ring) continue;
                    for (int oy = -BELOW; oy <= ABOVE; oy++) {
                        BlockData data = cubeAt(ow, key, ox, oy, oz);
                        if (data == null) continue;
                        want.put(pack(ox, oy, oz), data);
                        if (want.size() >= MAX_CUBES) break outer;
                    }
                }
            }
        }
        for (Long p : new java.util.ArrayList<>(s.cubes.keySet())) {
            if (!want.containsKey(p)) setCube(s, id, base, p, null);
        }
        for (Map.Entry<Long, BlockData> e : want.entrySet()) setCube(s, id, base, e.getKey(), e.getValue());
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
        mirrorState(src, copy);
    }

    /** Copy everything the real one is doing that shows: pose, sitting, fire, eating, bows, shields. */
    private void mirrorState(LivingEntity src, LivingEntity copy) {
        Pose pose = src.getPose();
        if (pose != Pose.DYING && copy.getPose() != pose) {
            if (!(copy instanceof Mannequin) || Mannequin.validPoses().contains(pose)) copy.setPose(pose, true);
        }
        if (src instanceof Sittable ss && copy instanceof Sittable cs && ss.isSitting() != cs.isSitting()) {
            cs.setSitting(ss.isSitting());
        }
        boolean burning = src.getFireTicks() > 0 || src.isVisualFire();
        if (copy.isVisualFire() != burning) copy.setVisualFire(burning);

        if (copy instanceof Mannequin m && src instanceof Player p && (tick % 10 == 0 || p.isHandRaised() != m.isHandRaised())) {
            copyGear(p, m);
        }
        if (src.isHandRaised()) {
            if (!copy.isHandRaised()) copy.startUsingItem(src.getActiveItemHand());
            copy.setActiveItemRemainingTime(Math.max(1, src.getActiveItemRemainingTime()));
        } else if (copy.isHandRaised()) {
            copy.clearActiveItem();
        }
    }

    /** Every copy of this entity, tiny and giant. */
    public java.util.List<LivingEntity> copiesOf(UUID source) {
        java.util.List<LivingEntity> out = new java.util.ArrayList<>(2);
        LivingEntity a = small.get(source), b = giant.get(source);
        if (a != null && a.isValid()) out.add(a);
        if (b != null && b.isValid()) out.add(b);
        return out;
    }

    /** The real one died: its copies play the death animation too, then vanish. */
    public void killCopies(UUID source) {
        for (Map<UUID, LivingEntity> map : java.util.List.of(small, giant)) {
            LivingEntity c = map.remove(source);
            if (c == null || !c.isValid()) continue;
            c.getEquipment().clear();
            c.setInvulnerable(false);
            c.setHealth(0);
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
        outsideChanges.remove(id);
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

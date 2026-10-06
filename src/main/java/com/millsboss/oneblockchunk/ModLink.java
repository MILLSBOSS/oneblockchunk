package com.millsboss.oneblockchunk;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;

/**
 * Talks to the OneBlockChunk client mod. Players who have the mod get the real chunk
 * (or the real outside area) streamed to them block by block and entity by entity, and
 * their game draws it live at the right size. They never see the plugin's copies.
 * Players without the mod are untouched and keep seeing the copies.
 *
 * <p>Protocol (channel {@value #CHANNEL}), all big-endian, first byte is the message type:
 * see {@code OPEN, CLOSE, BLOCKS, ADD, STATE, REMOVE, ACTION, EQUIP} below. The client
 * mod's {@code Protocol} class is the matching reader.
 */
public final class ModLink {

    public static final String CHANNEL = "oneblockchunk:view";
    public static final int PROTOCOL_VERSION = 1;

    static final byte OPEN = 1, CLOSE = 2, BLOCKS = 3, ADD = 4, STATE = 5, REMOVE = 6, ACTION = 7, EQUIP = 8;
    public static final byte ACT_SWING_MAIN = 0, ACT_SWING_OFF = 1, ACT_HURT = 2;

    /** How close an outside player must be to a OneBlock Chunk to get its live view. */
    private static final double OUTSIDE_VIEW_DISTANCE = 96;
    /** Same window the copies use, so both kinds of player see the same thing. */
    private static final int OUT_HEIGHT = 48;

    private final OneBlockChunkPlugin plugin;
    private final Set<UUID> modded = new HashSet<>();
    private int nextViewId = 1;
    private int nextEntityKey = 1;
    private int tick;

    /** One live view a player is receiving. */
    private static final class View {
        final int viewId;
        final int pocketId;
        final boolean insideOut;
        final UUID srcWorld;
        final int sx0, sy0, sz0; // source box corner
        final int w, h, d;       // source box size
        final Map<UUID, Integer> entityKeys = new HashMap<>();
        final Map<UUID, String> lastState = new HashMap<>();
        final Map<UUID, String> lastGear = new HashMap<>();

        View(int viewId, int pocketId, boolean insideOut, UUID srcWorld, int sx0, int sy0, int sz0, int w, int h, int d) {
            this.viewId = viewId;
            this.pocketId = pocketId;
            this.insideOut = insideOut;
            this.srcWorld = srcWorld;
            this.sx0 = sx0;
            this.sy0 = sy0;
            this.sz0 = sz0;
            this.w = w;
            this.h = h;
            this.d = d;
        }

        boolean contains(UUID world, int x, int y, int z) {
            return world.equals(srcWorld) && x >= sx0 && x < sx0 + w && y >= sy0 && y < sy0 + h && z >= sz0 && z < sz0 + d;
        }
    }

    private final Map<UUID, Map<String, View>> views = new HashMap<>();
    /** Blocks that changed this tick, per world. */
    private final Map<UUID, Set<Long>> changed = new HashMap<>();

    public ModLink(OneBlockChunkPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- who has the mod ----------

    public boolean isModded(Player p) {
        return modded.contains(p.getUniqueId());
    }

    /** The player's client announced it can receive our channel. */
    public void playerRegistered(Player p) {
        if (!modded.add(p.getUniqueId())) return;
        plugin.getLogger().info(p.getName() + " has the OneBlockChunk client mod: sending live views.");
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (isCopyOrModel(e)) p.hideEntity(plugin, e);
        }
    }

    public void playerLeft(Player p) {
        modded.remove(p.getUniqueId());
        views.remove(p.getUniqueId());
    }

    private static boolean isCopyOrModel(Entity e) {
        Set<String> tags = e.getScoreboardTags();
        return tags.contains(ViewBridge.TAG) || tags.contains("oneblockchunk_mini");
    }

    /** A copy or model piece was just spawned: modded players see the real thing instead. */
    public void hideFromModded(Entity e) {
        for (UUID id : modded) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideEntity(plugin, e);
        }
    }

    // ---------- change notifications ----------

    public void blockChanged(Block b) {
        if (modded.isEmpty()) return;
        changed.computeIfAbsent(b.getWorld().getUID(), k -> new HashSet<>()).add(pos(b.getX(), b.getY(), b.getZ()));
    }

    public void action(UUID entity, byte action) {
        if (modded.isEmpty()) return;
        for (Map.Entry<UUID, Map<String, View>> e : views.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (View v : e.getValue().values()) {
                Integer key = v.entityKeys.get(entity);
                if (key == null) continue;
                send(p, out -> {
                    out.writeByte(ACTION);
                    out.writeInt(v.viewId);
                    out.writeInt(key);
                    out.writeByte(action);
                });
            }
        }
    }

    // ---------- every tick ----------

    public void tick() {
        tick++;
        if (modded.isEmpty()) {
            changed.clear();
            return;
        }
        for (UUID id : new ArrayList<>(modded)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                modded.remove(id);
                views.remove(id);
                continue;
            }
            Map<String, View> mine = views.computeIfAbsent(id, k -> new HashMap<>());
            Map<String, int[]> want = wantedViews(p);
            // close views that no longer apply
            Iterator<Map.Entry<String, View>> it = mine.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, View> e = it.next();
                if (!want.containsKey(e.getKey())) {
                    int vid = e.getValue().viewId;
                    send(p, out -> {
                        out.writeByte(CLOSE);
                        out.writeInt(vid);
                    });
                    it.remove();
                }
            }
            // open new ones
            for (Map.Entry<String, int[]> e : want.entrySet()) {
                if (!mine.containsKey(e.getKey())) {
                    View v = open(p, e.getKey().startsWith("in:"), e.getValue());
                    if (v != null) mine.put(e.getKey(), v);
                }
            }
            for (View v : mine.values()) {
                sendBlockChanges(p, v);
                sendEntities(p, v);
            }
        }
        changed.clear();
    }

    /** key -> {pocketId, base} */
    private Map<String, int[]> wantedViews(Player p) {
        Map<String, int[]> want = new HashMap<>();
        World pw = plugin.pocketWorld();
        if (p.getWorld().equals(pw)) {
            int id = plugin.travel().insideId(p);
            if (id < 0) return want;
            PocketStore.BlockKey k = plugin.store().placement(id);
            Integer base = plugin.renderer().baseY(id);
            if (k == null || base == null) return want;
            World ow = Bukkit.getWorld(k.world());
            if (ow == null || !ow.isChunkLoaded(k.x() >> 4, k.z() >> 4)) return want;
            want.put("in:" + id, new int[]{id, base});
            return want;
        }
        Location me = p.getLocation();
        for (Map.Entry<Integer, PocketStore.BlockKey> e : plugin.store().allPlaced().entrySet()) {
            PocketStore.BlockKey k = e.getValue();
            if (!k.world().equals(p.getWorld().getUID())) continue;
            double dx = k.x() + 0.5 - me.getX(), dy = k.y() - me.getY(), dz = k.z() + 0.5 - me.getZ();
            if (dx * dx + dy * dy + dz * dz > OUTSIDE_VIEW_DISTANCE * OUTSIDE_VIEW_DISTANCE) continue;
            Integer base = plugin.renderer().baseY(e.getKey());
            int id = e.getKey();
            if (base == null || !pw.isChunkLoaded(PocketGrid.chunkX(id), PocketGrid.chunkZ(id))) continue;
            want.put("out:" + id, new int[]{id, base});
        }
        return want;
    }

    private View open(Player p, boolean insideOut, int[] spec) {
        int id = spec[0], base = spec[1];
        PocketStore.BlockKey k = plugin.store().placement(id);
        if (k == null) return null;
        int r = ViewBridge.RADIUS, below = ViewBridge.BELOW, above = ViewBridge.ABOVE;
        View v;
        double ox, oy, oz;
        float scale;
        World src;
        if (insideOut) {
            src = Bukkit.getWorld(k.world());
            v = new View(nextViewId++, id, true, k.world(),
                    k.x() - r, k.y() - below, k.z() - r, 2 * r + 1, below + above + 1, 2 * r + 1);
            // outside block (x, y, z) -> pocket position, same mapping as the giant copies
            ox = PocketGrid.minX(id) + (v.sx0 - k.x()) * 16.0;
            oy = base + (v.sy0 - (k.y() + 1)) * 16.0;
            oz = PocketGrid.minZ(id) + (v.sz0 - k.z()) * 16.0;
            scale = 16f;
        } else {
            src = plugin.pocketWorld();
            v = new View(nextViewId++, id, false, src.getUID(),
                    PocketGrid.minX(id), base, PocketGrid.minZ(id), 16, OUT_HEIGHT, 16);
            ox = k.x();
            oy = k.y() + 1;
            oz = k.z();
            scale = 1f / 16f;
        }
        if (src == null) return null;
        // palette + one index per block, x fastest, then z, then y
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        palette.add("minecraft:air");
        index.put("minecraft:air", 0);
        int[] cells = new int[v.w * v.h * v.d];
        int i = 0;
        for (int y = 0; y < v.h; y++) {
            for (int z = 0; z < v.d; z++) {
                for (int x = 0; x < v.w; x++) {
                    String s = stateAt(v, src, k, v.sx0 + x, v.sy0 + y, v.sz0 + z);
                    Integer n = index.get(s);
                    if (n == null) {
                        n = palette.size();
                        palette.add(s);
                        index.put(s, n);
                    }
                    cells[i++] = n;
                }
            }
        }
        final View fv = v;
        final double fox = ox, foy = oy, foz = oz;
        final float fscale = scale;
        boolean ok = send(p, out -> {
            out.writeByte(OPEN);
            out.writeInt(PROTOCOL_VERSION);
            out.writeInt(fv.viewId);
            out.writeBoolean(fv.insideOut);
            out.writeInt(fv.sx0);
            out.writeInt(fv.sy0);
            out.writeInt(fv.sz0);
            out.writeInt(fv.w);
            out.writeInt(fv.h);
            out.writeInt(fv.d);
            out.writeDouble(fox);
            out.writeDouble(foy);
            out.writeDouble(foz);
            out.writeFloat(fscale);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream z = new DataOutputStream(new DeflaterOutputStream(raw))) {
                z.writeInt(palette.size());
                for (String s : palette) z.writeUTF(s);
                for (int c : cells) writeVarInt(z, c);
            }
            byte[] packed = raw.toByteArray();
            out.writeInt(packed.length);
            out.write(packed);
        });
        return ok ? v : null;
    }

    /** Block at a source position, as the client should draw it. */
    private String stateAt(View v, World src, PocketStore.BlockKey k, int x, int y, int z) {
        if (v.insideOut && x == k.x() && z == k.z() && y <= k.y()) return "minecraft:air"; // that column is the pocket
        return src.getBlockAt(x, y, z).getBlockData().getAsString();
    }

    private void sendBlockChanges(Player p, View v) {
        Set<Long> c = changed.get(v.srcWorld);
        if (c == null || c.isEmpty()) return;
        World src = Bukkit.getWorld(v.srcWorld);
        PocketStore.BlockKey k = plugin.store().placement(v.pocketId);
        if (src == null || k == null) return;
        List<int[]> hits = new ArrayList<>();
        for (long l : c) {
            int x = unX(l), y = unY(l), z = unZ(l);
            if (v.contains(v.srcWorld, x, y, z)) hits.add(new int[]{x, y, z});
        }
        if (hits.isEmpty()) return;
        // events fire before the change lands, so read the blocks now, a tick later
        send(p, out -> {
            out.writeByte(BLOCKS);
            out.writeInt(v.viewId);
            out.writeInt(hits.size());
            for (int[] h : hits) {
                out.writeShort(h[0] - v.sx0);
                out.writeShort(h[1] - v.sy0);
                out.writeShort(h[2] - v.sz0);
                out.writeUTF(stateAt(v, src, k, h[0], h[1], h[2]));
            }
        });
    }

    private void sendEntities(Player p, View v) {
        World src = Bukkit.getWorld(v.srcWorld);
        if (src == null) return;
        Set<UUID> seen = new HashSet<>();
        for (LivingEntity le : sourceEntities(v, src)) {
            UUID uid = le.getUniqueId();
            seen.add(uid);
            Integer key = v.entityKeys.get(uid);
            if (key == null) {
                int nk = nextEntityKey++;
                if (!sendAdd(p, v, nk, le)) continue;
                v.entityKeys.put(uid, nk);
                key = nk;
            }
            sendState(p, v, key, le);
            if (le instanceof Player pl) sendGear(p, v, key, pl);
        }
        Iterator<Map.Entry<UUID, Integer>> it = v.entityKeys.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            if (seen.contains(e.getKey())) continue;
            Entity real = Bukkit.getEntity(e.getKey());
            boolean died = real instanceof LivingEntity l && (l.isDead() || l.getHealth() <= 0);
            int key = e.getValue();
            send(p, out -> {
                out.writeByte(REMOVE);
                out.writeInt(v.viewId);
                out.writeInt(key);
                out.writeBoolean(died);
            });
            v.lastState.remove(e.getKey());
            v.lastGear.remove(e.getKey());
            it.remove();
        }
    }

    private List<LivingEntity> sourceEntities(View v, World src) {
        List<LivingEntity> out = new ArrayList<>();
        if (v.insideOut) {
            Location c = new Location(src, v.sx0 + v.w / 2.0, v.sy0 + v.h / 2.0, v.sz0 + v.d / 2.0);
            for (Entity e : src.getNearbyEntities(c, v.w / 2.0 + 1, v.h / 2.0 + 1, v.d / 2.0 + 1)) {
                if (!(e instanceof LivingEntity le) || le.isDead() || isCopyOrModel(e)) continue;
                if (e instanceof Player pl && plugin.travel().isBusy(pl)) continue;
                if (e.getLocation().getY() < v.sy0) continue;
                out.add(le);
            }
        } else {
            if (!src.isChunkLoaded(PocketGrid.chunkX(v.pocketId), PocketGrid.chunkZ(v.pocketId))) return out;
            Chunk ch = src.getChunkAt(PocketGrid.chunkX(v.pocketId), PocketGrid.chunkZ(v.pocketId));
            for (Entity e : ch.getEntities()) {
                if (!(e instanceof LivingEntity le) || le.isDead() || isCopyOrModel(e)) continue;
                Location l = e.getLocation();
                if (l.getY() < v.sy0 || l.getX() < v.sx0 || l.getX() >= v.sx0 + 16 || l.getZ() < v.sz0 || l.getZ() >= v.sz0 + 16) continue;
                out.add(le);
            }
        }
        return out;
    }

    private boolean sendAdd(Player viewer, View v, int key, LivingEntity le) {
        byte[] nbt = null;
        if (!(le instanceof Player)) {
            try {
                nbt = Bukkit.getUnsafe().serializeEntity(le);
            } catch (RuntimeException ex) {
                return false;
            }
        }
        byte[] fnbt = nbt;
        return send(viewer, out -> {
            out.writeByte(ADD);
            out.writeInt(v.viewId);
            out.writeInt(key);
            if (le instanceof Player pl) {
                out.writeByte(1);
                out.writeLong(pl.getUniqueId().getMostSignificantBits());
                out.writeLong(pl.getUniqueId().getLeastSignificantBits());
                out.writeUTF(pl.getName());
            } else {
                out.writeByte(0);
                out.writeUTF(le.getType().getKey().toString());
                out.writeInt(fnbt.length);
                out.write(fnbt);
            }
        });
    }

    private void sendState(Player viewer, View v, int key, LivingEntity le) {
        Location l = le.getLocation();
        int flags = 0;
        if (le.getFireTicks() > 0 || le.isVisualFire()) flags |= 1;
        if (le instanceof Player pl && pl.isSneaking()) flags |= 2;
        if (le instanceof Player pl && pl.isSprinting()) flags |= 4;
        if (le.isInvisible()) flags |= 8;
        if (le.isHandRaised()) flags |= 16;
        if (le.isHandRaised() && le.getActiveItemHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) flags |= 32;
        if (le instanceof Sittable s && s.isSitting()) flags |= 64;
        String pose = le.getPose().name();
        String sig = String.format("%.4f,%.4f,%.4f,%.1f,%.1f,%.1f,%.1f,%s,%d",
                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), le.getBodyYaw(), headYaw(le), pose, flags);
        if (sig.equals(v.lastState.get(le.getUniqueId()))) return;
        v.lastState.put(le.getUniqueId(), sig);
        int f = flags;
        send(viewer, out -> {
            out.writeByte(STATE);
            out.writeInt(v.viewId);
            out.writeInt(key);
            out.writeDouble(l.getX());
            out.writeDouble(l.getY());
            out.writeDouble(l.getZ());
            out.writeFloat(l.getYaw());
            out.writeFloat(l.getPitch());
            out.writeFloat(headYaw(le));
            out.writeFloat(le.getBodyYaw());
            out.writeUTF(pose);
            out.writeByte(f);
        });
    }

    private static float headYaw(LivingEntity le) {
        // Bukkit has no getter for a mob's head yaw; the entity's yaw is the look direction for
        // players and the facing direction for mobs, which is what the client uses as a fallback.
        return le.getLocation().getYaw();
    }

    private void sendGear(Player viewer, View v, int key, Player src) {
        if (tick % 5 != 0 && v.lastGear.containsKey(src.getUniqueId())) return;
        EntityEquipment eq = src.getEquipment();
        ItemStack[] items = {eq.getItemInMainHand(), eq.getItemInOffHand(), eq.getHelmet(), eq.getChestplate(), eq.getLeggings(), eq.getBoots()};
        StringBuilder sb = new StringBuilder();
        for (ItemStack it : items) sb.append(itemId(it)).append(';');
        String sig = sb.toString();
        if (sig.equals(v.lastGear.get(src.getUniqueId()))) return;
        v.lastGear.put(src.getUniqueId(), sig);
        send(viewer, out -> {
            out.writeByte(EQUIP);
            out.writeInt(v.viewId);
            out.writeInt(key);
            for (ItemStack it : items) out.writeUTF(itemId(it));
        });
    }

    private static String itemId(ItemStack it) {
        return it == null || it.getType().isAir() ? "" : it.getType().getKey().toString();
    }

    // ---------- plumbing ----------

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private boolean send(Player p, Writer w) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buf)) {
                w.write(out);
            }
            byte[] data = buf.toByteArray();
            if (data.length > 1_000_000) return false;
            p.sendPluginMessage(plugin, CHANNEL, data);
            return true;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Could not send live view data to " + p.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    private static void writeVarInt(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unX(long p) {
        return (int) (p >> 38);
    }

    private static int unY(long p) {
        return (int) (p << 52 >> 52);
    }

    private static int unZ(long p) {
        return (int) (p << 26 >> 38);
    }
}

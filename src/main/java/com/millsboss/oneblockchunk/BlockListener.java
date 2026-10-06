package com.millsboss.oneblockchunk;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Placing / breaking / protecting the OneBlock Chunk, and watching pockets for changes. */
public final class BlockListener implements Listener {

    private final OneBlockChunkPlugin plugin;

    public BlockListener(OneBlockChunkPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- placing & breaking the OneBlock Chunk ----

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getWorld().equals(plugin.pocketWorld())) {
            onPocketPlace(e);
            return;
        }
        ItemStack item = e.getItemInHand();
        if (!plugin.items().isOneBlock(item)) return;
        int id = plugin.items().pocketId(item);
        if (id >= 0 && plugin.store().placement(id) != null) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("That OneBlock Chunk is already placed somewhere else.", NamedTextColor.RED));
            return;
        }
        if (id < 0 || !plugin.store().isKnownId(id)) id = plugin.store().newId();
        plugin.store().place(id, b);
        plugin.renderer().render(id);
        e.getPlayer().sendActionBar(Component.text("OneBlock Chunk #" + id + " placed. Jump on it to go inside!", NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getWorld().equals(plugin.pocketWorld())) {
            if (!insideOwnPocket(e.getPlayer(), b)) {
                e.setCancelled(true);
                return;
            }
            dirtyAt(b);
            return;
        }
        Integer id = plugin.store().idAt(b);
        if (id == null) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        // Always drop it (even in creative) so the pocket and its builds are never lost.
        plugin.renderer().remove(id);
        plugin.store().remove(id);
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), plugin.items().create(id));
    }

    // ---- keep the OneBlock Chunk itself a grass block ----

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (isOneBlock(e.getBlock())) e.setCancelled(true);
        else dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (isOneBlock(e.getBlock())) e.setCancelled(true);
        else dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!isOneBlock(e.getClickedBlock())) return;
        ItemStack hand = e.getItem();
        if (hand == null) return;
        String n = hand.getType().name();
        if (n.endsWith("_HOE") || n.endsWith("_SHOVEL")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (isOneBlock(b)) { e.setCancelled(true); return; }
        if (e.getBlock().getWorld().equals(plugin.pocketWorld())) dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (isOneBlock(b)) { e.setCancelled(true); return; }
        if (e.getBlock().getWorld().equals(plugin.pocketWorld())) dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        handleExplosion(e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        handleExplosion(e.blockList());
    }

    private void handleExplosion(List<Block> blocks) {
        // OneBlock Chunks are blast-proof; pocket explosions refresh the model.
        blocks.removeIf(this::isOneBlock);
        if (!blocks.isEmpty()) dirtyAt(blocks.getFirst());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        dirtyAt(e.getBlock());
    }

    // ---- pocket world: building limits & change tracking ----

    private void onPocketPlace(BlockPlaceEvent e) {
        if (plugin.items().isOneBlock(e.getItemInHand())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("You can't put a OneBlock Chunk inside another one.", NamedTextColor.RED));
            return;
        }
        if (!insideOwnPocket(e.getPlayer(), e.getBlockPlaced())) {
            e.setCancelled(true);
            return;
        }
        dirtyAt(e.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent e) {
        dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!e.getBlock().getWorld().equals(plugin.pocketWorld())) return;
        if (!insideOwnPocket(e.getPlayer(), e.getBlock())) e.setCancelled(true);
        else dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!e.getBlock().getWorld().equals(plugin.pocketWorld())) return;
        // keep fluids from pouring into the void around the pocket
        if (PocketGrid.idForBlock(e.getToBlock().getX(), e.getToBlock().getZ())
                != PocketGrid.idForBlock(e.getBlock().getX(), e.getBlock().getZ())) {
            e.setCancelled(true);
            return;
        }
        dirtyAt(e.getToBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        dirtyAt(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTree(StructureGrowEvent e) {
        if (e.getLocation().getWorld().equals(plugin.pocketWorld())) dirtyAt(e.getLocation().getBlock());
    }

    // ---- show / hide models with the chunk they sit in ----

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (e.getWorld().equals(plugin.pocketWorld())) return;
        for (int id : plugin.store().idsInChunk(e.getWorld().getUID(), Chunks.key(e.getChunk().getX(), e.getChunk().getZ()))) {
            plugin.renderer().markDirty(id);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (e.getWorld().equals(plugin.pocketWorld())) return;
        for (int id : plugin.store().idsInChunk(e.getWorld().getUID(), Chunks.key(e.getChunk().getX(), e.getChunk().getZ()))) {
            plugin.renderer().forget(id);
        }
    }

    // ---- helpers ----

    private boolean isOneBlock(Block b) {
        return b.getType() == Material.GRASS_BLOCK && plugin.store().idAt(b) != null;
    }

    private boolean insideOwnPocket(Player p, Block b) {
        int id = plugin.travel().insideId(p);
        return id >= 0 && PocketGrid.idForBlock(b.getX(), b.getZ()) == id;
    }

    private void dirtyAt(Block b) {
        if (!b.getWorld().equals(plugin.pocketWorld())) return;
        int id = PocketGrid.idForBlock(b.getX(), b.getZ());
        if (id >= 0) plugin.renderer().markDirty(id);
    }
}

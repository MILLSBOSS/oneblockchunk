package com.millsboss.oneblockchunk;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;

public final class OneBlockChunkPlugin extends JavaPlugin implements Listener {

    public static final String POCKET_WORLD = "oneblockchunk_pockets";

    private PocketStore store;
    private OneBlockItem items;
    private MiniChunkRenderer renderer;
    private Travel travel;
    private World pocketWorld;

    @Override
    public void onEnable() {
        store = new PocketStore(getDataFolder());
        store.load();
        items = new OneBlockItem(this);
        renderer = new MiniChunkRenderer(this);
        travel = new Travel(this);

        pocketWorld = new WorldCreator(POCKET_WORLD)
                .environment(World.Environment.NORMAL)
                .generator(new PocketGenerator())
                .generateStructures(false)
                .createWorld();
        if (pocketWorld == null) {
            getLogger().severe("Could not create the pocket world; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        items.registerRecipe();

        var pm = getServer().getPluginManager();
        pm.registerEvents(new BlockListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(this, this);

        // Clean up any mini models left behind by a crash, then draw the loaded ones.
        for (World w : Bukkit.getWorlds()) {
            if (!w.equals(pocketWorld)) MiniChunkRenderer.sweepStrays(w);
        }
        store.allPlaced().keySet().forEach(renderer::markDirty);
        Bukkit.getScheduler().runTaskTimer(this, renderer::flushDirty, 20L, 20L);

        registerCommand();
        getLogger().info("OneBlockChunk enabled with " + store.allPlaced().size() + " placed chunk(s).");
    }

    @Override
    public void onDisable() {
        if (renderer != null) renderer.removeAll();
        if (store != null) store.save();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (travel != null && travel.isBusy(p)) Travel.setScale(p, 1.0);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(items.recipeKey());
    }

    private void registerCommand() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("oneblockchunk", "OneBlock Chunk commands", List.of("obc"),
                        new BasicCommand() {
                            @Override
                            public void execute(CommandSourceStack src, String[] args) {
                                handle(src.getSender(), args);
                            }

                            @Override
                            public Collection<String> suggest(CommandSourceStack src, String[] args) {
                                if (args.length <= 1) return List.of("give", "leave");
                                if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                                    return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                                }
                                return List.of();
                            }
                        }));
    }

    private void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "give" -> {
                if (!sender.hasPermission("oneblockchunk.admin")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return;
                }
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                        : sender instanceof Player pl ? pl : null;
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return;
                }
                target.getInventory().addItem(items.create(-1));
                sender.sendMessage(Component.text("Gave a OneBlock Chunk to " + target.getName() + ".", NamedTextColor.GREEN));
            }
            case "leave" -> {
                if (sender instanceof Player p && travel.insideId(p) >= 0) {
                    travel.exit(p, 0, 0);
                } else {
                    sender.sendMessage(Component.text("You're not inside a OneBlock Chunk.", NamedTextColor.RED));
                }
            }
            default -> sender.sendMessage(Component.text(
                    "/oneblockchunk give [player] | /oneblockchunk leave", NamedTextColor.YELLOW));
        }
    }

    public PocketStore store() {
        return store;
    }

    public OneBlockItem items() {
        return items;
    }

    public MiniChunkRenderer renderer() {
        return renderer;
    }

    public Travel travel() {
        return travel;
    }

    public World pocketWorld() {
        return pocketWorld;
    }
}

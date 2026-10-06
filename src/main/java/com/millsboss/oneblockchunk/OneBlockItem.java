package com.millsboss.oneblockchunk;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** The OneBlock Chunk item: a tagged grass block. Its tag carries the pocket id once placed. */
public final class OneBlockItem {

    private final NamespacedKey itemKey;
    private final NamespacedKey pocketKey;
    private final NamespacedKey recipeKey;

    public OneBlockItem(Plugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "oneblock_chunk");
        this.pocketKey = new NamespacedKey(plugin, "pocket_id");
        this.recipeKey = new NamespacedKey(plugin, "oneblock_chunk");
    }

    /** @param pocketId the pocket this item holds, or -1 for a fresh (empty) one. */
    public ItemStack create(int pocketId) {
        ItemStack item = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("OneBlock Chunk", NamedTextColor.GREEN));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("A whole chunk in a single block.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Place it, then jump on it to shrink inside.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (pocketId >= 0) {
            lore.add(Component.text("Chunk #" + pocketId + " (keeps your builds)", NamedTextColor.DARK_GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(pocketId >= 0 ? 1 : 64);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.BOOLEAN, true);
        if (pocketId >= 0) pdc.set(pocketKey, PersistentDataType.INTEGER, pocketId);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isOneBlock(ItemStack item) {
        if (item == null || item.getType() != Material.GRASS_BLOCK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BOOLEAN);
    }

    /** @return the pocket id carried by the item, or -1 if it is fresh. */
    public int pocketId(ItemStack item) {
        Integer id = item.getItemMeta().getPersistentDataContainer().get(pocketKey, PersistentDataType.INTEGER);
        return id == null ? -1 : id;
    }

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, create(-1));
        // Top: dirt, grass, stone. Middle: any log, empty, water bucket. Bottom: 3 deepslate.
        recipe.shape("DGS", "L W", "PPP");
        recipe.setIngredient('D', Material.DIRT);
        // Exact plain grass, so a OneBlock Chunk can't be eaten as an ingredient.
        recipe.setIngredient('G', new RecipeChoice.ExactChoice(new ItemStack(Material.GRASS_BLOCK)));
        recipe.setIngredient('S', Material.STONE);
        recipe.setIngredient('L', new RecipeChoice.MaterialChoice(Tag.LOGS));
        recipe.setIngredient('W', Material.WATER_BUCKET);
        recipe.setIngredient('P', Material.DEEPSLATE);
        Bukkit.addRecipe(recipe, true);
    }

    public NamespacedKey recipeKey() {
        return recipeKey;
    }
}

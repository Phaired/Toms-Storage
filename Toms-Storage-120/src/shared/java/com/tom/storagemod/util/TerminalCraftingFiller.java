package com.tom.storagemod.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

import com.tom.storagemod.platform.Platform;
import com.tom.storagemod.tile.CraftingTerminalBlockEntity;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public class TerminalCraftingFiller {
	private CraftingTerminalBlockEntity te;
	private Player player;
	private Int2ObjectMap<List<ItemStack>> allItems = new Int2ObjectOpenHashMap<>();
	private TerminalSyncManager sync;

	// Cache with WeakHashMap to allow garbage collection of unused recipes
	private static final Map<Recipe<?>, Map<StoredItemStack, Integer>> recipeIngredientCache = new WeakHashMap<>(100);

	// Counter to track consecutive crafts of the same recipe for batch optimizations
	private static Recipe<?> lastRecipe = null;
	private static int consecutiveCrafts = 0;
	private static final int BATCH_THRESHOLD = 5; // Start batch processing after this many consecutive crafts
	private static Map<StoredItemStack, StoredItemStack> batchPullBuffer = null;

	// Stats tracking to optimize cache management
	private static long lastCacheClear = System.currentTimeMillis();
	private static final long CACHE_CLEAR_INTERVAL = 300000; // 5 minutes

	public TerminalCraftingFiller(CraftingTerminalBlockEntity te, Player player, TerminalSyncManager sync) {
		this.te = te;
		this.player = player;
		this.sync = sync;
	}

	public void placeRecipe(Recipe<?> recipe) {
		 // Periodic cache maintenance to prevent memory leaks
		long currentTime = System.currentTimeMillis();
		if (currentTime - lastCacheClear > CACHE_CLEAR_INTERVAL) {
			recipeIngredientCache.clear();
			batchPullBuffer = null;
			lastRecipe = null;
			consecutiveCrafts = 0;
			lastCacheClear = currentTime;
		}

		// Track consecutive crafts of the same recipe
		if (recipe.equals(lastRecipe)) {
			consecutiveCrafts++;
		} else {
			lastRecipe = recipe;
			consecutiveCrafts = 0;
			batchPullBuffer = null;
		}

		// Clear crafting grid
		te.clear(player);

		// Load inventory items only once - loading on demand improves performance
		if (allItems.isEmpty()) {
			sync.fillCraftingFiller(this);
			for (var i : player.getInventory().items) {
				accountStack(i);
			}
		}

		// Calculate grid width
		int rw = calculateRecipeWidth(recipe);

		// Get ingredients
		var ings = recipe.getIngredients();

		// Determine whether to use batch processing
		boolean useBatchProcessing = consecutiveCrafts >= BATCH_THRESHOLD;

		// Batch extraction process
		Map<StoredItemStack, StoredItemStack> extractedItems;
		if (useBatchProcessing && batchPullBuffer != null) {
			// Use existing batch buffer
			extractedItems = batchPullBuffer;
		} else {
			// Get ingredient quantities from cache or calculate them
			Map<StoredItemStack, Integer> batchExtractions = getIngredientQuantities(recipe, ings, rw);

			// Pull items from storage
			extractedItems = new HashMap<>();
			for (var entry : batchExtractions.entrySet()) {
				StoredItemStack stack = entry.getKey();
				int count = entry.getValue();

				// For batch processing, pull extra items
				int pullCount = useBatchProcessing ? count * 10 : count;

				StoredItemStack pulled = te.pullStack(stack, pullCount);
				if (pulled != null && pulled.getQuantity() > 0) {
					extractedItems.put(stack, pulled);
				}
			}

			// Store batch buffer for future crafts
			if (useBatchProcessing) {
				batchPullBuffer = extractedItems;
			}
		}

		// Fill grid with extracted items
		fillCraftingGrid(ings, rw, extractedItems);
	}

	private int calculateRecipeWidth(Recipe<?> recipe) {
		int rw = Platform.getRecipeWidth(recipe);
		if (rw == -1) {
			if (recipe instanceof ShapedRecipe sr) {
				rw = sr.getWidth();
			} else {
				int cnt = recipe.getIngredients().size();
				if (cnt == 1) {
					rw = 1;
				} else if (cnt <= 4) {
					rw = 2;
				} else {
					rw = 3;
				}
			}
		}
		return rw;
	}

	private Map<StoredItemStack, Integer> getIngredientQuantities(Recipe<?> recipe, List<Ingredient> ings, int rw) {
		// Use cache if available
		if (recipeIngredientCache.containsKey(recipe)) {
			return recipeIngredientCache.get(recipe);
		}

		// Calculate ingredients needed
		Map<StoredItemStack, Integer> batchExtractions = new HashMap<>();

		for (int i = 0; i < ings.size(); i++) {
			Ingredient ingr = ings.get(i);
			if (ingr.isEmpty()) continue;

			boolean found = false;
			for (int v : ingr.getStackingIds()) {
				var lst = allItems.get(v);
				if (lst != null) {
					for (var item : lst) {
						if (ingr.test(item)) {
							StoredItemStack storedStack = new StoredItemStack(item);
							batchExtractions.put(storedStack, batchExtractions.getOrDefault(storedStack, 0) + 1);
							found = true;
							break;
						}
					}
					if (found) break;
				}
			}
		}

		// Cache for future use
		recipeIngredientCache.put(recipe, batchExtractions);
		return batchExtractions;
	}

	private void fillCraftingGrid(List<Ingredient> ings, int rw, Map<StoredItemStack, StoredItemStack> extractedItems) {
		for (int i = 0; i < ings.size(); i++) {
			Ingredient ingr = ings.get(i);
			if (ingr.isEmpty()) continue;

			int x = i % rw;
			int y = i / rw;
			boolean filled = false;

			// Try to fill from extracted items
			for (int v : ingr.getStackingIds()) {
				var lst = allItems.get(v);
				if (lst != null) {
					for (var item : lst) {
						if (ingr.test(item)) {
							StoredItemStack storedStack = new StoredItemStack(item);
							if (extractedItems.containsKey(storedStack) && extractedItems.get(storedStack).getQuantity() > 0) {
								StoredItemStack pulled = extractedItems.get(storedStack);
								ItemStack craftItem = item.copy(); // Use item directly to avoid creating unnecessary objects
								craftItem.setCount(1);

								// Update remaining quantity using a new instance
								if (pulled.getQuantity() > 1) {
									extractedItems.put(storedStack, new StoredItemStack(pulled.getStack(), pulled.getQuantity() - 1));
								} else {
									extractedItems.remove(storedStack);
								}

								te.setCraftSlot(x, y, craftItem);
								filled = true;
								break;
							}
						}
					}
					if (filled) break;
				}
			}

			// If not filled from storage, try player inventory as fallback
			if (!filled) {
				tryFillFromPlayerInventory(ingr, x, y);
			}
		}
	}

	private boolean tryFillFromPlayerInventory(Ingredient ingr, int x, int y) {
		for (int v : ingr.getStackingIds()) {
			var lst = allItems.get(v);
			if (lst != null) {
				for (var item : lst) {
					if (ingr.test(item)) {
						int id = player.getInventory().findSlotMatchingItem(item);
						if (id != -1) {
							te.setCraftSlot(x, y, player.getInventory().removeItem(id, 1));
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	public void accountStack(ItemStack st) {
		if (st.isEmpty() || st.hasCustomHoverName()) return;
		int index = StackedContents.getStackingIndex(st);
		allItems.computeIfAbsent(index, __ -> new ArrayList<>()).add(st);
	}
}

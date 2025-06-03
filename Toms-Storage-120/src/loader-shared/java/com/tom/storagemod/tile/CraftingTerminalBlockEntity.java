package com.tom.storagemod.tile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import com.tom.storagemod.util.MultiItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;

import com.tom.storagemod.Content;
import com.tom.storagemod.StorageMod;
import com.tom.storagemod.gui.CraftingTerminalMenu;
import com.tom.storagemod.platform.PlatformRecipe;
import com.tom.storagemod.polymorph.PolymorphHelper;
import com.tom.storagemod.util.CraftingMatrix;
import com.tom.storagemod.util.StoredItemStack;

public class CraftingTerminalBlockEntity extends StorageTerminalBlockEntity {
	private PlatformRecipe currentRecipe;
	private final CraftingContainer craftMatrix = new CraftingMatrix(3, 3, () -> {
		if (level != null && !level.isClientSide) {
			onCraftingMatrixChanged();
		}
		setChanged();
	});
	private ResultContainer craftResult = new ResultContainer();
	private HashSet<CraftingTerminalMenu> craftingListeners = new HashSet<>();
	private boolean refillingGrid;
	private int craftingCooldown;
	// Cache pour les opérations de crafting répétitives
	private Map<String, ItemStack> lastItemsNeeded = new HashMap<>();
	private Map<String, Integer> lastItemsCount = new HashMap<>();
	private long lastCraftTime = 0;
	private static final long CACHE_VALIDITY_PERIOD = 1000; // 1 seconde en millisecondes

	public CraftingTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(Content.craftingTerminalTile.get(), pos, state);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory plInv, Player arg2) {
		return new CraftingTerminalMenu(id, plInv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("ts.crafting_terminal");
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		super.saveAdditional(compound);

		ListTag listnbt = new ListTag();

		for(int i = 0; i < craftMatrix.getContainerSize(); ++i) {
			ItemStack itemstack = craftMatrix.getItem(i);
			if (!itemstack.isEmpty()) {
				CompoundTag compoundnbt = new CompoundTag();
				compoundnbt.putByte("Slot", (byte)i);
				itemstack.save(compoundnbt);
				listnbt.add(compoundnbt);
			}
		}

		compound.put("CraftingTable", listnbt);
	}

	private boolean reading;
	@Override
	public void load(CompoundTag compound) {
		super.load(compound);
		reading = true;
		ListTag listnbt = compound.getList("CraftingTable", 10);

		for(int i = 0; i < listnbt.size(); ++i) {
			CompoundTag compoundnbt = listnbt.getCompound(i);
			int j = compoundnbt.getByte("Slot") & 255;
			if (j >= 0 && j < craftMatrix.getContainerSize()) {
				craftMatrix.setItem(j, ItemStack.of(compoundnbt));
			}
		}
		reading = false;
	}

	public CraftingContainer getCraftingInv() {
		return craftMatrix;
	}

	public ResultContainer getCraftResult() {
		return craftResult;
	}

       public void craft(Player thePlayer) {
               if(currentRecipe != null) {
                       MultiItemHandler.enableBatchMode();
                       try {
				long currentTime = System.currentTimeMillis();
				boolean useCache = currentTime - lastCraftTime < CACHE_VALIDITY_PERIOD;
				lastCraftTime = currentTime;

				// Pre-calculate what we need
				NonNullList<ItemStack> remainder = currentRecipe.getRemainingItems(craftMatrix);
				boolean playerInvUpdate = false;
				refillingGrid = true;

				// Groupe les items identiques pour réduire le nombre d'appels à pullStack
				Map<String, Integer> neededItems = new HashMap<>();
				Map<String, ItemStack> itemTemplates = new HashMap<>();

				// 1. Collecter les besoins
				for (int i = 0; i < craftMatrix.getContainerSize(); ++i) {
					ItemStack slot = craftMatrix.getItem(i);
					if (!slot.isEmpty()) {
						ItemStack oldItem = slot.copy();
						String key = getItemKey(oldItem);
						neededItems.put(key, neededItems.getOrDefault(key, 0) + 1);
						itemTemplates.put(key, oldItem.copy());
					}
				}

				// Si les besoins sont identiques à la dernière opération, utiliser le cache
				boolean needsAreSame = useCache && neededItems.equals(lastItemsCount);

				// Mise à jour du cache
				if (!needsAreSame) {
					lastItemsNeeded.clear();
					lastItemsCount.clear();
					lastItemsNeeded.putAll(itemTemplates);
					lastItemsCount.putAll(neededItems);
				}

				// 2. Extraction optimisée des items nécessaires
				Map<String, List<ItemStack>> extractedItems = new HashMap<>();
				for (Map.Entry<String, Integer> entry : neededItems.entrySet()) {
					String key = entry.getKey();
					int count = entry.getValue();
					ItemStack template = itemTemplates.get(key);

						// Extraction optimisée en lot
					StoredItemStack extracted = pullStack(new StoredItemStack(template), count);
					if (extracted != null && !extracted.getStack().isEmpty()) {
						List<ItemStack> singles = new ArrayList<>();
						ItemStack stack = extracted.getActualStack();
						int extractedCount = Math.min(stack.getCount(), count); // S'assurer de ne pas dépasser le compte demandé

						// Diviser en stacks individuels de façon efficace
						for (int i = 0; i < extractedCount; i++) {
							ItemStack single = template.copy();
							single.setCount(1);
							singles.add(single);
						}

						extractedItems.put(key, singles);
					} else {
						extractedItems.put(key, new ArrayList<>());
					}
				}

				// 3. Application optimisée à la grille de craft
				for (int i = 0; i < remainder.size(); ++i) {
					ItemStack slot = craftMatrix.getItem(i);
					ItemStack rem = remainder.get(i);

					if (!slot.isEmpty()) {
						String key = getItemKey(slot);
						craftMatrix.removeItem(i, 1);

						// Utiliser un item extrait si disponible
						if (extractedItems.containsKey(key) && !extractedItems.get(key).isEmpty()) {
							List<ItemStack> items = extractedItems.get(key);
							craftMatrix.setItem(i, items.remove(0));
						}
						// Sinon, essayer l'inventaire du joueur si autorisé
						else if ((getSorting() & (1 << 8)) != 0) {
							boolean found = false;
							for (int j = 0; j < thePlayer.getInventory().getContainerSize(); j++) {
								ItemStack st = thePlayer.getInventory().getItem(j);
								if (ItemStack.isSameItemSameTags(slot, st)) {
									st = thePlayer.getInventory().removeItem(j, 1);
									if (!st.isEmpty()) {
										craftMatrix.setItem(i, st);
										playerInvUpdate = true;
										found = true;
										break;
									}
								}
							}

							// Rien trouvé, on place l'item de reste s'il existe
							if (!found && !rem.isEmpty()) {
								craftMatrix.setItem(i, rem.copy());
							}
						}
					}
					// Gérer les items de reste
					else if (!rem.isEmpty()) {
						craftMatrix.setItem(i, rem.copy());
					}
				}

				refillingGrid = false;
				onCraftingMatrixChanged();
				craftingCooldown += craftResult.getItem(0).getCount();
                               if (playerInvUpdate) thePlayer.containerMenu.broadcastChanges();
                       } finally {
                               // Assurer que le flag est réinitialisé même en cas d'exception
                               refillingGrid = false;
                               MultiItemHandler.disableBatchMode();
                       }
               }
       }

	/**
	 * Génère une clé unique pour identifier un item et ses tags
	 */
	private String getItemKey(ItemStack stack) {
		if (stack.isEmpty()) return "empty";
		String key = stack.getItem().toString();
		if (stack.hasTag()) {
			key += ":" + stack.getTag().toString();
		}
		return key;
	}

	public void unregisterCrafting(CraftingTerminalMenu containerCraftingTerminal) {
		craftingListeners.remove(containerCraftingTerminal);
	}

	public void registerCrafting(CraftingTerminalMenu containerCraftingTerminal) {
		craftingListeners.add(containerCraftingTerminal);
	}

	protected void onCraftingMatrixChanged() {
		if(refillingGrid)return;
		if (currentRecipe == null || !currentRecipe.matches(craftMatrix, level)) {
			currentRecipe = getRecipe();
		}

		if (currentRecipe == null) {
			craftResult.setItem(0, ItemStack.EMPTY);
		} else {
			craftResult.setItem(0, currentRecipe.assemble(craftMatrix, level.registryAccess()));
		}

		craftingListeners.forEach(CraftingTerminalMenu::onCraftMatrixChanged);
		craftResult.setRecipeUsed(currentRecipe == null ? null : currentRecipe.recipe());

		if (!reading) {
			setChanged();
		}
	}

	private PlatformRecipe getRecipe() {
		if (StorageMod.polymorph) {
			return PlatformRecipe.of(PolymorphHelper.getRecipe(this, RecipeType.CRAFTING, craftMatrix, level).orElse(null));
		}
		return PlatformRecipe.of(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craftMatrix, level).orElse(null));
	}

	public void clear(Player player) {
		for (int i = 0; i < craftMatrix.getContainerSize(); i++) {
			ItemStack st = craftMatrix.removeItemNoUpdate(i);
			if(!st.isEmpty()) {
				StoredItemStack st0 = pushStack(new StoredItemStack(st));
				if (st0 != null) {
					var is = st0.getActualStack();
					player.getInventory().add(is);
					if (!is.isEmpty())
						dropItem(is);
				}
			}
		}
		onCraftingMatrixChanged();
	}

	@Override
	public void updateServer() {
		super.updateServer();
		craftingCooldown = 0;
	}

	public boolean canCraft() {
		return craftingCooldown + craftResult.getItem(0).getCount() <= craftResult.getItem(0).getMaxStackSize();
	}

	public void polymorphUpdate() {
		currentRecipe = null;
		onCraftingMatrixChanged();
	}

	public void setCraftSlot(int x, int y, ItemStack actualStack) {
		craftMatrix.setItem(x + y * 3, actualStack);
	}
}

package com.tom.storagemod.util;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.EmptyHandler;

import com.tom.storagemod.Config;

public class MultiItemHandler implements IItemHandler {
	private List<LazyOptional<IItemHandler>> handlers = new ArrayList<>();
	private List<ItemStack[]> dupDetector = new ArrayList<>();
	private int[] invSizes = new int[0];
	private int invSize;
	private boolean calling;

	// Système de gestion de batch pour l'optimisation des performances
	private static boolean batchModeEnabled = false;
	private static int batchOperationCounter = 0;
	private static final int BATCH_THRESHOLD = 10; // Nombre d'opérations avant notification
	private static final int BATCH_MAX_OPERATIONS = 50; // Maximum d'opérations avant reset forcé
	private static final WeakHashMap<Object, Integer> handlerAccessCount = new WeakHashMap<>();
	private static long lastResetTime = System.currentTimeMillis();
	private static final long BATCH_RESET_INTERVAL = 500; // 500ms

	/**
	 * Active le mode batch pour les opérations d'extraction d'items
	 * Ce mode réduit les mises à jour des conteneurs pendant le crafting intensif
	 */
	public static void enableBatchMode() {
		batchModeEnabled = true;
		batchOperationCounter = 0;
		handlerAccessCount.clear();
	}

	/**
	 * Désactive le mode batch et force une mise à jour des conteneurs
	 */
	public static void disableBatchMode() {
		batchModeEnabled = false;
		batchOperationCounter = 0;
		handlerAccessCount.clear();
	}

	/**
	 * Reset forcé des compteurs de batch si nécessaire
	 */
	private static void checkBatchReset() {
		long currentTime = System.currentTimeMillis();
		if (batchOperationCounter > BATCH_MAX_OPERATIONS ||
				(batchModeEnabled && currentTime - lastResetTime > BATCH_RESET_INTERVAL)) {
			batchOperationCounter = 0;
			handlerAccessCount.clear();
			lastResetTime = currentTime;
		}
	}

	/**
	 * Vérifie si l'extraction doit déclencher une mise à jour
	 * En mode batch, limite les mises à jour pour améliorer les performances
	 */
	private boolean shouldNotifyOnExtract(Object handler) {
		if (!batchModeEnabled) return true;

		checkBatchReset();

		batchOperationCounter++;
		int count = handlerAccessCount.getOrDefault(handler, 0) + 1;
		handlerAccessCount.put(handler, count);

		// Notifier périodiquement selon le nombre d'opérations sur ce handler
		return count % BATCH_THRESHOLD == 0;
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		if(calling)return false;
		if(slot >= invSize)return false;
		calling = true;
		for (int i = 0; i < invSizes.length; i++) {
			if(slot >= invSizes[i])slot -= invSizes[i];
			else {
				boolean r = handlers.get(i).orElse(EmptyHandler.INSTANCE).isItemValid(slot, stack);
				calling = false;
				return r;
			}
		}
		calling = false;
		return false;
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if(calling)return stack;
		if(slot >= invSize)return stack;
		calling = true;
		for (int i = 0; i < invSizes.length; i++) {
			if(slot >= invSizes[i])slot -= invSizes[i];
			else {
				ItemStack s = handlers.get(i).orElse(EmptyHandler.INSTANCE).insertItem(slot, stack, simulate);
				calling = false;
				return s;
			}
		}
		calling = false;
		return stack;
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		if(calling)return ItemStack.EMPTY;
		if(slot >= invSize)return ItemStack.EMPTY;
		calling = true;
		for (int i = 0; i < invSizes.length; i++) {
			if(slot >= invSizes[i])slot -= invSizes[i];
			else {
				ItemStack s = handlers.get(i).orElse(EmptyHandler.INSTANCE).getStackInSlot(slot);
				calling = false;
				return s;
			}
		}
		calling = false;
		return ItemStack.EMPTY;
	}

	@Override
	public int getSlots() {
		return invSize;
	}

	@Override
	public int getSlotLimit(int slot) {
		if(calling)return 0;
		if(slot >= invSize)return 0;
		calling = true;
		for (int i = 0; i < invSizes.length; i++) {
			if(slot >= invSizes[i])slot -= invSizes[i];
			else {
				int r = handlers.get(i).orElse(EmptyHandler.INSTANCE).getSlotLimit(slot);
				calling = false;
				return r;
			}
		}
		calling = false;
		return 0;
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		if(calling)return ItemStack.EMPTY;
		if(slot >= invSize)return ItemStack.EMPTY;
		calling = true;
		for (int i = 0; i < invSizes.length; i++) {
			if(slot >= invSizes[i])slot -= invSizes[i];
			else {
				IItemHandler handler = handlers.get(i).orElse(EmptyHandler.INSTANCE);

                               // Limiter les mises à jour en mode batch pour réduire le lag
                               if (!simulate && batchModeEnabled && !shouldNotifyOnExtract(handler)) {
                                       // On souhaite extraire sans déclencher de notification
                                       // Extraire d'abord en mode simulé pour connaitre la quantité disponible
                                       ItemStack extracted = handler.extractItem(slot, amount, true);
                                       if(!extracted.isEmpty()) {
                                               try {
                                                       // Retirer réellement les items sans notification
                                                       handler.extractItem(slot, extracted.getCount(), false);
                                               } catch (Exception e) {
                                                       // En cas d'erreur, abandonner
                                                       calling = false;
                                                       return ItemStack.EMPTY;
                                               }
                                       }
                                       calling = false;
                                       return extracted;
                               }

                               ItemStack s = handler.extractItem(slot, amount, simulate);
				calling = false;
				return s;
			}
		}
		calling = false;
		return ItemStack.EMPTY;
	}

	public List<LazyOptional<IItemHandler>> getHandlers() {
		return handlers;
	}

	public void refresh() {
		dupDetector.clear();
		if(invSizes.length != handlers.size())invSizes = new int[handlers.size()];
		invSize = 0;
		for (int i = 0; i < invSizes.length; i++) {
			IItemHandler ih = handlers.get(i).orElse(null);
			if(ih == null)invSizes[i] = 0;
			else {
				int s = ih.getSlots();
				invSizes[i] = s;
				invSize += s;
			}
		}
	}

	public boolean contains(Object o) {
		return handlers.contains(o);
	}

	public void add(LazyOptional<IItemHandler> e) {
		if(e.map(this::checkInv).orElse(false))
			handlers.add(e);
	}

	private boolean checkInv(IItemHandler h) {
		int len = Math.min(Config.get().invDupScanSize, h.getSlots());
		if(len == 0)return true;
		ItemStack[] is = new ItemStack[len];
		for(int i = 0;i<len;i++) {
			is[i] = h.getStackInSlot(i);
		}

		for (ItemStack[] st : dupDetector) {
			int l = Math.min(len, st.length);
			for (int i = 0; i < l; i++) {
				ItemStack item = st[i];
				if(!item.isEmpty() && item == is[i])
					return false;
			}
		}
		dupDetector.add(is);
		return true;
	}

	public void clear() {
		invSize = 0;
		handlers.clear();
		dupDetector.clear();
	}
}

package com.tom.storagemod.performance;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.tom.storagemod.inventory.StoredItemStack;
import com.tom.storagemod.inventory.InventorySlot;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cache optimisé pour les slots d'inventaire par type d'item
 * Réduit la complexité de O(n) à O(1) pour la recherche de slots compatibles
 */
public class SlotCache {
    
    // Cache principal : Item -> Liste des slots pouvant accepter cet item
    private final Map<Item, List<CachedSlotInfo>> itemToSlotsCache = new ConcurrentHashMap<>();
    
    // Cache des slots vides disponibles
    private final List<CachedSlotInfo> emptySlotsCache = new CopyOnWriteArrayList<>();
    
    // Timestamp de dernière invalidation pour éviter les données obsolètes
    private volatile long lastInvalidation = 0;
    private static final long CACHE_VALIDITY_MS = 100; // 100ms de validité
    
    public static class CachedSlotInfo {
        public final InventorySlot slot;
        public final int inventoryIndex;
        public final int slotIndex;
        public final long cachedAt;
        
        public CachedSlotInfo(InventorySlot slot, int inventoryIndex, int slotIndex) {
            this.slot = slot;
            this.inventoryIndex = inventoryIndex;
            this.slotIndex = slotIndex;
            this.cachedAt = System.currentTimeMillis();
        }
        
        public boolean isValid() {
            return (System.currentTimeMillis() - cachedAt) < CACHE_VALIDITY_MS;
        }
    }
    
    /**
     * Recherche optimisée de slot destination pour un item
     */
    public InventorySlot findOptimizedSlotDest(StoredItemStack forStack) {
        if (forStack == null || forStack.getStack().isEmpty()) {
            return null;
        }
        
        Item item = forStack.getStack().getItem();
        
        // 1. Chercher dans le cache des slots existants pour cet item
        List<CachedSlotInfo> cachedSlots = itemToSlotsCache.get(item);
        if (cachedSlots != null) {
            for (CachedSlotInfo cachedSlot : cachedSlots) {
                if (cachedSlot.isValid() && canAcceptItem(cachedSlot.slot, forStack)) {
                    return cachedSlot.slot;
                }
            }
        }
        
        // 2. Chercher dans les slots vides
        for (CachedSlotInfo emptySlot : emptySlotsCache) {
            if (emptySlot.isValid() && canAcceptItem(emptySlot.slot, forStack)) {
                // Déplacer vers le cache spécifique à l'item
                addToItemCache(item, emptySlot);
                emptySlotsCache.remove(emptySlot);
                return emptySlot.slot;
            }
        }
        
        return null;
    }
    
    /**
     * Met à jour le cache avec les nouveaux slots disponibles
     */
    public void updateCache(List<InventorySlot> allSlots) {
        long currentTime = System.currentTimeMillis();
        
        // Éviter les mises à jour trop fréquentes
        if (currentTime - lastInvalidation < 50) {
            return;
        }
        
        // Nettoyage des caches expirés
        cleanExpiredEntries();
        
        // Mise à jour incrémentale plutôt que reconstruction complète
        for (InventorySlot slot : allSlots) {
            updateSlotInCache(slot);
        }
        
        lastInvalidation = currentTime;
    }
    
    private void updateSlotInCache(InventorySlot slot) {
        ItemStack stack = slot.getStack();
        
        if (stack.isEmpty()) {
            // Slot vide
            CachedSlotInfo info = new CachedSlotInfo(slot, -1, -1);
            if (!emptySlotsCache.contains(info)) {
                emptySlotsCache.add(info);
            }
        } else {
            // Slot avec item
            Item item = stack.getItem();
            CachedSlotInfo info = new CachedSlotInfo(slot, -1, -1);
            addToItemCache(item, info);
        }
    }
    
    private void addToItemCache(Item item, CachedSlotInfo info) {
        itemToSlotsCache.computeIfAbsent(item, k -> new CopyOnWriteArrayList<>()).add(info);
    }
    
    private boolean canAcceptItem(InventorySlot slot, StoredItemStack forStack) {
        try {
            ItemStack testStack = forStack.getStack().copy();
            testStack.setCount(1);
            
            ItemStack result = slot.insert(testStack);
            return result.isEmpty(); // Si l'insertion réussit, le slot peut accepter l'item
        } catch (Exception e) {
            return false;
        }
    }
    
    private void cleanExpiredEntries() {
        // Nettoyage des entrées expirées
        itemToSlotsCache.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(info -> !info.isValid());
            return entry.getValue().isEmpty();
        });
        
        emptySlotsCache.removeIf(info -> !info.isValid());
    }
    
    /**
     * Invalide complètement le cache (à appeler lors de changements majeurs d'inventaire)
     */
    public void invalidateAll() {
        itemToSlotsCache.clear();
        emptySlotsCache.clear();
        lastInvalidation = System.currentTimeMillis();
    }
    
    /**
     * Invalide le cache pour un item spécifique
     */
    public void invalidateItem(Item item) {
        itemToSlotsCache.remove(item);
    }
}

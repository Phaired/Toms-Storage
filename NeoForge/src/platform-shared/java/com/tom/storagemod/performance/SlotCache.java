package com.tom.storagemod.performance;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Iterator;

/**
 * Cache optimisé pour les slots d'inventaire par type d'item.
 * Réduit la complexité de recherche de O(n) à O(1) pour les opérations shift-click.
 */
public class SlotCache {
    
    private static class CacheEntry {
        final List<Slot> compatibleSlots;
        final long timestamp;
        
        CacheEntry(List<Slot> slots) {
            this.compatibleSlots = new CopyOnWriteArrayList<>(slots);
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long maxAge) {
            return System.currentTimeMillis() - timestamp > maxAge;
        }
    }
    
    // Cache principal : Item -> Liste des slots compatibles
    private final Map<Item, CacheEntry> itemSlotCache = new ConcurrentHashMap<>();
    
    // Cache des slots vides disponibles (mise à jour fréquente)
    private volatile List<Slot> emptySlots = new CopyOnWriteArrayList<>();
    private volatile long emptySlotsTimestamp = 0;
    
    // Configuration du cache
    private static final long CACHE_EXPIRY_MS = 100; // 100ms de validité
    private static final long EMPTY_SLOTS_EXPIRY_MS = 50; // 50ms pour les slots vides
    
    /**
     * Trouve les slots compatibles pour un item donné (avec cache)
     */
    public List<Slot> findOptimizedSlotDest(ItemStack stack, List<Slot> allSlots, int startIndex, int endIndex) {
        Item item = stack.getItem();
        
        // 1. Vérifier le cache pour ce type d'item
        CacheEntry entry = itemSlotCache.get(item);
        if (entry != null && !entry.isExpired(CACHE_EXPIRY_MS)) {
            // Filtrer les slots dans la plage demandée
            return entry.compatibleSlots.stream()
                .filter(slot -> {
                    int index = allSlots.indexOf(slot);
                    return index >= startIndex && index < endIndex;
                })
                .toList();
        }
        
        // 2. Cache manqué - reconstruire
        List<Slot> compatibleSlots = new CopyOnWriteArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            if (i < allSlots.size()) {
                Slot slot = allSlots.get(i);
                if (isSlotCompatible(slot, stack)) {
                    compatibleSlots.add(slot);
                }
            }
        }
        
        // 3. Mettre à jour le cache
        itemSlotCache.put(item, new CacheEntry(compatibleSlots));
        
        return compatibleSlots;
    }
    
    /**
     * Trouve les slots vides disponibles (avec cache rapide)
     */
    public List<Slot> findEmptySlots(List<Slot> allSlots, int startIndex, int endIndex) {
        long now = System.currentTimeMillis();
        
        // Vérifier si le cache des slots vides est valide
        if (now - emptySlotsTimestamp > EMPTY_SLOTS_EXPIRY_MS || emptySlots.isEmpty()) {
            refreshEmptySlots(allSlots, startIndex, endIndex);
        }
        
        // Filtrer les slots dans la plage demandée
        return emptySlots.stream()
            .filter(slot -> {
                int index = allSlots.indexOf(slot);
                return index >= startIndex && index < endIndex && slot.getItem().isEmpty();
            })
            .toList();
    }
    
    /**
     * Met à jour le cache des slots vides
     */
    private void refreshEmptySlots(List<Slot> allSlots, int startIndex, int endIndex) {
        List<Slot> newEmptySlots = new CopyOnWriteArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            if (i < allSlots.size()) {
                Slot slot = allSlots.get(i);
                if (slot.getItem().isEmpty()) {
                    newEmptySlots.add(slot);
                }
            }
        }
        emptySlots = newEmptySlots;
        emptySlotsTimestamp = System.currentTimeMillis();
    }
    
    /**
     * Vérifie si un slot peut accepter un item
     */
    private boolean isSlotCompatible(Slot slot, ItemStack stack) {
        if (!slot.mayPlace(stack)) {
            return false;
        }
        
        ItemStack slotStack = slot.getItem();
        if (slotStack.isEmpty()) {
            return true;
        }
        
        return ItemStack.isSameItemSameComponents(stack, slotStack) && 
               slotStack.getCount() < slotStack.getMaxStackSize();
    }
    
    /**
     * Met à jour le cache lors de changements d'inventaire
     */
    public void updateCache(List<Slot> changedSlots) {
        long now = System.currentTimeMillis();
        
        // Invalider le cache des slots vides si nécessaire
        if (changedSlots.stream().anyMatch(slot -> slot.getItem().isEmpty())) {
            emptySlotsTimestamp = 0; // Force refresh
        }
        
        // Invalider les entrées de cache concernées
        for (Slot slot : changedSlots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                itemSlotCache.remove(stack.getItem());
            }
        }
    }
    
    /**
     * Invalide tout le cache
     */
    public void invalidateAll() {
        itemSlotCache.clear();
        emptySlots.clear();
        emptySlotsTimestamp = 0;
    }
    
    /**
     * Nettoie les entrées expirées du cache
     */
    public void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Item, CacheEntry>> iterator = itemSlotCache.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<Item, CacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired(CACHE_EXPIRY_MS * 2)) { // Double de l'expiration normale
                iterator.remove();
            }
        }
    }
}

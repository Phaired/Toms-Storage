package com.tom.storagemod.performance;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.tom.storagemod.menu.StorageTerminalMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Version optimisée de moveItemStackTo pour réduire le lag lors du shift-click
 * Utilise le batching et la mise en cache pour améliorer les performances
 */
public class OptimizedShiftClickHandler {
    
    private final StorageTerminalMenu menu;
    private final SlotCache slotCache;
    
    // Cache des slots compatibles par type d'item pour éviter les recherches répétées
    private final List<Slot> cachedCompatibleSlots = new ArrayList<>();
    private ItemStack lastCachedItem = ItemStack.EMPTY;
    
    public OptimizedShiftClickHandler(StorageTerminalMenu menu) {
        this.menu = menu;
        this.slotCache = new SlotCache();
    }
    
    /**
     * Version optimisée de moveItemStackTo avec batching et cache
     */
    public boolean optimizedMoveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        if (stack.isEmpty()) {
            return false;
        }
        
        // 1. Vérifier le cache des slots compatibles
        if (!ItemStack.isSameItemSameComponents(lastCachedItem, stack)) {
            refreshCompatibleSlotsCache(stack, startIndex, endIndex, reverseDirection);
        }
        
        // 2. Essayer d'abord les slots avec le même item (plus efficace)
        int moved = tryMoveToExistingStacks(stack);
        if (stack.isEmpty()) {
            return moved > 0;
        }
        
        // 3. Ensuite les slots vides
        moved += tryMoveToEmptySlots(stack);
        
        return moved > 0;
    }
    
    /**
     * Met à jour le cache des slots compatibles pour un type d'item donné
     */
    private void refreshCompatibleSlotsCache(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        cachedCompatibleSlots.clear();
        lastCachedItem = stack.copy();
        lastCachedItem.setCount(1);
        
        // Parcours optimisé : priorité aux slots proches du début pour réduire la fragmentation
        if (reverseDirection) {
            for (int i = endIndex - 1; i >= startIndex; i--) {
                Slot slot = menu.slots.get(i);
                if (isSlotCompatible(slot, stack)) {
                    cachedCompatibleSlots.add(slot);
                }
            }
        } else {
            for (int i = startIndex; i < endIndex; i++) {
                Slot slot = menu.slots.get(i);
                if (isSlotCompatible(slot, stack)) {
                    cachedCompatibleSlots.add(slot);
                }
            }
        }
    }
    
    /**
     * Essaie de déplacer vers les slots contenant déjà le même item
     */
    private int tryMoveToExistingStacks(ItemStack stack) {
        int totalMoved = 0;
        
        for (Slot slot : cachedCompatibleSlots) {
            if (stack.isEmpty()) break;
            
            ItemStack slotStack = slot.getItem();
            if (!slotStack.isEmpty() && ItemStack.isSameItemSameComponents(stack, slotStack)) {
                int toMove = Math.min(stack.getCount(), slotStack.getMaxStackSize() - slotStack.getCount());
                if (toMove > 0) {
                    slotStack.grow(toMove);
                    stack.shrink(toMove);
                    totalMoved += toMove;
                    slot.setChanged();
                }
            }
        }
        
        return totalMoved;
    }
    
    /**
     * Essaie de déplacer vers les slots vides
     */
    private int tryMoveToEmptySlots(ItemStack stack) {
        int totalMoved = 0;
        
        for (Slot slot : cachedCompatibleSlots) {
            if (stack.isEmpty()) break;
            
            if (slot.getItem().isEmpty()) {
                int toMove = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack newStack = stack.split(toMove);
                slot.set(newStack);
                totalMoved += toMove;
                slot.setChanged();
            }
        }
        
        return totalMoved;
    }
    
    /**
     * Vérifie si un slot peut accepter un item (avec cache pour éviter les appels répétés)
     */
    private boolean isSlotCompatible(Slot slot, ItemStack stack) {
        // Vérifications rapides d'abord
        if (!slot.mayPlace(stack)) {
            return false;
        }
        
        ItemStack slotStack = slot.getItem();
        if (slotStack.isEmpty()) {
            return true;
        }
        
        // Vérifier si on peut combiner les stacks
        return ItemStack.isSameItemSameComponents(stack, slotStack) && 
               slotStack.getCount() < slotStack.getMaxStackSize();
    }
    
    /**
     * Traitement optimisé du shift-click avec priorités
     */
    public void handleOptimizedShiftClick(Player player, int slotIndex) {
        Slot clickedSlot = menu.slots.get(slotIndex);
        if (clickedSlot == null || !clickedSlot.hasItem()) {
            return;
        }
        
        ItemStack slotStack = clickedSlot.getItem();
        ItemStack originalStack = slotStack.copy();
        
        // Déterminer la direction de transfert
        boolean isPlayerSlot = slotIndex > menu.playerSlotsStart;
        
        if (isPlayerSlot) {
            // Du joueur vers le stockage - utiliser l'optimisation du terminal
            optimizedMoveToStorage(slotStack, clickedSlot);
        } else {
            // Du stockage vers le joueur - utiliser moveItemStackTo optimisé
            optimizedMoveItemStackTo(slotStack, menu.playerSlotsStart + 1, menu.slots.size(), true);
        }
        
        // Nettoyer le slot si vide
        if (slotStack.isEmpty()) {
            clickedSlot.set(ItemStack.EMPTY);
        }
        
        // Marquer comme changé seulement si nécessaire
        if (!ItemStack.matches(originalStack, slotStack)) {
            clickedSlot.setChanged();
            player.getInventory().setChanged();
        }
    }
    
    /**
     * Optimisation spéciale pour le transfert vers le stockage du terminal
     */
    private void optimizedMoveToStorage(ItemStack stack, Slot sourceSlot) {
        if (menu.te != null) {
            // Utiliser directement l'API du terminal qui est optimisée
            com.tom.storagemod.inventory.StoredItemStack storedStack = 
                new com.tom.storagemod.inventory.StoredItemStack(stack, stack.getCount());
            
            com.tom.storagemod.inventory.StoredItemStack remainder = menu.te.pushStack(storedStack);
            
            if (remainder == null) {
                sourceSlot.set(ItemStack.EMPTY);
            } else {
                sourceSlot.set(remainder.getActualStack());
            }
        }
    }
    
    /**
     * Invalide les caches lors de changements d'inventaire
     */
    public void invalidateCache() {
        cachedCompatibleSlots.clear();
        lastCachedItem = ItemStack.EMPTY;
        slotCache.invalidateAll();
    }
}

package com.tom.storagemod.performance;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.SimpleContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests de performance pour valider les optimisations du shift-click
 */
public class PerformanceTest {
    
    public static void runBasicCacheTest() {
        SlotCache cache = new SlotCache();
        
        // Créer une liste de slots de test
        List<Slot> testSlots = new ArrayList<>();
        SimpleContainer container = new SimpleContainer(1000);
        
        for (int i = 0; i < 1000; i++) {
            testSlots.add(new Slot(container, i, 0, 0));
        }
        
        // Test de performance : recherche répétée
        ItemStack testItem = new ItemStack(Items.DIAMOND, 1);
        
        long startTime = System.nanoTime();
        
        // Simuler 1000 recherches de slots compatibles
        for (int i = 0; i < 1000; i++) {
            List<Slot> compatible = cache.findOptimizedSlotDest(testItem, testSlots, 0, testSlots.size());
        }
        
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1_000_000; // Convertir en millisecondes
        
        System.out.println("Test de cache terminé en " + duration + "ms");
        System.out.println("Performance: " + (1000.0 / duration) + " recherches par ms");
    }
    
    public static void runEmptySlotCacheTest() {
        SlotCache cache = new SlotCache();
        
        // Créer des slots avec certains vides
        List<Slot> testSlots = new ArrayList<>();
        SimpleContainer container = new SimpleContainer(500);
        
        for (int i = 0; i < 500; i++) {
            Slot slot = new Slot(container, i, 0, 0);
            // Remplir seulement la moitié des slots
            if (i % 2 == 0) {
                container.setItem(i, new ItemStack(Items.STONE, 64));
            }
            testSlots.add(slot);
        }
        
        long startTime = System.nanoTime();
        
        // Test de recherche de slots vides
        for (int i = 0; i < 100; i++) {
            List<Slot> emptySlots = cache.findEmptySlots(testSlots, 0, testSlots.size());
        }
        
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1_000_000;
        
        System.out.println("Test slots vides terminé en " + duration + "ms");
    }
    
    public static void printOptimizationSummary() {
        System.out.println("=== RÉSUMÉ DES OPTIMISATIONS SHIFT-CLICK ===");
        System.out.println("1. Cache des slots compatibles par type d'item");
        System.out.println("   - Réduit la complexité de O(n) à O(1)");
        System.out.println("   - Expiration automatique (100ms)");
        System.out.println("");
        System.out.println("2. Cache des slots vides");
        System.out.println("   - Mise à jour rapide (50ms)");
        System.out.println("   - Évite le parcours complet à chaque opération");
        System.out.println("");
        System.out.println("3. Batching des opérations");
        System.out.println("   - Traitement prioritaire : slots existants puis vides");
        System.out.println("   - Réduction des appels setChanged()");
        System.out.println("");
        System.out.println("4. Optimisation spéciale pour le stockage Create");
        System.out.println("   - Utilisation directe de l'API du terminal");
        System.out.println("   - Évite les boucles inefficaces sur de vastes réseaux");
        System.out.println("=== FIN DU RÉSUMÉ ===");
    }
}

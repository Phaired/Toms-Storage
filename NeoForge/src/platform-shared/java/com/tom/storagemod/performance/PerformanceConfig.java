package com.tom.storagemod.performance;

/**
 * Configuration des optimisations de performance pour Tom's Storage
 */
public class PerformanceConfig {
    
    // Activé par défaut pour résoudre les problèmes de lag
    public static final boolean ENABLE_SHIFT_CLICK_OPTIMIZATION = true;
    
    // Paramètres de cache
    public static final long SLOT_CACHE_EXPIRY_MS = 100;
    public static final long EMPTY_SLOT_CACHE_EXPIRY_MS = 50;
    
    // Seuils de performance
    public static final int LARGE_NETWORK_THRESHOLD = 1000; // Slots
    public static final int BATCH_SIZE = 64; // Items par batch
    
    // Debug et logging
    public static final boolean DEBUG_PERFORMANCE = false;
    public static final boolean LOG_CACHE_STATS = false;
    
    /**
     * Détermine si les optimisations doivent être activées pour un réseau donné
     */
    public static boolean shouldOptimize(int networkSize) {
        return ENABLE_SHIFT_CLICK_OPTIMIZATION && networkSize > LARGE_NETWORK_THRESHOLD;
    }
    
    /**
     * Log les statistiques de performance si activé
     */
    public static void logPerformanceStats(String operation, long durationMs, int itemCount) {
        if (DEBUG_PERFORMANCE) {
            System.out.println(String.format(
                "[TomStorage Performance] %s: %dms pour %d items (%.2f items/ms)",
                operation, durationMs, itemCount, itemCount / (double) durationMs
            ));
        }
    }
    
    /**
     * Log les statistiques du cache si activé
     */
    public static void logCacheStats(String cacheType, int hits, int misses) {
        if (LOG_CACHE_STATS) {
            double hitRate = hits / (double) (hits + misses) * 100;
            System.out.println(String.format(
                "[TomStorage Cache] %s: %.1f%% hit rate (%d hits, %d misses)",
                cacheType, hitRate, hits, misses
            ));
        }
    }
}

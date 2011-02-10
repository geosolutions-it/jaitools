/*
 * Copyright 2009 Michael Bedward
 *
 * This file is part of jai-tools.
 *
 * jai-tools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * jai-tools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with jai-tools.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package jaitools.tilecache;

import jaitools.CollectionFactory;

import java.awt.Point;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.media.jai.CachedTile;
import javax.media.jai.PlanarImage;
import javax.media.jai.TileCache;

/**
 * This class implements JAI {@linkplain javax.media.jai.TileCache}. It can store
 * cached tiles on disk to allow applications to work with very large volumes of
 * tiled image data without being limited by available memory.
 * <p>
 * A subset of tiles (by default, the most recently accessed) are cached in memory
 * to reduce access time.
 * <p>
 * The default behaviour is to cache newly added tiles into memory. If the cache
 * needs to free memory to accomodate a tile, it does so by removing lowest priority
 * tiles from memory and caching them to disk. Optionally, the user can specify
 * that newly added tiles are cached to disk immediately.
 * <p>
 * Unlike the standard JAI {@code TileCache} implementation, resident tiles are cached
 * using strong references. This is to support the use of this class with
 * {@linkplain jaitools.tiledimage.DiskMemImage} as well as operations that need to
 * cache tiles that are expensive to create (e.g. output of a time-consuming analysis).
 * A disadvantage of this design is that when the cache is being used for easily
 * generated tiles it can end up unnecessarily holding memory that is more urgently
 * required by other parts of an application. To avoid this happening, the cache can
 * be set to auto-flush resident tiles at regular intervals.
 *
 * @author Michael Bedward
 * @author Simone Giannecchini, GeoSolutions SAS
 *
 * @since 1.0
 * @version $Id$
 * 
 * @see DiskCachedTile
 * @see TileAccessTimeComparator
 */
public class DiskMemTileCache extends Observable implements TileCache {

    /**
     * The default memory capacity in bytes (64 * 2^20 = 64Mb)
     * @see #setMemoryCapacity(long)
     */
    public static final long DEFAULT_MEMORY_CAPACITY = 64L * 1024L * 1024L;

    /**
     * The default memory threshold value (0.75)
     * @see #setMemoryThreshold(float)
     */
    public static final float DEFAULT_MEMORY_THRESHOLD = 0.75F;

    /**
     * The default interval after which the cache will flush if
     * auto-flushing of resident tiles is enabled
     * @see #setAutoFlushMemoryInterval(long)
     * @see #setAutoFlushMemoryEnabled(boolean)
     */
    public static final long DEFAULT_AUTO_FLUSH_MEMORY_INTERVAL = 2500;

    // @todo use JAI ParameterList or some other ready-made class for this ?
    private static class ParamDesc {
        String key;
        Class<?> clazz;
        Object defaultValue;

        ParamDesc(String key, Class<?> clazz, Object defaultValue) {
            this.key = key; this.clazz = clazz; this.defaultValue = defaultValue;
        }

        boolean typeOK (Object value) {
            if (Number.class.isAssignableFrom(clazz)) {
                return Number.class.isAssignableFrom(value.getClass());

            } else {
                return clazz.isAssignableFrom(value.getClass());
            }
        }
    }

    /**
     * Key for the parameter controlling initial memory capacity of the
     * tile cache. This determines the maximum number of tiles that can
     * be resident concurrently. The value must be numeric and will be
     * treated as Long.
     * @see #setMemoryCapacity(long)
     * @see #DEFAULT_MEMORY_CAPACITY
     */
    public static final String KEY_INITIAL_MEMORY_CAPACITY = "memcapacity";

    /**
     * Key for the parameter controlling whether newly added tiles
     * are immediately cached to disk as well as in memory. The value
     * must be one Boolean. If the value is {@code Boolean.FALSE} (the default),
     * disk caching of tiles is deferred until required (ie. when
     * memory needs to be freed for other tiles).
     */
    public static final String KEY_ALWAYS_DISK_CACHE = "diskcache";

    /**
     * Key for the parameter controlling whether the cache will auto-flush
     * memory-resident tiles. The value must be Boolean. If the value is
     * {@code Boolean.TRUE}, auto-flushing of resident tiles will be enabled
     * when the cache is created. The default is {@code Boolean.FALSE}.
     * @see #setAutoFlushMemoryEnabled(boolean)
     */
    public static final String KEY_AUTO_FLUSH_MEMORY_ENABLED = "enableautoflush";

    /**
     * Key for the cache auto-flush interval parameter. The value must be numeric
     * and represents the interval, in milliseconds, between auto-flushes of
     * resident tiles. Values less than or equal to zero are ignored.
     * @see #setAutoFlushMemoryInterval(long)
     * @see #DEFAULT_AUTO_FLUSH_MEMORY_INTERVAL
     */
    public static final String KEY_AUTO_FLUSH_MEMORY_INTERVAL = "autoflushinterval";

    private static Map<String, ParamDesc> paramDescriptors;
    static {
        ParamDesc desc;
        paramDescriptors = new HashMap<String, ParamDesc>();

        desc = new ParamDesc(KEY_INITIAL_MEMORY_CAPACITY, Number.class, DEFAULT_MEMORY_CAPACITY);
        paramDescriptors.put( desc.key, desc );

        desc = new ParamDesc(KEY_ALWAYS_DISK_CACHE, Boolean.class, Boolean.FALSE);
        paramDescriptors.put( desc.key, desc );

        desc = new ParamDesc(KEY_AUTO_FLUSH_MEMORY_ENABLED, Boolean.class, Boolean.FALSE);
        paramDescriptors.put( desc.key, desc );

        desc = new ParamDesc(KEY_AUTO_FLUSH_MEMORY_INTERVAL, Number.class, DEFAULT_AUTO_FLUSH_MEMORY_INTERVAL);
        paramDescriptors.put( desc.key, desc );
    }

    // maximum memory available for resident tiles
    private long memCapacity;

    // current memory used for resident tiles
    private long curMemory;

    /*
     * A value between 0.0 and 1.0 that may be used for memory control
     * if the param KEY_USE_MEMORY_THRESHOLD is TRUE.
     */
    private float memThreshold;

    private boolean writeNewTilesToDisk;

    /**
     * Map of all cached tiles.
     */
    protected Map<Object, DiskCachedTile> tiles;
    
    /**
     * Memory-resident tiles.
     */
    protected Map<Object, Raster> residentTiles;

    /**
     * A tile comparator used to determine the priority of tiles for
     * storage in memory.
     */
    private Comparator<CachedTile> comparator;

    /* List of tile references that is sorted into tile priority order when
     * required for memory swapping.
     * <p>
     * Implementation note: we use this in preference to a SortedSet or similar
     * because of the complications of using the remove(obj) method with a sorted
     * collection, where the comparator is used rather than the equals method.
     */
    protected List<DiskCachedTile> sortedResidentTiles;

    // whether to send cache diagnostics to observers
    private boolean diagnosticsEnabled;

    /**
     * Variables used for auto-flushing of resident tiles
     */
    private static final String TIMER_THREAD_NAME = "cache_auto_flush";
    private Timer autoFlushTimer;
    private long autoFlushInterval = DEFAULT_AUTO_FLUSH_MEMORY_INTERVAL;
    private long timeToFlush;

    private class AutoFlushTask extends TimerTask {

        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (now > timeToFlush) {
                flushMemory();
            }
        }
    };


    /**
     * Constructor. Creates an instance of the cache with all parameters set
     * to their default values. Equivalent to <code>DiskMemTileCache(null)</code>.
     */
    public DiskMemTileCache() {
        this(null);
    }

    /**
     * Constructor.
     *
     * @param params an optional map of parameters (may be empty or null)
     */
    public DiskMemTileCache(Map<String, Object> params) {
        if (params == null) {
            params = Collections.emptyMap();
        }

        diagnosticsEnabled = false;
        tiles = new HashMap<Object, DiskCachedTile>();
        residentTiles = CollectionFactory.map();
        curMemory = 0L;
        memThreshold = DEFAULT_MEMORY_THRESHOLD;

        Object o;
        ParamDesc desc;

        desc= paramDescriptors.get(KEY_INITIAL_MEMORY_CAPACITY);
        memCapacity = (Long)desc.defaultValue;
        o = params.get(desc.key);
        if (o != null) {
            if (desc.typeOK(o)) {
                memCapacity = ((Number)o).longValue();
            }
        }

        desc = paramDescriptors.get(KEY_ALWAYS_DISK_CACHE);
        writeNewTilesToDisk = (Boolean)desc.defaultValue;
        o = params.get(desc.key);
        if (o != null) {
            if (desc.typeOK(o)) {
                writeNewTilesToDisk = (Boolean)o;
            }
        }

        desc = paramDescriptors.get(KEY_AUTO_FLUSH_MEMORY_INTERVAL);
        autoFlushInterval = ((Number)desc.defaultValue).longValue();
        o = params.get(desc.key);
        if (o != null) {
            if (desc.typeOK(o)) {
                long lval = ((Number)o).longValue();
                if (lval > 0) {
                    autoFlushInterval = lval;
                }
            }
        }

        desc = paramDescriptors.get(KEY_AUTO_FLUSH_MEMORY_ENABLED);
        o = params.get(desc.key);
        if (o != null) {
            if (desc.typeOK(o)) {
                setAutoFlushMemoryEnabled((Boolean)o);
            }
        }

        comparator = new TileAccessTimeComparator();
        sortedResidentTiles = new ArrayList<DiskCachedTile>();
    }

    /**
     * Deletes all disk-cached tiles when the cache is garbage collected
     *
     * @throws Throwable
     */
    @Override
    protected void finalize() throws Throwable {
        if (autoFlushTimer != null) {
            autoFlushTimer.cancel();
        }

        flush();
    }

    /**
     * Add a tile to the cache if not already present.
     *
     * @param owner the image that this tile belongs to
     * @param tileX the tile column
     * @param tileY the tile row
     * @param data the tile data
     */
    public void add(RenderedImage owner, int tileX, int tileY, Raster data) {
        add(owner, tileX, tileY, data, null);
    }
		 
    /**
     * Add a tile to the cache if not already present.
     *
     * @param owner the image that this tile belongs to
     * @param tileX the tile column
     * @param tileY the tile row
     * @param data the tile data
     * @param tileCacheMetric optional tile cache metric (may be {@code null}
     */
    public synchronized void add(RenderedImage owner,
                int tileX,
                int tileY,
                Raster data,
                Object tileCacheMetric) {

        resetAutoFlushMemoryTimer();

        Object key = getTileId(owner, tileX, tileY);
        if (tiles.containsKey(key)) {
            // tile is already cached
            return;
        }

        try {
            DiskCachedTile tile = new DiskCachedTile(
                    key, owner, tileX, tileY, data, writeNewTilesToDisk, tileCacheMetric);
            tiles.put(key, tile);

            if ( makeResident(tile, data) ) {
                tile.setAction(DiskCachedTile.TileAction.ACTION_ADDED_RESIDENT);
            } else {
                tile.setAction(DiskCachedTile.TileAction.ACTION_ADDED);
            }

            if (diagnosticsEnabled) {
                setChanged();
                notifyObservers(tile);
            }

        } catch (IOException ex) {
            Logger.getLogger(DiskMemTileCache.class.getName())
                    .log(Level.SEVERE, "Unable to cache this tile on disk", ex);
        }
    }

    /**
     * Remove the specifed tile from the cache
     * @param owner the image that this tile belongs to
     * @param tileX the tile column
     * @param tileY the tile row
     */
    public synchronized void remove(RenderedImage owner, int tileX, int tileY) {
        resetAutoFlushMemoryTimer();

        Object key = getTileId(owner, tileX, tileY);
        DiskCachedTile tile = tiles.get(key);

        if (tile == null) {
            return;
        }

        if (residentTiles.containsKey(key)) {
            try {
                removeResidentTile(key, false);

            } catch (DiskCacheFailedException ex) {
                /*
                 * It would be nicer to just throw this exception
                 * upwards but we can't in the overidden method
                 */
                Logger.getLogger(DiskMemTileCache.class.getName()).
                        log(Level.SEVERE, null, ex);
            }
        }

        tile.deleteDiskCopy();

        tile.setAction(DiskCachedTile.TileAction.ACTION_REMOVED);
        if (diagnosticsEnabled) {
            setChanged();
            notifyObservers(tile);
        }

        tiles.remove(key);
    }

    
    /**
     * Get the specified tile from the cache, if present. If the tile is
     * cached but not resident in memory it will be read from the cache's
     * disk storage and made resident.
     *
     * @param owner the image that the tile belongs to
     * @param tileX the tile column
     * @param tileY the tile row
     * @return the requested tile or null if the tile was not cached
     */
    public synchronized Raster getTile(RenderedImage owner, int tileX, int tileY) {
        resetAutoFlushMemoryTimer();

        Raster r = null;

        Object key = getTileId(owner, tileX, tileY);

        DiskCachedTile tile = tiles.get(key);
        if (tile != null) {

            // is the tile resident ?
            r = residentTiles.get(key);
            if (r == null) {
                /*
                 * The tile is not resident. Attempt
                 * to read it from the disk.
                 */
                r = tile.readData();
                if (r == null) {
                    /* The tile was not cached on disk. It may have
                     * been resident only, and then flushed.
                     */
                    return null;
                }
                
                if (makeResident(tile, r)) {
                    tile.setAction(DiskCachedTile.TileAction.ACTION_RESIDENT);
                    if (diagnosticsEnabled) {
                        setChanged();
                        notifyObservers(tile);
                    }
                }
            }

            tile.setAction(DiskCachedTile.TileAction.ACTION_ACCESSED);
            tile.setTileTimeStamp(System.currentTimeMillis());

            if (diagnosticsEnabled) {
                setChanged();
                notifyObservers(tile);
            }
        }

        return r;
    }

    /**
     * Get all cached tiles associated with the specified image.
     * The tiles will be loaded into memory as space allows.
     * 
     * @param owner the image for which tiles are requested
     * @return an array of tile Rasters
     */
    public synchronized Raster[] getTiles(RenderedImage owner) {
        resetAutoFlushMemoryTimer();

        int minX = owner.getMinTileX();
        int minY = owner.getMinTileY();
        int numX = owner.getNumXTiles();
        int numY = owner.getNumYTiles();
        
        List<Object> keys = new ArrayList<Object>();
        for (int y = minY, ny=0; ny < numY; y++, ny++) {
            for (int x = minX, nx = 0; nx < numX; x++, nx++) {
                Object key = getTileId(owner, x, y);
                if (tiles.containsKey(key)) keys.add(key);
            }
        }

        Raster[] rasters = new Raster[keys.size()];
        int k = 0;
        for (Object key : keys) {
            DiskCachedTile tile = tiles.get(key);
            Raster r = residentTiles.get(tile.getTileId());
            if (r == null) {
                r = tile.readData();
                makeResident(tile, r);
            }

            rasters[k++] = r;

            tile.setTileTimeStamp(System.currentTimeMillis());
            tile.setAction(DiskCachedTile.TileAction.ACTION_ACCESSED);
            if (diagnosticsEnabled) {
                setChanged();
                notifyObservers(tile);
            }
        }

        return rasters;
    }

    /**
     * Remove all tiles that belong to the specified image from the cache
     * @param owner the image owning the tiles to be removed
     */
    public void removeTiles(RenderedImage owner) {
        for (int y = owner.getMinTileY(), ny=0; ny < owner.getNumYTiles(); y++, ny++) {
            for (int x = owner.getMinTileX(), nx=0; nx < owner.getNumXTiles(); x++, nx++) {
                remove(owner, x, y);
            }
        }
    }

    /**
     * Check if any tiles have a null owner (e.g. owning image has been
     * garbage collected) and, if so, remove them from the cache.
     */
    public synchronized void removeNullTiles() {
        Set<Object> nullTileKeys = CollectionFactory.set();
        for (Object key : tiles.keySet()) {
            DiskCachedTile tile = tiles.get(key);
            if (tile.getOwner() == null) {
                nullTileKeys.add(key);
            }
        }

        for (Object key : nullTileKeys) {
            DiskCachedTile tile = tiles.get(key);
            tile.deleteDiskCopy();
            if (residentTiles.containsKey(key)) {
                residentTiles.remove(key);
                sortedResidentTiles.remove(tile);
                curMemory -= tile.getTileSize();
            }
            tiles.remove(key);
        }
    }

    /**
     * This method is not presently declared as synchronized because it simply calls
     * the {@code add} method repeatedly.
     *
     * @param owner the image that the tiles belong to
     * @param tileIndices an array of Points specifying the column-row coordinates
     * of each tile
     * @param tiles tile data in the form of Raster objects
     * @param tileCacheMetric optional metric (may be null)
     *
     * @see #add(java.awt.image.RenderedImage, int, int, java.awt.image.Raster, java.lang.Object) 
     */
    public void addTiles(RenderedImage owner,
                     Point[] tileIndices,
                     Raster[] tiles,
                     Object tileCacheMetric) {

        if (tileIndices.length != tiles.length) {
            throw new IllegalArgumentException("tileIndices and tiles args must be the same length");
        }

        for (int i = 0; i < tiles.length; i++) {
            add(owner, tileIndices[i].x, tileIndices[i].y, tiles[i], tileCacheMetric);
        }
    }

    /**
     * This method is not presently declared as synchronized because it simply calls
     * the <code>getTile</code> method repeatedly.
     *
     * @param owner the image that the tiles belong to
     * @param tileIndices an array of Points specifying the column-row coordinates
     * of each tile
     * @return data for the requested tiles as Raster objects
     */
    public Raster[] getTiles(RenderedImage owner, Point[] tileIndices) {
        Raster[] r = null;

        if (tileIndices.length > 0) {
            r = new Raster[tileIndices.length];
            for (int i = 0; i < tileIndices.length; i++) {
                r[i] = getTile(owner, tileIndices[i].x, tileIndices[i].y);
            }
        }

        return r;
    }

    /**
     * Remove ALL tiles from the cache: all resident tiles will be
     * removed from memory and all files for disk-cached tiles will
     * be discarded.
     * <p>
     * The update action of each tile will be set to {@linkplain DiskCachedTile#ACTION_REMOVED}.
     */
    public synchronized void flush() {
        flushMemory();
        
        for (DiskCachedTile tile : tiles.values()) {
            tile.deleteDiskCopy();
            tile.setAction(DiskCachedTile.TileAction.ACTION_REMOVED);
            if (diagnosticsEnabled) {
                setChanged();
                notifyObservers(tile);
            }
        }
        tiles.clear();
    }

    /**
     * Remove all resident tiles from memory. No rewriting of tile data
     * to disk is done.
     */
    public synchronized void flushMemory() {
        residentTiles.clear();
        sortedResidentTiles.clear();
        curMemory = 0;
    }

    /**
     * Free memory for resident tiles so that the fraction of memory occupied is
     * no more than the current value of the mamory threshold. 
     *
     * @see DiskMemTileCache#setMemoryThreshold(float)
     */
    public synchronized void memoryControl() {
        long maxUsed = (long)(memThreshold * memCapacity);
        long toFree = curMemory - maxUsed;
        if (toFree > 0) {
            defaultMemoryControl( toFree );
        }
    }

    /**
     * Make the requested amount of memory cache available, removing
     * resident tiles as necessary
     *
     * @param memRequired memory requested (bytes)
     */
    private void defaultMemoryControl( long memRequired ) {
        if (memRequired > memCapacity) {
            // @todo something better than this...
            throw new RuntimeException("space required is greater than cache memory capacity");
        }

        /*
         * Remove one or more lowest priority tiles to free
         * space
         */
        Collections.sort(sortedResidentTiles, comparator);
        while (memCapacity - curMemory < memRequired && !sortedResidentTiles.isEmpty()) {
            Object key = sortedResidentTiles.get(sortedResidentTiles.size()-1).getTileId();

            try {
                removeResidentTile(key, true);
            } catch (DiskCacheFailedException ex) {
                /*
                 * It would be nicer to just throw this exception
                 * upwards be we can't in the overidden method
                 */
                Logger.getLogger(DiskMemTileCache.class.getName()).
                        log(Level.SEVERE, null, ex);
            }
        }
    }


    /**
     * Implemented as an empty method (it was deprecated as of JAI 1.1)
     * @param arg0
     * @deprecated
     */
    @Deprecated
    public void setTileCapacity(int arg0) {
    }

    /**
     * Implemented as a dummy method that always returns 0
     * (it was deprecated as of JAI 1.1)
     * @deprecated
     */
    @Deprecated
    public int getTileCapacity() {
        return 0;
    }

    /**
     * Reset the memory capacity of the cache. Setting capacity to 0 will
     * flush all resident tiles from memory. Setting a capcity less than the
     * current capacity may result in some memory-resident tiles being
     * removed from memory.
     *
     * @param newCapacity requested memory capacity for resident tiles
     */
    public synchronized void setMemoryCapacity(long newCapacity) {
        if (newCapacity < 0) {
            throw new IllegalArgumentException("memory capacity must be >= 0");
        }

        long oldCapacity = memCapacity;
        memCapacity = newCapacity;

        if (newCapacity == 0) {
            flushMemory();

        } else if (newCapacity < oldCapacity && curMemory > newCapacity) {
            /*
             * Note: we free memory here directly rather than using
             * memoryControl or defaultMemoryControl methods because
             * they will fail when memCapacity has been reduced
             */
            Collections.sort(sortedResidentTiles, comparator);
            while (curMemory > newCapacity) {
                Object key = sortedResidentTiles.get(sortedResidentTiles.size()-1).getTileId();
                try {
                    removeResidentTile(key, true);
                } catch (DiskCacheFailedException ex) {
                    /*
                     * It would be nicer to just throw this exception
                     * upwards be we can't in the overidden method
                     */
                    Logger.getLogger(DiskMemTileCache.class.getName()).
                            log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    /**
     * Get the amount of memory, in bytes, allocated for storage of
     * resident tiles.
     *
     * @return resident tile memory capacity in bytes
     */
    public long getMemoryCapacity() {
        return memCapacity;
    }

    /**
     * Get the amount of memory currently being used for storage of
     * memory-resident tiles
     *
     * @return current memory use in bytes
     */
    public long getCurrentMemory() {
        return curMemory;
    }

    /**
     * Sets the memoryThreshold value to a floating point number that ranges from
     * 0.0 to 1.0. When the cache memory is full, the memory usage will be reduced
     * to this fraction of the total cache memory capacity. For example, a value
     * of .75 will cause 25% of the memory to be cleared, while retaining 75%.
     *
     * @param memoryThreshold Retained fraction of memory
     * @throws IllegalArgumentException if the memoryThreshold is less than 0.0 or greater than 1.0
     */
    public void setMemoryThreshold(float newThreshold) {
        if (newThreshold < 0.0F) {
            memThreshold = 0.0F;
        } else if (newThreshold > 1.0F) {
            memThreshold = 1.0F;
        } else {
            memThreshold = newThreshold;
        }

        memoryControl();
    }

    /**
     * Returns the memory threshold, which is the fractional amount of cache memory
     * to retain during tile removal. This only applies if memory thresholding has
     * been enabled by passing the parameter {@linkplain #KEY_USE_MEMORY_THRESHOLD} to
     * the constructor with a value of <code>Boolean.TRUE</code>.
     *
     * @return the retained fraction of memory
     */
    public float getMemoryThreshold() {
        return memThreshold;
    }

    // TODO write me !
    public synchronized void setTileComparator(Comparator comp) {

        if ( comp == null ) {
            // switch to default comparator based on tile access time
            comparator = new TileAccessTimeComparator();
        } else {
            comparator = comp;
        }

        sortedResidentTiles = new ArrayList<DiskCachedTile>();
        for (Object key : residentTiles.keySet()) {
            sortedResidentTiles.addAll(tiles.values());
        }
        Collections.sort(sortedResidentTiles, comparator);
    }

    /**
     * Get the Comparator currently being used to set priority
     * for resident tiles
     * @return reference to the current Comparator
     */
    public Comparator getTileComparator() {
        return comparator;
    }

    /**
     * Get the total number of tiles currently in the cache
     * @return number of cached tiles
     */
    public int getNumTiles() {
        return tiles.size();
    }

    /**
     * Get the number of tiles currently residing in the
     * cache's memory storage
     * @return number of memory-resident tiles
     */
    public int getNumResidentTiles() {
        return residentTiles.size();
    }

    /**
     * Query whether a given tile is in this cache
     * 
     * @param owner the owning image
     * @param tileX tile column
     * @param tileY tile row
     * @return true if the cache contains the tile; false otherwise
     */
    public boolean containsTile(RenderedImage owner, int tileX, int tileY) {
        Object key = getTileId(owner, tileX, tileY);
        return tiles.containsKey(key);
    }

    /**
     * Query whether a given tile is in this cache's memory storage
     *
     * @param owner the owning image
     * @param tileX tile column
     * @param tileY tile row
     * @return true if the tile is in cache memory; false otherwise
     */
    public boolean containsResidentTile(RenderedImage owner, int tileX, int tileY) {
        Object key = getTileId(owner, tileX, tileY);
        return residentTiles.containsKey(key);
    }

    /**
     * Inform the cache that the tile's data have changed. The tile should
     * be resident in memory as the result of a previous <code>getTile</code>
     * request. If this is the case and the tile was previously written to
     * disk, then the cache's disk copy of the tile will be refreshed.
     * <P>
     * If the tile is not resident in memory, for instance
     * because of memory swapping for other tile accesses, the disk copy
     * will not be refreshed and a <code>TileNotResidentException</code> is
     * thrown.
     *
     * @param owner the owning image
     * @param tileX tile column
     * @param tileY tile row
     * @throws TileNotResidentException if the tile is not resident
     */
    public void setTileChanged(RenderedImage owner, int tileX, int tileY)
            throws TileNotResidentException, DiskCacheFailedException {

        resetAutoFlushMemoryTimer();

        Object tileId = getTileId(owner, tileX, tileY);
        Raster r = residentTiles.get(tileId);
        if (r == null) {
            throw new TileNotResidentException(owner, tileX, tileY);
        }

        DiskCachedTile tile = tiles.get(tileId);
        if (tile.cachedToDisk()) {
            try {
                tile.writeData(r);
            } catch (IOException ioEx) {
                throw new DiskCacheFailedException(owner, tileX, tileY);
            }
        }
    }

    /**
     * Enable or disable auto-flushing of the cache with the
     * currently set auto-flush interval
     *
     * @param state true to enable auto-flushing; false to disable
     * @see #setAutoFlushMemoryInterval(long)
     */
    public void setAutoFlushMemoryEnabled(boolean state) {
        if (state) {
            if (autoFlushTimer == null) {
                autoFlushTimer = new Timer(TIMER_THREAD_NAME, true);
                autoFlushTimer.schedule(new AutoFlushTask(), autoFlushInterval, autoFlushInterval);
            }
        } else {
            if (autoFlushTimer != null) {
                autoFlushTimer.cancel();
                autoFlushTimer = null;
            }
        }
    }

    /**
     * Query whether auto-flushing is currently enabled
     *
     * @return true if the cache is auto-flushing; false otherwise
     */
    public boolean isAutoFlushMemoryEnabled() {
        return (autoFlushTimer != null);
    }

    /**
     * Set the interval, in milliseconds, to elapse between each automatic
     * flush of the cache.
     *
     * @param interval interval in milliseconds
     *        (values less than or equal to zero are ignored)
     */
    public void setAutoFlushMemoryInterval(long interval) {
        if (interval > 0 && interval != autoFlushInterval) {
            autoFlushInterval = interval;

            if (autoFlushTimer != null) {
                autoFlushTimer.cancel();
                autoFlushTimer = new Timer(TIMER_THREAD_NAME, true);
                autoFlushTimer.schedule(new AutoFlushTask(), autoFlushInterval, autoFlushInterval);
            }
        }
    }

    /**
     * Get the current auto-flush interval
     *
     * @return interval in milliseconds
     */
    public long getAutoFlushMemoryInterval() {
        return autoFlushInterval;
    }

    /**
     * Called by the cache when its contents are modified or accessed to
     * ensure that the next auto-flush (if enabled) will not occur before
     * the set auto-flush interval has elapsed.
     */
    private void resetAutoFlushMemoryTimer() {
        timeToFlush = System.currentTimeMillis() + autoFlushInterval;
    }

    /**
     * Enable or disable the publishing of cache messages
     * to Observers
     *
     * @param state true to publish diagnostic messages; false to suppress them
     */
    public void setDiagnostics(boolean state) {
        diagnosticsEnabled = state;
    }

    /**
     * Accept a <code>DiskMemCacheVisitor</code> object and call its
     * <code>visit</code> method for each tile presently in the
     * cache.
     */
    public synchronized void accept(DiskMemTileCacheVisitor visitor) {
        for (Object key : tiles.keySet()) {
            visitor.visit(tiles.get(key), residentTiles.containsKey(key));
        }
    }

    /**
     * Add a raster to those resident in memory
     */
    private boolean makeResident(DiskCachedTile tile, Raster data) {
        resetAutoFlushMemoryTimer();

        if (tile.getTileSize() > memCapacity) {
            return false;
        }
        
        if (tile.getTileSize() > memCapacity - curMemory) {
            memoryControl();

            /*
             * It is possible that the threshold rule fails to
             * free enough memory for the tile
             */
            if (tile.getTileSize() > memCapacity - curMemory) {
                defaultMemoryControl(tile.getTileSize());
            }
        }
        
        residentTiles.put(tile.getTileId(), data);
        curMemory += tile.getTileSize();

        /*
         * We don't bother about sort order here. Instead, the list
         * will be sorted by tile priority when resident tiles are
         * being removed
         */
        sortedResidentTiles.add(tile);

        return true;
    }


    /**
     * Remove a tile from the cache's memory storage. This may be to free
     * space for other tiles, in which case <code>writeData</code> will be
     * set to true and, if the tile is writable, a request is made to write
     * its data to disk again. If the tile is being removed from the cache
     * entirely, this method will be called with <code>writeData</code> set
     * to false.
     *
     * @param tileId the tile's unique id
     * @param writeData if true, and the tile is writable, its data will be
     * written to disk again; otherwise no writing is done.
     */
    private void removeResidentTile(Object tileId, boolean writeData) throws DiskCacheFailedException {
        resetAutoFlushMemoryTimer();

        DiskCachedTile tile = tiles.get(tileId);
        Raster raster = residentTiles.remove(tileId);
        sortedResidentTiles.remove(tile);
        curMemory -= tile.getTileSize();

        /**
         * If the tile is writable, ie. its data are represented
         * by a WritableRaster, we cache it to disk
         */
        if (writeData && tile.isWritable()) {
            try {
                tile.writeData(raster);
            } catch (IOException ioEx) {
                throw new DiskCacheFailedException(tile.getOwner(), tile.getTileX(), tile.getTileY());
            }
        }

        tile.setAction(DiskCachedTile.TileAction.ACTION_NON_RESIDENT);
        if (diagnosticsEnabled) {
            setChanged();
            notifyObservers(tile);
        }
    }
    

    /**
     * Generate a unique ID for this tile. This uses the same technique as the
     * Sun memory cache implementation: putting the id of the owning image
     * into the upper bytes of a long or BigInteger value and the tile index into
     * the lower bytes.
     * @param owner the owning image
     * @param tileX tile column
     * @param tileY tile row
     * @return the ID as an Object which will be an instance of either Long or BigInteger
     */
    private Object getTileId(RenderedImage owner,
                              int tileX,
                              int tileY) {

        long tileId = tileY * (long)owner.getNumXTiles() + tileX;

        BigInteger imageId = null;

        if (owner instanceof PlanarImage) {
            imageId = (BigInteger)((PlanarImage)owner).getImageID();
        }

        if (imageId != null) {
            byte[] buf = imageId.toByteArray();
            int length = buf.length;
            byte[] buf1 = new byte[buf.length + 8];
            System.arraycopy(buf, 0, buf1, 0, length);
            for (int i = 7, j = 0; i >= 0; i--, j += 8)
                buf1[length++] = (byte)(tileId >> j);
            return new BigInteger(buf1);

        } else {
            tileId &= 0x00000000ffffffffL;
            return new Long(((long)owner.hashCode() << 32) | tileId);
        }
    }

}

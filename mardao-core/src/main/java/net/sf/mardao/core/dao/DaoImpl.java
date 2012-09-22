package net.sf.mardao.core.dao;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.TreeSet;
import net.sf.jsr107cache.Cache;
import net.sf.jsr107cache.CacheException;
import net.sf.jsr107cache.CacheFactory;
import net.sf.jsr107cache.CacheManager;
import net.sf.mardao.core.CursorPage;
import net.sf.mardao.core.Filter;
import net.sf.mardao.core.geo.DLocation;
import net.sf.mardao.core.geo.GeoModel;
import net.sf.mardao.core.geo.Geobox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This is the base class for all implementations of the Dao Bean.
 * 
 * @author os
 * 
 * @param <T>
 *            domain object type
 * @param <ID>
 *            domain object simple key type
 * @param <P>
 *            domain object parent key type
 * @param <CT>
 *            cursor type
 * @param <E>
 *            database core entity type
 * @param <C>
 *            database core key type
 */
public abstract class DaoImpl<T extends Object, ID extends Serializable, 
        P extends Serializable, CT extends Object,
        E extends Serializable, C extends Serializable>
        implements Dao<T, ID> {
    
    public static final String PRINCIPAL_NAME_ANONYMOUS = "[ANONYMOUS]";
    
    /** Default name of the geoboxes column is "geoboxes" */
    public static final String COLUMN_NAME_GEOBOXES_DEFAULT = "geoboxes";

    /** Using slf4j logging */
    protected static final Logger   LOG = LoggerFactory.getLogger(DaoImpl.class);
    
    private Collection<Integer> boxBits = Arrays.asList(
        Geobox.BITS_12_10km, Geobox.BITS_15_1224m, Geobox.BITS_18_154m
    );

    /** set this, to have createdBy and updatedBy set */
    private static final ThreadLocal<String> principalName = new ThreadLocal<String>();
    
    /** mostly for logging */
    protected final Class<T> persistentClass;
    
    /** To help converting keys */
    protected final Class<ID> simpleIdClass;
    
    /** 
     * Set this to true in DaoBean constructor, to enable
     * the all-domains memCache
     */
    protected boolean memCacheAll = false;

    /** 
     * Set this to true in DaoBean constructor, to enable
     * the primaryKey-to-domain memCache
     */
    protected boolean memCacheEntities = false;
    
    private static Cache memCache = null;
    
    /** inject to get different behavior */
    private static Map memCacheConfig = Collections.EMPTY_MAP;

    protected DaoImpl(Class<T> domainType, Class<ID> simpleIdType) {
        this.persistentClass = domainType;
        this.simpleIdClass = simpleIdType;
    }

    public String getTableName() {
        return persistentClass.getSimpleName();
    }

    // --- BEGIN persistence-type beans must implement these ---
    
    /**
     * Implement / Override this in TypeDaoImpl. This method does not have to
     * worry about invalidating the cache, that is done in delete(parentKey, simpleKeys)
     * @param parentKey
     * @param simpleKeys
     * @return number of rows deleted (optional)
     */
    protected abstract int doDelete(Object parentKey, Iterable<ID> simpleKeys);
    /**
     * Implement / Override this in TypeDaoImpl. This method does not have to
     * worry about invalidating the cache, that is done in delete(domains)
     * @param domains
     * @return number of rows deleted (optional)
     */
    protected abstract int doDelete(Iterable<T> domains);
    
    protected abstract T doFindByPrimaryKey(Object parentKey, ID simpleKeys);
    protected abstract Iterable<T> doQueryByPrimaryKeys(Object parentKey, Iterable<ID> simpleKeys);

    protected abstract T findUniqueBy(Filter... filters);
    
    /** Implemented in TypeDaoImpl */
    protected abstract Collection<C> persistCore(Iterable<E> itrbl);
    
    /** Implemented in TypeDaoImpl */
    protected abstract CursorPage<T, ID> queryPage(boolean keysOnly, int pageSize,
            C ancestorKey, C simpleKey,
            String primaryOrderBy, boolean primaryIsAscending,
            String secondaryOrderBy, boolean secondaryIsAscending,
            Serializable cursorString,
            Filter... filters);

    /** Implemented in TypeDaoImpl */
    protected abstract Iterable<T> queryIterable(boolean keysOnly, 
            int offset, int limit,
            P ancestorKey, C simpleKey,
            String primaryOrderBy, boolean primaryIsAscending,
            String secondaryOrderBy, boolean secondaryIsAscending,
            Filter... filters);

    /** Implemented in TypeDaoImpl */
    protected abstract Iterable<ID> queryIterableKeys(int offset, int limit,
            P ancestorKey, C simpleKey,
            String primaryOrderBy, boolean primaryIsAscending,
            String secondaryOrderBy, boolean secondaryIsAscending,
            Filter... filters);

    /** Implemented in TypeDaoImpl */
    protected abstract ID coreToSimpleKey(E core);
    /** Implemented in TypeDaoImpl */
    protected abstract ID coreKeyToSimpleKey(C core);
    /** Implemented in TypeDaoImpl */
    protected abstract P coreToParentKey(E core);
    /** Implemented in TypeDaoImpl */
    protected abstract P coreKeyToParentKey(C core);
    
    protected abstract E createCore(Object primaryKey);
    protected abstract E createCore(Object parentKey, ID simpleKey);
    
    protected abstract String createMemCacheKey(Object parentKey, ID simpleKey);

    /** Implemented in TypeDaoImpl */
    protected abstract Object getCoreProperty(E core, String name);
    /** Implemented in TypeDaoImpl */
    protected abstract void setCoreProperty(Serializable core, String name, Object value);
    
    protected abstract Filter createEqualsFilter(String columnName, Object value);
    
    // --- END persistence-type beans must implement these ---
    
    public T coreToDomain(E core) {
        if (null == core) {
            return null;
        }
        
        final ID simpleKey = coreToSimpleKey(core);
        final P parentKey = coreToParentKey(core);
        
        try {
            final T domain = createDomain(parentKey, simpleKey);

            // created, updated
            copyCorePropertyToDomain(getCreatedByColumnName(), core, domain);
            copyCorePropertyToDomain(getCreatedDateColumnName(), core, domain);
            copyCorePropertyToDomain(getUpdatedByColumnName(), core, domain);
            copyCorePropertyToDomain(getUpdatedDateColumnName(), core, domain);

            // Domain Entity-specific properties
            for (String name : getColumnNames()) {
                copyCorePropertyToDomain(name, core, domain);
            }

            return domain;
        }
        catch (IllegalAccessException shouldNeverHappen) {
            LOG.error(getTableName(), shouldNeverHappen);
        }
        catch (InstantiationException shouldNeverHappen) {
            LOG.error(getTableName(), shouldNeverHappen);
        }
        return null;
    }
    
    protected Object copyCorePropertyToDomain(String name, E core, T domain) {
        if (null == name) {
            return null;
        }
        
        Object value = getCoreProperty(core, name);
        setDomainProperty(domain, name, value);
        
        return value;
    }
    
    protected Object copyDomainPropertyToCore(String name, T domain, E core) {
        if (null == name) {
            return null;
        }
        
        Object value = getDomainProperty(domain, name);
        setCoreProperty(core, name, value);
        
        return value;
    }
    
    protected T createDomain() throws InstantiationException, IllegalAccessException {
        return createDomain(null, null);
    }

    protected T createDomain(Object primaryKey) throws InstantiationException, IllegalAccessException {
        C pk = (C) primaryKey;
        Object parentKey = coreKeyToParentKey(pk);
        ID simpleKey = coreKeyToSimpleKey(pk);
        return createDomain(null, null);
    }

    protected T createDomain(Object parentKey, ID simpleKey) throws InstantiationException, IllegalAccessException {
        final T domain = persistentClass.newInstance();
        
        setParentKey(domain, parentKey);
        setSimpleKey(domain, simpleKey);
        
        return domain;
    }
    
    public E domainToCore(T domain, final Date currentDate) {
        if (null == domain) {
            return null;
        }
        
        E core = createCore(getParentKey(domain), getSimpleKey(domain));
        
        // created, updated
        String principal = getCreatedBy(domain);
        if (null == principal) {
            principal = getPrincipalName();
            if (null == principal) {
                principal = PRINCIPAL_NAME_ANONYMOUS;
            }
            _setCreatedBy(domain, principal);
        }
        setCoreProperty(core, getCreatedByColumnName(), principal);
        
        Date date = getCreatedDate(domain);
        if (null == date) {
            date = currentDate;
            _setCreatedDate(domain, currentDate);
        }
        setCoreProperty(core, getCreatedDateColumnName(), date);
        
        principal = getPrincipalName();
        if (null == principal) {
            principal = PRINCIPAL_NAME_ANONYMOUS;
        }
        _setUpdatedBy(domain, principal);
        setCoreProperty(core, getUpdatedByColumnName(), principal);
        
        _setUpdatedDate(domain, currentDate);
        setCoreProperty(core, getUpdatedDateColumnName(), currentDate);
        
        // Domain Entity-specific properties
        for (String name : getColumnNames()) {
            copyDomainPropertyToCore(name, domain, core);
        }

        // geoboxes
        if (null != getGeoLocationColumnName()) {
            updateGeoModel(domain, core);
        }

        return core;
    }
    
    public Collection<Serializable> domainsToPrimaryKeys(Iterable<T> domains) {
        final Collection<Serializable> keys = new ArrayList<Serializable>();
        Serializable pk;
        for (T d : domains) {
            pk = (Serializable) getPrimaryKey(d);
            keys.add(pk);
        }
        return keys;
    }

    public Collection<ID> domainsToSimpleKeys(Iterable<T> domains) {
        final Collection<ID> keys = new ArrayList<ID>();
        ID simpleKey;
        for (T d : domains) {
            simpleKey = getSimpleKey(d);
            keys.add(simpleKey);
        }
        return keys;
    }

    public Collection<ID> coresToSimpleKeys(Iterable<E> cores) {
        final Collection<ID> ids = new ArrayList<ID>();
        ID id;
        for (E core : cores) {
            id = coreToSimpleKey(core);
            ids.add(id);
        }
        return ids;
    }
    
    protected final String createMemCacheKeyAll() {
        return String.format("%s.all()", getTableName());
    }
    
    protected final Collection<String> createMemCacheKeys(Object parentKey, Iterable<ID> simpleKeys) {
        Collection<String> returnValue = new ArrayList<String>();
        
        for (ID id : simpleKeys) {
            returnValue.add(createMemCacheKey(parentKey, id));
        }
        
        return returnValue;
    }

    /** Override in GeneratedDaoImpl */
    protected Object getDomainProperty(T domain, String name) {
        Object value;
        if (name.equals(getCreatedByColumnName())) {
            value = getCreatedBy(domain);
        }
        else if (name.equals(getCreatedDateColumnName())) {
            value = getCreatedDate(domain);
        }
        else if (name.equals(getUpdatedByColumnName())) {
            value = getUpdatedBy(domain);
        }
        else if (name.equals(getUpdatedDateColumnName())) {
            value = getUpdatedDate(domain);
        }
        else {
            throw new IllegalArgumentException(String.format("No such property %s for %s", name, getTableName()));
        }
        
        return value;
    }
    
    protected static Cache getMemCache() {
        if (null == memCache) {
            try {
                final CacheFactory factory = CacheManager.getInstance().getCacheFactory();
                memCache = factory.createCache(memCacheConfig);
            }
            catch (CacheException ce) {
                LOG.error("Could not create memCache", ce);
            }
        }
        return memCache;
    }
    
    protected final void updateMemCache(Collection<String> memCacheKeys) {
        LOG.debug("removing cache for {} {}", memCacheKeys.size(), getTableName());
        if (!memCacheKeys.isEmpty()) {
            // invalidate cache
            if (memCacheAll) {
                getMemCache().remove(createMemCacheKeyAll());
            }

            if (memCacheEntities) {
                for (String memCacheKey : memCacheKeys) {
                    getMemCache().remove(memCacheKey);
                }
            }
        }
    }
    
    protected final void updateMemCache(boolean remove, Map<String, T> domains) {
        if (remove) {
            updateMemCache(domains.keySet());
        }
        else {
            LOG.debug("updating cache for {} {}", domains.size(), getTableName());
            if (!domains.isEmpty()) {
                // invalidate cache
                if (memCacheAll) {
                    getMemCache().remove(createMemCacheKeyAll());
                }

                if (memCacheEntities) {
                    getMemCache().putAll(domains);
                }
            }
        }
    }
    
    protected final Collection<T> updateMemCache(boolean remove, Iterable<T> domains) {
        if (memCacheEntities || memCacheAll) {
            Object parentKey;
            String memCacheKey;
            ID simpleKey;
            
            // the returnValue is only to populate memCacheAll
            final Collection<T> returnValue = memCacheAll ? new ArrayList<T>() : null;
            
            final Map<String, T> toCache = new TreeMap<String, T>();
            for (T domain : domains) {
                simpleKey = getSimpleKey(domain);
                parentKey = getParentKey(domain);
                memCacheKey = createMemCacheKey(parentKey, simpleKey);
                toCache.put(memCacheKey, domain);
                if (memCacheAll) {
                    returnValue.add(domain);
                }
            }
            updateMemCache(remove, toCache);
            return returnValue;
        }
        return null;
    }

    protected final Collection<T> updateMemCacheAll(Iterable<T> domains) {
        final Collection<T> returnValue = updateMemCache(false, domains);
        if (memCacheAll && null != returnValue) {
            getMemCache().put(createMemCacheKeyAll(), returnValue);
        }
        return returnValue;
    }

    /** Default implementation returns null, override for your hierarchy */
    public String getParentKeyColumnName() {
        return null;
    }
    
    public static String getPrincipalName() {
        return principalName.get();
    }

    /** Default implementation is void, override for your parent field */
    public void setParentKey(T domain, Object parentKey) {
    }
    
    /** Default implementation returns null, override for your creator field */
    public String getCreatedBy(T domain) {
        return null;
    }

    /** Default implementation returns null, override for your creator field */
    public String getCreatedByColumnName() {
        return null;
    }

    /** Default implementation is void, override for your creator field */
    public void _setCreatedBy(T domain, String creator) {
    }
    
    /** Default implementation returns null, override for your updator field */
    public String getUpdatedBy(T domain) {
        return null;
    }

    /** Default implementation returns null, override for your updator field */
    public String getUpdatedByColumnName() {
        return null;
    }

    /** Default implementation is void, override for your updator field */
    public void _setUpdatedBy(T domain, String updator) {
    }
    
    /** Default implementation returns null, override for your created field */
    public Date getCreatedDate(T domain) {
        return null;
    }

    /** Default implementation returns null, override for your created field */
    public String getCreatedDateColumnName() {
        return null;
    }

    /** Default implementation is void, override for your creator field */
    public void _setCreatedDate(T domain, Date date) {
    }
    
    /** Default implementation returns null, override for your updated field */
    public Date getUpdatedDate(T domain) {
        return null;
    }

    /** Default implementation returns null, override for your updated field */
    public String getUpdatedDateColumnName() {
        return null;
    }

    /** Default implementation is void, override for your updated field */
    public void _setUpdatedDate(T domain, Date date) {
    }
    
    /**
     * Override to return your desired column name
     * @return COLUMN_NAME_GEOBOXES_DEFAULT, i.e. "geoboxes"
     */
    protected String getGeoboxesColumnName() {
        return COLUMN_NAME_GEOBOXES_DEFAULT;
    }
    
    /** Override in GeneratedEntityDaoImpl */
    public String getGeoLocationColumnName() {
        return null;
    }

    public DLocation getGeoLocation(T domain) {
        return null;
    }
    
    /** geoboxes are needed to findGeo the nearest entities before sorting them by distance */
    protected void updateGeoModel(T domain, E core) throws IllegalArgumentException {
        final DLocation location = getGeoLocation(domain);
        final Collection<Long> geoboxes = new ArrayList<Long>();

        // if entity has no location, simply set the domain field to empty collection
        if (null != location) {
            for (int bits : boxBits) {
                geoboxes.addAll(Geobox.getTuple(location.getLatitude(), location.getLongitude(), bits));
            }
            setCoreProperty(core, getGeoboxesColumnName(), geoboxes);
        }
    }

    // --- BEGIN Dao methods ---
    
    public int delete(Object parentKey, Iterable<ID> simpleKeys) {
        final int count = doDelete(parentKey, simpleKeys);
        
        // invalidate cache
        final Collection<String> memCacheKeys = createMemCacheKeys(parentKey, simpleKeys);
        updateMemCache(memCacheKeys);
        
        return count;
    }

    public int delete(Iterable<T> domains) {
        final int count = doDelete(domains);
        
        // invalidate cache (remove)
        updateMemCache(true, domains);
        
        return count;
    }

    public boolean delete(Object parentKey, ID simpleKey) {
        final int count = delete(parentKey, Arrays.asList(simpleKey));
        return 1 == count;
    }
    
    public boolean delete(ID simpleKey) {
        final int count = delete(null, Arrays.asList(simpleKey));
        return 1 == count;
    }
    
    public boolean delete(T domain) {
        final int count = delete(getParentKey(domain), Arrays.asList(getSimpleKey(domain)));
        return 1 == count;
    }
    
    public T findByPrimaryKey(Object parentKey, ID simpleKey) {
        // TODO: find in cache
        
        return doFindByPrimaryKey(parentKey, simpleKey);
    }
    
    public T findByPrimaryKey(ID simpleKey) {
        return findByPrimaryKey(null, simpleKey);
    }
    
    public Collection<ID> persist(Iterable<T> domains) {
        final Date currentDate = new Date();
        
        // convert to Core Entity:
        final Collection<E> itrbl = new ArrayList<E>();
        E core;
        for (T d : domains) {
            core = domainToCore(d, currentDate);
            itrbl.add(core);
        }
        
        // batch-persist:
        final Collection<C> keys = persistCore(itrbl);
        
        // collect IDs to return:
        final Collection<ID> ids = new ArrayList<ID>(itrbl.size());
        Iterator<T> ds = domains.iterator();
        T d;
        ID simpleKey;
        for (C c : keys) {
            simpleKey = coreKeyToSimpleKey(c);
            ids.add(simpleKey);
            
            // update domain with generated key?
            d = ds.next();
            if (null == getSimpleKey(d)) {
                setSimpleKey(d, simpleKey);
            }
        }
        
        // update cache (do not remove)
        updateMemCache(false, domains);
        
        return ids;
    }

    public ID persist(T domain) {
        final Iterable<ID> ids = persist(Arrays.asList(domain));
        final ID id = ids.iterator().hasNext() ? ids.iterator().next() : null;
        return id;
    }
    
    public Iterable<T> queryAll() {
        Iterable<T> returnValue = null;

        // try cache first
        if (memCacheAll) {
           returnValue = (Collection<T>) getMemCache().get(createMemCacheKeyAll());
        }
        
        // if no cache or missing, query
        if (null == returnValue) {
            returnValue = queryIterable(false, 0, -1, null, null, null, false, null, false);
            
            // populate memCache, and get the Collection
            if (memCacheAll) {
                returnValue = updateMemCacheAll(returnValue);
                LOG.debug("Queried {} entities for {}.queryAll()", ((Collection)returnValue).size(), getTableName());
            }
            else {
                LOG.debug("Queried entities for {}.queryAll()", getTableName());
            }
        }
        else {
            LOG.debug("Fetched {} entities from memCache {}.queryAll()", ((Collection)returnValue).size(), getTableName());
        }
        return returnValue;
    }
    
    public Iterable<T> queryAll(Object parentKey) {
        Iterable<T> returnValue = null;
        
        // try cache first
        if (memCacheAll) {
            final Collection<T> ts = (Collection<T>) getMemCache().get(createMemCacheKeyAll());
            if (null != ts) {
                
                // filter mem cache by parent key
                final ArrayList<T> domains = new ArrayList<T>();
                returnValue = domains;
                for (T t : ts) {
                    if (null == parentKey || parentKey.equals(getParentKey(t))) {
                        domains.add(t);
                    }
                }
            }
        }

        if (null == returnValue) {
            returnValue = queryIterable(false, 0, -1, (P) parentKey, null, null, false, null, false);
        }
        
        return returnValue;
    }
    
    public Iterable<ID> queryAllKeys() {
        Iterable<ID> returnValue = null;
        
        // try cache first
        if (memCacheAll) {
            final Collection<T> ts = (Collection<T>) getMemCache().get(createMemCacheKeyAll());
            if (null != ts) {
                returnValue = domainsToSimpleKeys(ts);
            }
        }

        if (null == returnValue) {
            returnValue = queryIterableKeys(0, -1, null, null, null, false, null, false);
        }
        
        return returnValue;
    }
    
    public Iterable<ID> queryAllKeys(Object parentKey) {
        Iterable<ID> returnValue = null;
        
        // try cache first
        if (memCacheAll) {
            final Collection<T> ts = (Collection<T>) getMemCache().get(createMemCacheKeyAll());
            if (null != ts) {
                
                // filter mem cache by parent key
                final ArrayList<ID> keys = new ArrayList<ID>();
                returnValue = keys;
                ID simpleKey;
                for (T t : ts) {
                    if (null == parentKey || parentKey.equals(getParentKey(t))) {
                        simpleKey = getSimpleKey(t);
                        keys.add(simpleKey);
                    }
                }
            }
        }

        if (null == returnValue) {
            returnValue = queryIterableKeys(0, -1, (P) parentKey, null, null, false, null, false);
        }
        
        return returnValue;
    }
    
    public Iterable<T> queryByPrimaryKeys(Object parentKey, Iterable<ID> simpleKeys) {
        int entitiesCached = 0, entitiesQueried = 0;
        final Map<ID, T> entities = new TreeMap<ID, T>();
        final TreeSet<ID> missing = new TreeSet<ID>();
        for (ID id : simpleKeys) {
            missing.add(id);
        }
        Collection<String> memCacheKeys = null;
        
        // find in cache
        if (memCacheEntities) {
            
            memCacheKeys = createMemCacheKeys(parentKey, missing);
            try {
                final Map<String,T> cached = getMemCache().getAll(memCacheKeys);
                
                // found entities should not be queried
                ID simpleKey;
                T domain;
                for (Entry<String,T> cacheHit : cached.entrySet()) {
                    domain = cacheHit.getValue();
                    simpleKey = getSimpleKey(domain);
                    missing.remove(simpleKey);
                    
                    // add to found entities
                    entities.put(simpleKey, cacheHit.getValue());
                }
                entitiesCached = entities.size();
                
            } catch (CacheException ex) {
                LOG.warn(String.format("Error getting cached %ss", getTableName()), ex);
            }
            catch (NullPointerException ifNoCache) {
                memCacheEntities = false;
                LOG.warn("Disabling non-functional cache for {}.memCacheEntities", getTableName());
            }
        }
         
        // cache miss?
        if (!missing.isEmpty()) {
            final Iterable<T> queried = doQueryByPrimaryKeys(parentKey, missing);
            final Map<String, T> toCache = new HashMap<String, T>(missing.size());
            
            ID simpleKey;
            String memCacheKey;
            for (T domain : queried) {
                
                // add to returnValue
                simpleKey = getSimpleKey(domain);
                entities.put(simpleKey, domain);
                
                // add to toCache map
                memCacheKey = createMemCacheKey(parentKey, simpleKey);
                toCache.put(memCacheKey, domain);
            }

            // update cache (batch style)
            updateMemCache(false, toCache);
            entitiesQueried = entities.size();
        }
        
        LOG.debug("cached:{}, queried:{}", entitiesCached, entitiesQueried);
        return entities.values();
    }
    
    public CursorPage<T, ID> queryPage(int pageSize, Serializable cursorString) {
        return queryPage(false, pageSize, null, null,
                null, false, null, false,
                cursorString);
    }

    public CursorPage<T, ID> queryPage(int pageSize, 
            String primaryOrderBy, boolean primaryIsAscending, String secondaryOrderBy, boolean secondaryIsAscending, 
            Serializable cursorString) {
        return queryPage(false, pageSize, null, null,
                primaryOrderBy, primaryIsAscending, secondaryOrderBy, secondaryIsAscending,
                cursorString);
    }

    public CursorPage<T, ID> queryInGeobox(float lat, float lng, int bits, int pageSize, 
            String primaryOrderBy, boolean primaryIsAscending, String secondaryOrderBy, boolean secondaryIsAscending, 
            Serializable cursorString, Filter... filters) {
        if (!boxBits.contains(bits)) {
            throw new IllegalArgumentException("Unboxed resolution, hashed are " + boxBits);
        }
        
        final long box = Geobox.getHash(lat, lng, bits);
        
        final Filter geoFilters[] = Arrays.copyOf(filters, filters != null ? filters.length + 1 : 1, Filter[].class);
        geoFilters[geoFilters.length-1] = createEqualsFilter(getGeoboxesColumnName(), box);
        return queryPage(false, pageSize, null, null, primaryOrderBy, primaryIsAscending, secondaryOrderBy, secondaryIsAscending, 
                cursorString, geoFilters);
    }

    public Collection<T> findNearest(final float lat, final float lng, 
            String primaryOrderBy, boolean primaryIsAscending, String secondaryOrderBy, boolean secondaryIsAscending, 
            int offset, int limit, Filter... filters) {
        final DLocation p = new DLocation(lat, lng);
        int size = offset + (0 < limit ? limit : 10000);
        
        // sorting on distance has to be done outside datastore, i.e. here in application:
        Map<Double, T> orderedMap = new TreeMap<Double, T>();
        for (int bits : boxBits) {       
            final CursorPage<T, ID> subList = queryInGeobox(lat, lng, bits, limit,
                    primaryOrderBy, primaryIsAscending, secondaryOrderBy, secondaryIsAscending,
                    null, filters);
            for (T model : subList.getItems()) {
                double d = Geobox.distance(((GeoModel)model).getLocation(), p);
                orderedMap.put(d, model);
            }
            
            if (size <= orderedMap.size()) {
                break;
            }
        }
        // return with specified offset and limit
        final Collection<T> values = orderedMap.values();
        T[] page = (T[]) Arrays.copyOfRange(values.toArray(), 
                Math.min(offset, values.size()), Math.min(size, values.size()));
        return Arrays.asList(page);
    }

    /**
     * Implemented with a call to persist(domains). Feel free to override.
     * @param domains 
     */
    public void update(Iterable<T> domains) {
        // for this implementation, same as persist
        persist(domains);
    }

    /**
     * Implemented with a call to persist(domains). Feel free to override.
     * @param domain
     */
    public void update(T domain) {
        persist(Arrays.asList(domain));
    }
    
    // --- END Dao methods ---
    
    /** Override in GeneratedDaoImpl */
    protected void setDomainProperty(final T domain, final String name, final Object value) {
        if (name.equals(getCreatedByColumnName())) {
            _setCreatedBy(domain, (String) value);
        }
        else if (name.equals(getCreatedDateColumnName())) {
            _setCreatedDate(domain, (Date) value);
        }
        else if (name.equals(getUpdatedByColumnName())) {
            _setUpdatedBy(domain, (String) value);
        }
        else if (name.equals(getUpdatedDateColumnName())) {
            _setUpdatedDate(domain, (Date) value);
        }
        else {
            throw new IllegalArgumentException(String.format("No such property %s for %s", name, getTableName()));
        }
    }
    
    public void setBoxBits(Collection<Integer> boxBits) {
        this.boxBits = boxBits;
    }

    public static void setPrincipalName(String name) {
        principalName.set(name);
    }

    public static void setMemCacheConfig(Map memCacheConfig) {
        DaoImpl.memCacheConfig = memCacheConfig;
    }

}
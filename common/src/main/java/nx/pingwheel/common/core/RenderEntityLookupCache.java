package nx.pingwheel.common.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Render-only UUID lookup acceleration that returns raw live entity identity.
 *
 * <p>The cache scans the renderable entity collection at most once per begun
 * frame/world pass. It retains only entities actually requested by UUID across
 * adjacent passes and validates every retained entity against the live ID
 * lookup before returning it. An entity spawned after a pass's temporary index
 * is built can appear on the next pass. Fresh lookup APIs and presentation
 * canonicalization remain outside this cache.</p>
 */
public final class RenderEntityLookupCache<W, E> {
    /** Supplies the small live-world surface required by this cache. */
    public interface Access<W, E> {
        Iterable<E> entitiesForRendering(W world);

        E getById(W world, int id);

        UUID uuid(E entity);

        int id(E entity);

        boolean removed(E entity);
    }

    private final Access<W, E> access;
    private final Map<UUID, Entry<E>> positiveEntries = new HashMap<>();

    private W world;
    private long epoch;
    private Map<UUID, E> frameIndex;

    public RenderEntityLookupCache(Access<W, E> access) {
        this.access = access;
    }

    /**
     * Starts a render-world pass. A pass owns one lazy full UUID index and a
     * distinct world object invalidates all retained entities.
     */
    public void beginFrame(W world) {
        if (world == null) {
            clear();
            return;
        }

        if (this.world != world) {
            clear();
            this.world = world;
        }

        epoch++;
        positiveEntries.entrySet().removeIf(entry -> entry.getValue().lastUsedEpoch < epoch - 1);
        frameIndex = null;
    }

    /**
     * Finds a renderable entity by UUID without affecting callers that need a
     * fresh world lookup. A cold pass scans at most once; a warm valid entry
     * does not scan the world collection.
     */
    public E findUuid(W world, UUID uuid) {
        if (world == null || uuid == null) {
            return null;
        }

        if (this.world != world) {
            beginFrame(world);
        }

        Entry<E> entry = positiveEntries.get(uuid);
        if (entry != null) {
            if (isLiveMatch(world, uuid, entry.entity)) {
                entry.lastUsedEpoch = epoch;
                return entry.entity;
            }
            positiveEntries.remove(uuid);
        }

        if (frameIndex == null) {
            frameIndex = buildFrameIndex(world);
        }

        E candidate = frameIndex.get(uuid);
        if (!isLiveMatch(world, uuid, candidate)) {
            frameIndex.remove(uuid);
            return null;
        }

        positiveEntries.put(uuid, new Entry<>(candidate, epoch));
        return candidate;
    }

    /** Clears all world and entity references, including the temporary index. */
    public void clear() {
        world = null;
        epoch = 0;
        positiveEntries.clear();
        frameIndex = null;
    }

    private Map<UUID, E> buildFrameIndex(W world) {
        Map<UUID, E> index = new HashMap<>();
        Iterable<E> entities = access.entitiesForRendering(world);
        if (entities == null) {
            return index;
        }

        Iterator<E> iterator = entities.iterator();
        while (iterator.hasNext()) {
            E entity = iterator.next();
            if (entity == null || access.removed(entity)) {
                continue;
            }

            UUID entityUuid = access.uuid(entity);
            if (entityUuid == null || access.getById(world, access.id(entity)) != entity) {
                continue;
            }

            index.putIfAbsent(entityUuid, entity);
        }
        return index;
    }

    private boolean isLiveMatch(W world, UUID uuid, E entity) {
        return entity != null
                && !access.removed(entity)
                && uuid.equals(access.uuid(entity))
                && access.getById(world, access.id(entity)) == entity;
    }

    private static final class Entry<E> {
        private final E entity;
        private long lastUsedEpoch;

        private Entry(E entity, long lastUsedEpoch) {
            this.entity = entity;
            this.lastUsedEpoch = lastUsedEpoch;
        }
    }
}

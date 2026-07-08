package exp.CCnewmods.mah.capture;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable bone-name ↔ id mapping for one entity key.
 *
 * <p>Replaces the old AccurateHitboxes convention of "bone index = position in the
 * OBB list", which was order-fragile: Misanthrope Core's {@code WoundEntry.boneIndex}
 * gets a real identity to point at. Ids are assigned in first-encounter order, which
 * for a given model is the deterministic pre-order render walk; the <b>name</b> is the
 * canonical identity — ids are a compact wire/NBT form and tables are synced whole in
 * later phases, so cross-session id drift is harmless as long as names round-trip.
 *
 * <p>Bone names: vanilla parts use the slash-joined child path stamped at bake time
 * (e.g. {@code root/body/head}); GeckoLib bones use their Blockbench name directly
 * (unique per model by Blockbench's own constraint), matching the conventions already
 * used by Misanthrope Core's CorpseDefinitions.
 *
 * <p>Thread-safe; capture writes on the render thread, gameplay reads elsewhere.
 */
public final class BoneTable {

    /** Returned by {@link #idFor} once the (absurd) 32k-bone budget is exhausted. */
    public static final short OVERFLOW_ID = Short.MAX_VALUE;

    private final List<String> names = new ArrayList<>();
    private final Map<String, Short> ids = new HashMap<>();

    public synchronized short idFor(String boneName) {
        Short existing = ids.get(boneName);
        if (existing != null) return existing;
        if (names.size() >= Short.MAX_VALUE) return OVERFLOW_ID;
        short id = (short) names.size();
        names.add(boneName);
        ids.put(boneName, id);
        return id;
    }

    @Nullable
    public synchronized String nameOf(short id) {
        return id >= 0 && id < names.size() ? names.get(id) : null;
    }

    public synchronized int size() {
        return names.size();
    }

    /** Snapshot of all names in id order, for export/sync/diagnostics. */
    public synchronized List<String> snapshotNames() {
        return List.copyOf(names);
    }
}

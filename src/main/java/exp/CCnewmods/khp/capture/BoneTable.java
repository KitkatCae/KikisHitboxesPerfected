package exp.CCnewmods.khp.capture;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable bone-name ↔ id mapping for one entity key. Names are the identity; ids are first-encounter order and
 * can differ between sessions. Vanilla names are paths like {@code root/body/head}; GeckoLib uses bone names.
 * Thread-safe.
 */
public final class BoneTable {

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

    public synchronized List<String> snapshotNames() {
        return List.copyOf(names);
    }
}

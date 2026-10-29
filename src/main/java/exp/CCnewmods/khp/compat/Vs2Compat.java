package exp.CCnewmods.khp.compat;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * Whether VS2 is installed. Has no VS2 references, so it's safe to call from anywhere, including mixins. Falls
 * back to {@code LoadingModList} while {@code ModList} doesn't exist yet.
 */
public final class Vs2Compat {

    public static final String MODID = "valkyrienskies";

    private static volatile Boolean loaded;

    public static boolean isLoaded() {
        Boolean cached = loaded;
        if (cached != null) return cached;

        ModList modList = ModList.get();
        if (modList != null) {
            boolean result = modList.isLoaded(MODID);
            loaded = result;
            return result;
        }
        LoadingModList loadingModList = LoadingModList.get();
        return loadingModList != null && loadingModList.getModFileById(MODID) != null;
    }

    private Vs2Compat() {
    }
}

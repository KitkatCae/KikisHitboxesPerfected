package exp.CCnewmods.khp.compat;

import exp.CCnewmods.khp.api.MultipartEntityAdapter;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.entity.PartEntity;
import javax.annotation.Nullable;

/**
 * Covers everything built on Forge's own public extraction of the vanilla Ender
 * Dragon's multipart mechanism, {@code net.minecraftforge.entity.PartEntity<T
 * extends Entity>} — confirmed via decompile: constructor takes the parent,
 * {@code getParent()} is public, no other state. This is vanilla-adjacent (Forge,
 * not a specific third-party mod), always present, no mod-loaded gate needed —
 * registered unconditionally.
 *
 * <p>Confirmed this session to cover Ender's Cataclysm's own multipart base class,
 * {@code Cm_Part_Entity<T> extends PartEntity<T>} — so this single adapter handles
 * Cataclysm's bosses with zero Cataclysm-specific code. Also covers the vanilla
 * Ender Dragon itself, and any other mod building on the same Forge base.
 */
public final class VanillaPartEntityAdapter implements MultipartEntityAdapter {

    @Override
    public boolean isPart(Entity entity) {
        return entity instanceof PartEntity<?>;
    }

    @Override
    @Nullable
    public Entity resolveParent(Entity part) {
        return ((PartEntity<?>) part).getParent();
    }
}

package exp.CCnewmods.khp.compat;

import exp.CCnewmods.khp.api.MultipartEntityAdapter;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.entity.PartEntity;
import javax.annotation.Nullable;

/** Forge's {@code PartEntity}: Ender Dragon, Ender's Cataclysm, etc. */
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

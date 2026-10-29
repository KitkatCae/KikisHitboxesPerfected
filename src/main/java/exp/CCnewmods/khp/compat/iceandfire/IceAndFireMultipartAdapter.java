package exp.CCnewmods.khp.compat.iceandfire;

import com.github.alexthe666.iceandfire.entity.EntityMutlipartPart;
import exp.CCnewmods.khp.api.MultipartEntityAdapter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Ice and Fire parts extend {@code Entity}, not {@code PartEntity}. {@code getParent()} only works server-side,
 * so the client finds the parent by its synced UUID.
 */
public final class IceAndFireMultipartAdapter implements MultipartEntityAdapter {

    @Override
    public boolean isPart(Entity entity) {
        return entity instanceof EntityMutlipartPart;
    }

    @Override
    @Nullable
    public Entity resolveParent(Entity part) {
        EntityMutlipartPart mpp = (EntityMutlipartPart) part;

        Entity serverResolved = mpp.getParent();
        if (serverResolved != null) {
            return serverResolved;
        }

        UUID parentId = mpp.getParentId();
        if (parentId == null) {
            return null;
        }
        Level level = part.level();
        if (level.isClientSide() && level instanceof ClientLevel clientLevel) {
            for (Entity candidate : clientLevel.entitiesForRendering()) {
                if (candidate.getUUID().equals(parentId)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
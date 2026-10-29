package exp.CCnewmods.khp.compat.iceandfire.ce;

import com.iafenvoy.iceandfire.entity.EntityMultipartPart;
import exp.CCnewmods.khp.api.MultipartEntityAdapter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/** Community Edition counterpart of {@code IceAndFireMultipartAdapter}. */
public final class IceAndFireCEMultipartAdapter implements MultipartEntityAdapter {

    @Override
    public boolean isPart(Entity entity) {
        return entity instanceof EntityMultipartPart;
    }

    @Override
    @Nullable
    public Entity resolveParent(Entity part) {
        EntityMultipartPart mpp = (EntityMultipartPart) part;

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

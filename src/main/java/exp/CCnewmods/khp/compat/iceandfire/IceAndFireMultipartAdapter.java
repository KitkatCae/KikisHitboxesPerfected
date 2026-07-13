package exp.CCnewmods.khp.compat.iceandfire;

import com.github.alexthe666.iceandfire.entity.EntityMutlipartPart;
import exp.CCnewmods.khp.api.MultipartEntityAdapter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Ice and Fire's {@code EntityMutlipartPart} extends vanilla {@code Entity}
 * directly, not Forge's {@code PartEntity} — confirmed via decompile, so it needs
 * its own adapter rather than being covered by {@code VanillaPartEntityAdapter}.
 *
 * <h3>Correction — {@code getParent()} is server-only, not just "sometimes null"</h3>
 * Decompiling the actual jar shows {@code getParent()} is hard-gated on
 * {@code level() instanceof ServerLevel}:
 * <pre>{@code
 * public Entity getParent() {
 *     UUID id = this.getParentId();
 *     if (id != null && (level = this.level()) instanceof ServerLevel) {
 *         return ((ServerLevel) level).getEntity(id);
 *     }
 *     return null;
 * }
 * }</pre>
 * On the client it unconditionally returns {@code null} — not "null when the parent
 * can't be resolved right now", null <i>always</i>, every call, every tick. That's
 * fine for damage forwarding (server-side already) but it silently broke the one
 * consumer that matters purely client-side: {@code EntityHitboxOverlayMixin}'s F3+B
 * redirect. With this adapter only ever forwarding to {@code getParent()}, every
 * IceAndFire part entity resolved to {@code null} on the client, so
 * {@code MahApi.getCombatSnapshot(part)} never reached the parent-resolution branch,
 * and vanilla's own hitbox box kept drawing for every dragon segment / hydra head /
 * serpent bubble right alongside our overlay on the parent — the two were never
 * mutually exclusive the way the doc comment on {@code EntityHitboxOverlayMixin}
 * describes.
 *
 * <p>{@code getParentId()} (the raw synced {@code UUID}) is public and, being
 * {@code SynchedEntityData}, is genuinely available on both sides — the fix is a
 * client-side fallback: scan {@code level.entitiesForRendering()} (the same
 * iterable vanilla's own entity-outline rendering walks) for a UUID match when the
 * server-only path comes back empty. Kept as a fallback rather than a replacement so
 * the proven server-side {@code ServerLevel} lookup still does the exact-match,
 * zero-scan-cost thing it already does for damage forwarding and combat redirects.
 *
 * <h3>Correction #2 — {@code entitiesForRendering()} is {@code ClientLevel}-only</h3>
 * First pass called it straight off the {@code Level}-typed local, which doesn't
 * compile: {@code entitiesForRendering()} isn't declared on the shared {@code Level}
 * class at all (that class is also the server's superclass) — it's a
 * {@code ClientLevel}-only convenience iterator, same family as the outline/glowing
 * entity render lookups. Fixed by casting after the {@code isClientSide()} check,
 * which is exactly the case where the cast is guaranteed safe.
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

        // Server-side: exact, cheap, already proven — keep it first.
        Entity serverResolved = mpp.getParent();
        if (serverResolved != null) {
            return serverResolved;
        }

        // Client-side fallback: getParent() is server-gated and always returns
        // null here, but the parent UUID itself is synced data and readable.
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
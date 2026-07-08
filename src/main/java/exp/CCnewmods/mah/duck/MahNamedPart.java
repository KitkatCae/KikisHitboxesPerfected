package exp.CCnewmods.mah.duck;

/**
 * Duck interface applied to {@code ModelPart} by ModelPartMixin.
 *
 * <p>Vanilla throws part names away at bake time; MAH stamps them back on:
 * PartDefinitionMixin names every child as its parent bakes, LayerDefinitionMixin
 * names the tree root {@code "root"}. Parts constructed outside the LayerDefinition
 * pipeline keep the default {@code "part"}.
 */
public interface MahNamedPart {

    String mah$getName();

    void mah$setName(String name);
}

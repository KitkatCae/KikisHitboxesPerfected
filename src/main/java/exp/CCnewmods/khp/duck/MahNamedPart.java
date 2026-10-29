package exp.CCnewmods.khp.duck;

/**
 * Part names, stamped at bake time ({@code "root"} for the root, {@code "part"} if built outside a LayerDefinition).
 */
public interface MahNamedPart {

    String mah$getName();

    void mah$setName(String name);
}

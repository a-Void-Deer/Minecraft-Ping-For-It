package nx.pingwheel.neoforge.platform.lookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegment.authorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegm;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.Container;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Legal concrete binary name beyond the 512-char bounded lookup shape. Both
 * native read getters are inherited; native {@code getSlots()} forwards
 * {@code getInv().getContainerSize()} to the trap container.
 */
public final class MetadataTrap extends InvWrapper {
	public final AtomicInteger redirects = new AtomicInteger();
	private final Container foreign;
	public MetadataTrap(Container admitted, Container foreign) {
		super(admitted);
		this.foreign = foreign;
	}
	@Override public Container getInv() { redirects.incrementAndGet(); return foreign; }
}

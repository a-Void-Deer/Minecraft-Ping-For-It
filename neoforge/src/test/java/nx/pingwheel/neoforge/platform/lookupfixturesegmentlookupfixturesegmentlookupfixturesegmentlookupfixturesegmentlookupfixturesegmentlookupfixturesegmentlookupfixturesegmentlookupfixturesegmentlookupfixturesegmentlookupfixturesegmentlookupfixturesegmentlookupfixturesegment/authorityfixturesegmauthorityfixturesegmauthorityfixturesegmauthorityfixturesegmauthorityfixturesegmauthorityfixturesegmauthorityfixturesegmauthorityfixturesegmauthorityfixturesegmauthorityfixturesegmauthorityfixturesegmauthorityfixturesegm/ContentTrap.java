package nx.pingwheel.neoforge.platform.lookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegment.authorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegm;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.Container;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Legal concrete binary name beyond the 512-char bounded lookup shape. The
 * inherited indexed content getter still forwards through the virtual
 * {@code getInv()}; {@code getSlots()} is explicitly overridden to one so the
 * inherited content route stays reachable.
 */
public final class ContentTrap extends InvWrapper {
	public final AtomicInteger redirects = new AtomicInteger();
	private final Container foreign;
	public ContentTrap(Container admitted, Container foreign) {
		super(admitted);
		this.foreign = foreign;
	}
	@Override public Container getInv() { redirects.incrementAndGet(); return foreign; }
	@Override public int getSlots() { return 1; }
}

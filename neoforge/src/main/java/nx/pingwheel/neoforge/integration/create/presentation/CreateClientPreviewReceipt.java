package nx.pingwheel.neoforge.integration.create.presentation;

/** Positive client update evidence, not a claim of server freshness. No values are cached here. */
public interface CreateClientPreviewReceipt {
	boolean pingforit$receivedKinetics();
	long pingforit$kineticReceiptTick();
}

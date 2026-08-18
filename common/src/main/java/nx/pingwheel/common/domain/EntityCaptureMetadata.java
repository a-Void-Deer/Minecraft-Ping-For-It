package nx.pingwheel.common.domain;

import java.util.Objects;

/**
 * Safe metadata retained only for the client-side capture/debug boundary.
 *
 * <p>The metadata is deliberately independent of Minecraft entities and never
 * becomes part of target identity or the network payload. It records the
 * locator representation chosen for the canonical entity and whether a
 * multipart hit was canonicalized to its parent entity.
 */
public record EntityCaptureMetadata(EntityLocator.Kind locatorKind, boolean canonicalizedMultipart) {

	public EntityCaptureMetadata {
		Objects.requireNonNull(locatorKind, "locatorKind");
	}

	/**
	 * Special capture identities are the only ones that need an identity debug
	 * record. Ordinary UUID entity captures remain silent.
	 */
	public boolean isSpecialIdentity() {
		return locatorKind != EntityLocator.Kind.UUID || canonicalizedMultipart;
	}

	/** A privacy-safe strategy label suitable for a debug field. */
	public String locatorStrategy() {
		return locatorKind.tag();
	}
}

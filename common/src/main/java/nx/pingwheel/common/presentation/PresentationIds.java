package nx.pingwheel.common.presentation;

import java.util.regex.Pattern;

public final class PresentationIds {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
	private PresentationIds() {}

	public static void validate(String id) {
		if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("invalid presentation id: " + id);
	}
}

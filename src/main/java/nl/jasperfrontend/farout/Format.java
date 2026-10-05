package nl.jasperfrontend.farout;

import java.util.Locale;

final class Format {
	private Format() {
	}

	/** Server ticks as mm:ss.t, or h:mm:ss.t from an hour on. */
	static String time(long ticks) {
		long tenths = ticks / 2;
		long t = tenths % 10;
		long totalSeconds = tenths / 10;
		long s = totalSeconds % 60;
		long m = totalSeconds / 60 % 60;
		long h = totalSeconds / 3600;
		return h > 0
			? String.format(Locale.ROOT, "%d:%02d:%02d.%d", h, m, s, t)
			: String.format(Locale.ROOT, "%02d:%02d.%d", m, s, t);
	}

	static String blocks(int n) {
		return String.format(Locale.ROOT, "%,d", n);
	}
}

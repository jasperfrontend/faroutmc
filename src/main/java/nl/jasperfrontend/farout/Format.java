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

	/** A signed difference in ticks: -42.1, +1:05.3 (negative = ahead of the personal best). */
	static String delta(long ticks) {
		String sign = ticks < 0 ? "-" : "+";
		long tenths = Math.abs(ticks) / 2;
		long t = tenths % 10;
		long totalSeconds = tenths / 10;
		long s = totalSeconds % 60;
		long m = totalSeconds / 60;
		return m > 0
			? String.format(Locale.ROOT, "%s%d:%02d.%d", sign, m, s, t)
			: String.format(Locale.ROOT, "%s%d.%d", sign, s, t);
	}

	static String blocks(int n) {
		return String.format(Locale.ROOT, "%,d", n);
	}
}

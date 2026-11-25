package com.wesbos.homehq;

/**
 * Decides whether the dashboard should be at normal brightness, dimmed, or dark for the night.
 * Plain Java with no Android imports, so kiosk/test-dimmer.py can exercise it on the host.
 */
final class DimPolicy {
  static final int NORMAL = 0, DIM = 1, NIGHT = 2;

  final boolean enabled;
  /** Idle time before the daytime dim; 0 or less turns the daytime dim off. */
  final long dimAfterMs;
  final int dimLevel;
  /** Night window in minutes after midnight; equal values mean no night window. */
  final int nightStart, nightEnd;
  /** Idle time before the night level applies inside the night window. */
  final long nightAfterMs;
  final int nightLevel;

  DimPolicy(boolean enabled, long dimAfterMs, int dimLevel, int nightStart, int nightEnd,
      long nightAfterMs, int nightLevel) {
    this.enabled = enabled;
    this.dimAfterMs = dimAfterMs;
    this.dimLevel = clampLevel(dimLevel);
    this.nightStart = nightStart;
    this.nightEnd = nightEnd;
    this.nightAfterMs = Math.max(0, nightAfterMs);
    this.nightLevel = clampLevel(nightLevel);
  }

  static int clampLevel(int level) {
    return Math.max(0, Math.min(255, level));
  }

  /** "22:00-06:30" to {1320, 390}. "off", empty or anything malformed gives null (no night). */
  static int[] parseWindow(String s) {
    if (s == null) return null;
    String[] parts = s.trim().split("-");
    if (parts.length != 2) return null;
    int start = parseTime(parts[0]), end = parseTime(parts[1]);
    if (start < 0 || end < 0 || start == end) return null;
    return new int[] {start, end};
  }

  private static int parseTime(String s) {
    String[] hm = s.trim().split(":");
    if (hm.length != 2) return -1;
    try {
      int h = Integer.parseInt(hm[0].trim()), m = Integer.parseInt(hm[1].trim());
      if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
      return h * 60 + m;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  boolean inNight(int minuteOfDay) {
    if (nightStart == nightEnd) return false;
    if (nightStart < nightEnd) return minuteOfDay >= nightStart && minuteOfDay < nightEnd;
    return minuteOfDay >= nightStart || minuteOfDay < nightEnd; // crosses midnight
  }

  int state(int minuteOfDay, long idleMs) {
    if (!enabled) return NORMAL;
    if (inNight(minuteOfDay) && idleMs >= nightAfterMs) return NIGHT;
    if (dimAfterMs > 0 && idleMs >= dimAfterMs) return DIM;
    return NORMAL;
  }

  /** Backlight level for a state, or -1 for normal (Android's own brightness setting). */
  int levelFor(int state) {
    if (state == NIGHT) return nightLevel;
    if (state == DIM) return dimLevel;
    return -1;
  }

  static String name(int state) {
    return state == NIGHT ? "night" : state == DIM ? "dim" : "normal";
  }

  /** Why the page is being told: a fixed set, anything else is reported as "other". */
  static final java.util.List<String> CAUSES =
      java.util.Arrays.asList("timer", "touch", "resume", "leave", "config", "load");

  /**
   * One-way note to the page (no bridge: the page can't call back). Sets data-homehq-backlight
   * (normal/dim/night) and data-homehq-night (1 inside the night window) on <html>, and fires a
   * "homehq:backlight" event with {state, night, cause}. Built only from fixed words.
   */
  static String pageScript(int state, boolean night, String cause) {
    String c = CAUSES.contains(cause) ? cause : "other";
    return "(function(s,n,c){var d=document.documentElement;"
        + "if(d){d.setAttribute('data-homehq-backlight',s);d.setAttribute('data-homehq-night',n?'1':'0');}"
        + "try{window.dispatchEvent(new CustomEvent('homehq:backlight',{detail:{state:s,night:n,cause:c}}));}"
        + "catch(e){}})('" + name(state) + "'," + night + ",'" + c + "')";
  }
}

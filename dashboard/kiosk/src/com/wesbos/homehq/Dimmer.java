package com.wesbos.homehq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LOCAL PATCH, not upstream (upstream keeps native night/dim screens out of the shared kiosk;
 * see .agents/skills/triage-contributions/references/decisions.md, 2026-11-04).
 *
 * Dims the backlight after a period without touches, and turns it down to a night level inside a
 * time window. The dashboard keeps running and Wi-Fi stays up: the screen is never put to sleep.
 * The first touch on a dimmed screen only wakes it; it does not reach the page.
 *
 * Android clamps app-requested brightness to its settings minimum (30 on the MONOCUS BMY-106), so
 * lower levels are written to /sys/class/backlight/<dev>/brightness through su, after Android has
 * finished ramping to the window brightness. While dimmed or dark the level is checked every
 * HOLD_MS and re-applied if something reset it. Without a working su it falls back to the window
 * brightness alone (Android's floor) plus the black night cover. Leaving the dashboard (Settings,
 * another app, screen off) restores Android's own brightness.
 *
 * The page is told about changes one way only (DimPolicy.pageScript): data-homehq-backlight and
 * data-homehq-night on <html>, and a "homehq:backlight" event. The page cannot call the kiosk.
 *
 * Off by default. Settings are saved, like the other kiosk options:
 *   adb shell am start -n com.wesbos.homehq/.MainActivity --ez dimmer true
 *   ... --ei dim_after_min 10 --ei dim_level 10        (dim_after_min 0: no daytime dim)
 *   ... --es night 22:00-06:30 --ei night_after_min 1 --ei night_level 0   (--es night off: none)
 *   ... --ez dimmer false                                (turns it off and restores brightness)
 */
final class Dimmer {
  static final String TAG = MainActivity.TAG;
  static final String PREF_ENABLED = "dimmer";
  static final String PREF_DIM_AFTER = "dim_after_min";
  static final String PREF_DIM_LEVEL = "dim_level";
  static final String PREF_NIGHT = "night";
  static final String PREF_NIGHT_AFTER = "night_after_min";
  static final String PREF_NIGHT_LEVEL = "night_level";
  static final int DEFAULT_DIM_AFTER_MIN = 10, DEFAULT_DIM_LEVEL = 10;
  static final String DEFAULT_NIGHT = "22:00-06:30";
  static final int DEFAULT_NIGHT_AFTER_MIN = 1, DEFAULT_NIGHT_LEVEL = 0;
  static final long TICK_MS = 15000; // at normal brightness: when to check for idle or night
  static final long HOLD_MS = 5000;  // while dimmed or dark: how often to check the level held
  // Android ramps to a new window brightness (about 1 s); write the real level after that.
  static final long SETTLE_MS = 1500;
  static final long SU_TIMEOUT_MS = 3000;
  static final int SU_FAILURES_BEFORE_FALLBACK = 3;
  static final String[] SU_PATHS = {"/system/xbin/su", "/system/bin/su", "/su/bin/su", "/sbin/su"};

  private final Activity activity;
  private final WebView page;
  private final View cover;
  private final Handler handler = new Handler(Looper.getMainLooper());
  // One thread, so backlight writes and checks run in the order they were asked for.
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final PowerManager power;
  private final String su;
  private final File brightness, actual;
  private volatile int suFailures;
  // Bumped on every state change; queued work for an older state is dropped.
  private volatile int generation;
  private DimPolicy policy = new DimPolicy(false, 0, 0, 0, 0, 0, 0);
  private long lastTouch = SystemClock.elapsedRealtime();
  private int state = DimPolicy.NORMAL;
  private boolean resumed, swallowing;
  private Boolean lastNight; // last night flag the page was told

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      evaluate();
      if (resumed && policy.enabled) handler.postDelayed(this, interval());
    }
  };
  private final Runnable rootWrite = this::writeLevel;
  private final Runnable restoreCheck = this::checkRestored;

  Dimmer(Activity activity, FrameLayout root, WebView page) {
    this.activity = activity;
    this.page = page;
    power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
    cover = new View(activity);
    cover.setBackgroundColor(Color.BLACK);
    cover.setVisibility(View.GONE);
    root.addView(cover, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    su = findSu();
    File dir = findBacklight();
    brightness = dir == null ? null : new File(dir, "brightness");
    actual = dir == null ? null : new File(dir, "actual_brightness");
  }

  /** Saves any dimmer extras, then reloads the settings. A change counts as a touch. */
  void applyFlags(Intent intent) {
    SharedPreferences prefs = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    Bundle extras = intent == null ? null : intent.getExtras();
    if (extras != null) {
      SharedPreferences.Editor edit = prefs.edit();
      boolean changed = false;
      if (extras.containsKey(PREF_ENABLED)) {
        edit.putBoolean(PREF_ENABLED, intent.getBooleanExtra(PREF_ENABLED, false));
        changed = true;
      }
      for (String key : new String[] {PREF_DIM_AFTER, PREF_DIM_LEVEL, PREF_NIGHT_AFTER, PREF_NIGHT_LEVEL}) {
        Integer value = intExtra(extras, key);
        if (value != null) {
          edit.putInt(key, value);
          changed = true;
        }
      }
      if (extras.containsKey(PREF_NIGHT)) {
        edit.putString(PREF_NIGHT, String.valueOf(extras.get(PREF_NIGHT)));
        changed = true;
      }
      if (changed) edit.apply();
    }
    policy = load(prefs);
    if (policy.enabled) {
      Log.i(TAG, "Dimmer on: dim after " + policy.dimAfterMs / 60000 + " min to " + policy.dimLevel
          + ", night " + (policy.nightStart == policy.nightEnd ? "off"
              : minutes(policy.nightStart) + "-" + minutes(policy.nightEnd) + " after "
                  + policy.nightAfterMs / 60000 + " min to " + policy.nightLevel)
          + ", backlight " + (brightness == null ? "not found" : brightness.getParent())
          + ", su " + (su == null ? "not found (window brightness only)" : su));
    } else {
      Log.i(TAG, "Dimmer off");
    }
    lastTouch = SystemClock.elapsedRealtime();
    if (!setState(DimPolicy.NORMAL, "config")) notifyPage("config");
    restartTick();
  }

  /** The page (re)loaded: give it the current state, since it starts without the attributes. */
  void pageLoaded() {
    notifyPage("load");
  }

  void resume() {
    resumed = true;
    swallowing = false;
    lastTouch = SystemClock.elapsedRealtime();
    if (!setState(DimPolicy.NORMAL, "resume")) notifyPage("resume");
    // A previous process may have died while the backlight was down.
    handler.removeCallbacks(restoreCheck);
    handler.postDelayed(restoreCheck, SETTLE_MS);
    restartTick();
  }

  void pause() {
    resumed = false;
    swallowing = false;
    handler.removeCallbacks(tick);
    setState(DimPolicy.NORMAL, "leave");
  }

  void destroy() {
    handler.removeCallbacksAndMessages(null);
    worker.shutdown(); // queued restores still run
  }

  /**
   * Called first from MainActivity.dispatchTouchEvent. Any touch counts as activity. On a dimmed
   * screen the whole gesture that wakes it is consumed, so the page never sees it.
   */
  boolean onTouch(MotionEvent e) {
    if (!policy.enabled) return false;
    lastTouch = SystemClock.elapsedRealtime();
    int action = e.getActionMasked();
    boolean ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    if (swallowing) {
      if (ends) swallowing = false;
      return true;
    }
    if (state != DimPolicy.NORMAL) {
      Log.i(TAG, "Touch woke the screen from " + DimPolicy.name(state));
      swallowing = !ends;
      setState(DimPolicy.NORMAL, "touch");
      restartTick();
      return true;
    }
    return false;
  }

  private long interval() {
    return state == DimPolicy.NORMAL ? TICK_MS : HOLD_MS;
  }

  private void restartTick() {
    handler.removeCallbacks(tick);
    if (resumed && policy.enabled) handler.postDelayed(tick, interval());
  }

  private static int minuteNow() {
    Calendar now = Calendar.getInstance();
    return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
  }

  private void evaluate() {
    if (!resumed || !policy.enabled) {
      if (state != DimPolicy.NORMAL) setState(DimPolicy.NORMAL, "timer");
      return;
    }
    int minute = minuteNow();
    int target = policy.state(minute, SystemClock.elapsedRealtime() - lastTouch);
    if (target != state) {
      setState(target, "timer");
      return;
    }
    if (state != DimPolicy.NORMAL) writeLevel(); // re-apply if something reset it
    // The night window opened or closed with no change of brightness (someone is using it).
    if (lastNight == null || lastNight != policy.inNight(minute)) notifyPage("timer");
  }

  /** Tells the page the current state. UI thread only. */
  private void notifyPage(String cause) {
    boolean night = policy.enabled && policy.inNight(minuteNow());
    lastNight = night;
    if (page != null) page.evaluateJavascript(DimPolicy.pageScript(state, night, cause), null);
  }

  /** Returns true when the state changed (and the page was told). */
  private boolean setState(int next, String cause) {
    int previous = state;
    state = next;
    generation++;
    int level = policy.levelFor(next);
    cover.setVisibility(next == DimPolicy.NIGHT ? View.VISIBLE : View.GONE);
    Window window = activity.getWindow();
    WindowManager.LayoutParams attrs = window.getAttributes();
    attrs.screenBrightness = level < 0
        ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : level / 255f;
    window.setAttributes(attrs);
    handler.removeCallbacks(rootWrite);
    handler.removeCallbacks(restoreCheck);
    if (next != DimPolicy.NORMAL) handler.postDelayed(rootWrite, SETTLE_MS);
    else if (previous != DimPolicy.NORMAL) handler.postDelayed(restoreCheck, SETTLE_MS);
    if (previous == next) return false;
    Log.i(TAG, "Backlight " + DimPolicy.name(next) + (level >= 0 ? " (level " + level + ")" : ""));
    notifyPage(cause);
    return true;
  }

  private boolean rootUsable() {
    return su != null && brightness != null && suFailures < SU_FAILURES_BEFORE_FALLBACK;
  }

  /** Writes the current state's level through su, unless the backlight already shows it. */
  private void writeLevel() {
    if (state == DimPolicy.NORMAL || !resumed || !rootUsable()) return;
    final int level = policy.levelFor(state);
    final int gen = generation;
    worker.execute(() -> {
      if (gen != generation || !power.isInteractive()) return;
      int now = readActual();
      if (now == level) return;
      if (now >= 0) Log.i(TAG, "Backlight at " + now + ", setting " + level);
      runSu("echo " + level + " > " + brightness.getPath());
    });
  }

  /** After a return to normal, puts Android's brightness setting back if the backlight is still low. */
  private void checkRestored() {
    if (state != DimPolicy.NORMAL || !rootUsable()) return;
    final int gen = generation;
    worker.execute(() -> {
      if (gen != generation || !power.isInteractive()) return;
      int target = settingBrightness();
      int now = readActual();
      if (target < 0 || now < 0 || now == target) return;
      Log.i(TAG, "Backlight still at " + now + " after restore, setting " + target);
      runSu("echo " + target + " > " + brightness.getPath());
    });
  }

  /** Android's manual brightness setting, or -1 when it is automatic or unreadable. */
  private int settingBrightness() {
    try {
      if (Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
          Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
        return -1;
      }
      return DimPolicy.clampLevel(
          Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS));
    } catch (Settings.SettingNotFoundException e) {
      return -1;
    }
  }

  private int readActual() {
    if (actual == null) return -1;
    try (BufferedReader in = new BufferedReader(new FileReader(actual))) {
      String line = in.readLine();
      return line == null ? -1 : Integer.parseInt(line.trim());
    } catch (IOException | NumberFormatException e) {
      return -1;
    }
  }

  /** Runs one root shell command built only from integers and a sysfs path. Worker thread only. */
  private boolean runSu(String command) {
    Process process = null;
    try {
      process = new ProcessBuilder(su, "-c", command).redirectErrorStream(true).start();
      // Process.waitFor(timeout) requires API 26; bounded polling also works on API 23.
      long deadline = SystemClock.elapsedRealtime() + SU_TIMEOUT_MS;
      while (true) {
        try {
          int rc = process.exitValue();
          if (rc == 0) {
            suFailures = 0;
            return true;
          }
          Log.w(TAG, "su exited " + rc);
          break;
        } catch (IllegalThreadStateException running) {
          if (SystemClock.elapsedRealtime() >= deadline) {
            Log.w(TAG, "su timed out");
            break;
          }
          Thread.sleep(50);
        }
      }
    } catch (IOException e) {
      Log.w(TAG, "Could not run su");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (process != null) process.destroy();
    }
    if (++suFailures == SU_FAILURES_BEFORE_FALLBACK) {
      Log.w(TAG, "su keeps failing; dimming with window brightness only (Android's minimum)");
    }
    return false;
  }

  private static DimPolicy load(SharedPreferences prefs) {
    int[] night = DimPolicy.parseWindow(prefs.getString(PREF_NIGHT, DEFAULT_NIGHT));
    return new DimPolicy(
        prefs.getBoolean(PREF_ENABLED, false),
        prefs.getInt(PREF_DIM_AFTER, DEFAULT_DIM_AFTER_MIN) * 60000L,
        prefs.getInt(PREF_DIM_LEVEL, DEFAULT_DIM_LEVEL),
        night == null ? 0 : night[0],
        night == null ? 0 : night[1],
        prefs.getInt(PREF_NIGHT_AFTER, DEFAULT_NIGHT_AFTER_MIN) * 60000L,
        prefs.getInt(PREF_NIGHT_LEVEL, DEFAULT_NIGHT_LEVEL));
  }

  /** Accepts --ei and --es for numeric settings, since both are easy to type by mistake. */
  private static Integer intExtra(Bundle extras, String key) {
    if (!extras.containsKey(key)) return null;
    Object value = extras.get(key);
    if (value instanceof Integer) return (Integer) value;
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring " + key + "=" + value + " (not a number)");
      return null;
    }
  }

  private static String minutes(int m) {
    return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
  }

  private static String findSu() {
    for (String path : SU_PATHS) {
      if (new File(path).exists()) return path;
    }
    return null;
  }

  private static File findBacklight() {
    File[] devices = new File("/sys/class/backlight").listFiles();
    if (devices == null) return null;
    Arrays.sort(devices);
    for (File d : devices) {
      if (new File(d, "brightness").exists() && new File(d, "actual_brightness").exists()) return d;
    }
    return null;
  }
}

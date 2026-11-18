package app.hootplay;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional: opens Hootplay when a dedicated app button on the remote is
 * pressed. It is off until the viewer switches it on under the TV's
 * Accessibility settings, and it looks at nothing except the scan code of
 * remote key presses; it never reads what is on screen.
 */
public class ShortcutKeyService extends AccessibilityService {
    private static final String TAG = "Hootplay";

    /** Scan codes that remotes send for their video-app shortcut button. */
    private static final int[] SHORTCUT_SCAN_CODES = {569};
    private static final long REOPEN_DELAY_MS = 600;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!isShortcut(event.getScanCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "Shortcut button pressed, opening Hootplay");
            open();
            // Another app may answer the same button a moment later; ask again
            // shortly afterwards so Hootplay is the one left in front.
            handler.postDelayed(this::open, REOPEN_DELAY_MS);
        }
        return true;
    }

    private void open() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static boolean isShortcut(int scanCode) {
        for (int code : SHORTCUT_SCAN_CODES) {
            if (code == scanCode) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: this service only listens for remote key presses.
    }

    @Override
    public void onInterrupt() {
    }
}

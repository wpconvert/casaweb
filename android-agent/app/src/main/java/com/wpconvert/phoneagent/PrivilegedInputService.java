package com.wpconvert.phoneagent;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Runs as a Shizuku UserService (shell UID when Shizuku is started through ADB).
 * This is intentionally separate from the normal APK process so input injection
 * is attempted with the shell privilege instead of the normal application UID.
 * No AccessibilityService is used here.
 */
public class PrivilegedInputService extends IRemoteInputService.Stub {
    private final Object inputManager;
    private final Method injectMethod;
    private final String initError;
    private long activeTouchDownTime = 0L;
    private float activeTouchX = 0f;
    private float activeTouchY = 0f;

    public PrivilegedInputService() {
        Object manager = null;
        Method method = null;
        String error = null;
        try {
            Class<?> clazz = Class.forName("android.hardware.input.InputManager");
            Method getInstance = clazz.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            manager = getInstance.invoke(null);

            method = clazz.getDeclaredMethod(
                    "injectInputEvent",
                    InputEvent.class,
                    int.class
            );
            method.setAccessible(true);
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
        inputManager = manager;
        injectMethod = method;
        initError = error;
    }

    public PrivilegedInputService(Context context) {
        this();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public boolean tap(float x, float y) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
        );
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);

        boolean downOk = inject(down);
        if (!downOk) return false;

        long upTime = SystemClock.uptimeMillis();
        MotionEvent up = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, x, y, 0
        );
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return inject(up);
    }

    @Override
    public synchronized boolean touchDown(float x, float y) {
        // Close any stale gesture before starting a new one.
        if (activeTouchDownTime != 0L) {
            touchUp(activeTouchX, activeTouchY);
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, x, y, 0
        );
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean ok = inject(down);
        if (ok) {
            activeTouchDownTime = now;
            activeTouchX = x;
            activeTouchY = y;
        }
        return ok;
    }

    @Override
    public synchronized boolean touchMove(float x, float y) {
        if (activeTouchDownTime == 0L) return false;
        long now = SystemClock.uptimeMillis();
        MotionEvent move = MotionEvent.obtain(
                activeTouchDownTime, now, MotionEvent.ACTION_MOVE, x, y, 0
        );
        move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean ok = inject(move);
        activeTouchX = x;
        activeTouchY = y;
        return ok;
    }

    @Override
    public synchronized boolean touchUp(float x, float y) {
        if (activeTouchDownTime == 0L) return false;
        long downTime = activeTouchDownTime;
        long now = Math.max(downTime + 1L, SystemClock.uptimeMillis());
        MotionEvent up = MotionEvent.obtain(
                downTime, now, MotionEvent.ACTION_UP, x, y, 0
        );
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean ok = inject(up);
        activeTouchDownTime = 0L;
        activeTouchX = x;
        activeTouchY = y;
        return ok;
    }

    @Override
    public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0
        );
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        if (!inject(down)) return false;

        // Keep the event count low to reduce IPC overhead while preserving a smooth gesture.
        int steps = Math.max(6, Math.min(16, (int) (durationMs / 20L)));
        boolean allOk = true;

        for (int i = 1; i < steps; i++) {
            float fraction = i / (float) steps;
            float x = x1 + ((x2 - x1) * fraction);
            float y = y1 + ((y2 - y1) * fraction);
            long eventTime = downTime + (long) (durationMs * fraction);

            MotionEvent move = MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0
            );
            move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            if (!inject(move)) allOk = false;
        }

        long upTime = downTime + Math.max(1L, durationMs);
        MotionEvent up = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, x2, y2, 0
        );
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return inject(up) && allOk;
    }

    @Override
    public boolean key(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, keyCode, 0
        );
        KeyEvent up = new KeyEvent(
                now, now + 1L, KeyEvent.ACTION_UP, keyCode, 0
        );
        return inject(down) && inject(up);
    }

    @Override
    public String diagnostic() {
        if (inputManager == null || injectMethod == null) {
            return "InputManager unavailable: " + initError;
        }
        return "InputManager ready in privileged UserService";
    }

    private boolean inject(InputEvent event) {
        if (inputManager == null || injectMethod == null) {
            if (event instanceof MotionEvent) ((MotionEvent) event).recycle();
            return false;
        }

        try {
            // ASYNC avoids waiting for Android to finish dispatching every input event.
            // Keep calls serialized by the synchronized gesture methods above.
            Object result = injectMethod.invoke(inputManager, event, 0);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (event instanceof MotionEvent) {
                ((MotionEvent) event).recycle();
            }
        }
    }
}

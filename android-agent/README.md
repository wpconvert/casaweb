# Android Agent V9 — Shizuku Input Test

This build tests RemotePhone control without AccessibilityService.

## Input path

Website -> Cloudflare/WebRTC DataChannel -> RealtimeManager -> persistent Shizuku UserService -> Android InputManager -> foreground app.

AccessibilityService is not used as a fallback.

## Requirement for this test

Install and start Shizuku on the phone. On Android 11+, Shizuku can be started using the phone's Wireless debugging flow without a PC after setup. Grant Web Phone Agent the Shizuku permission when prompted.

Keep Android Accessibility for RemotePhone OFF during this test.

## Why this version should have lower input delay

The privileged input service is persistent. A tap/swipe is sent through one Binder call to the already-running UserService instead of starting a new shell process for every event.

The service then calls Android's hidden InputManager injection API under the Shizuku shell identity.

## Logs

Look for:

- InputInjector: Shizuku UserService connected
- InputInjector: InputManager ready in privileged UserService
- RealtimeManager: CONTROL InputInjector executed=true

If Shizuku is not running, the log will say `Shizuku OFF`.

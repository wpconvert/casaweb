# Web Phone Agent

Android agent prototype for the Casaweb remote-phone project.

Current milestone:
- Native Android app shell
- MediaProjection permission request
- WebRTC Android dependency
- GitHub Actions debug APK build

Next milestones:
1. Start MediaProjection foreground service.
2. Create WebRTC PeerConnection.
3. Connect the publisher to the Casaweb Cloudflare Worker.
4. Publish the screen track to Cloudflare Realtime SFU.
5. Add remote control DataChannel.
6. Add reconnect and device identity.


V8 INPUT TEST
- Control commands intentionally use InputInjector only.
- AccessibilityService is NOT used as a fallback for tap/swipe/back/home/recents.
- This build is for testing whether the device accepts system-wide InputManager injection.

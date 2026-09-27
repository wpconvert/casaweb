package com.wpconvert.phoneagent;

interface IRemoteInputService {
    void destroy();
    boolean tap(float x, float y);
    boolean swipe(float x1, float y1, float x2, float y2, long durationMs);
    boolean key(int keyCode);
    String diagnostic();
}

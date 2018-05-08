/*
 * Copyright (C) 2017 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ceco.lollipop.gravitybox.managers;

import java.util.ArrayList;
import java.util.List;

import com.ceco.lollipop.gravitybox.BroadcastSubReceiver;
import com.ceco.lollipop.gravitybox.Utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.location.GpsStatus;
import android.os.UserManager;
import android.provider.Settings;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class GpsStatusMonitor implements BroadcastSubReceiver {
    public static final String TAG="GB:GpsStatusMonitor";
    private static boolean DEBUG = false;

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    public interface Listener {
        void onGpsEnabledChanged(boolean gpsEnabled);
        void onGpsFixChanged(boolean gpsFixed);
    }

    private Context mContext;
    private boolean mGpsEnabled;
    private boolean mGpsFixed;
    private boolean mGpsStatusTrackingActive;
    private LocationManager mLocMan;
    private List<Listener> mListeners = new ArrayList<>();

    private GpsStatus.Listener mGpsStatusListener = new GpsStatus.Listener() {
        @Override
        public void onGpsStatusChanged(int event) {
            switch (event) {
                case GpsStatus.GPS_EVENT_STARTED:
                    if (DEBUG) log("onGpsStatusChanged: GPS_EVENT_STARTED");
                    break;
                case GpsStatus.GPS_EVENT_STOPPED:
                    if (DEBUG) log("onGpsStatusChanged: GPS_EVENT_STOPPED");
                    if (mGpsFixed) {
                        mGpsFixed = false;
                        notifyGpsFixChanged();
                    }
                    break;
                case GpsStatus.GPS_EVENT_FIRST_FIX:
                    if (DEBUG) log("onGpsStatusChanged: GPS_EVENT_FIRST_FIX");
                    mGpsFixed = true;
                    notifyGpsFixChanged();
                    break;
            }
        }
    };

    protected GpsStatusMonitor(Context context) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");

        mContext = context;
        mLocMan = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        if (intent.getAction().equals(LocationManager.MODE_CHANGED_ACTION)) {
            final boolean oldGpsEnabled = mGpsEnabled;
            final int mode = getLocationMode();
            mGpsEnabled = mode == Settings.Secure.LOCATION_MODE_HIGH_ACCURACY ||
                              mode == Settings.Secure.LOCATION_MODE_SENSORS_ONLY;
            if (mGpsEnabled != oldGpsEnabled) {
                notifyGpsEnabledChanged();
                if (mGpsEnabled) {
                    startGpsStatusTracking();
                } else {
                    stopGpsStatusTracking();
                    if (mGpsFixed) {
                        mGpsFixed = false;
                        notifyGpsFixChanged();
                    }
                }
            }
            if (DEBUG) log("MODE_CHANGED_ACTION received: mode=" + mode + "; " +
                    "mGpsEnabled=" + mGpsEnabled);
        }
    }

    private void startGpsStatusTracking() {
        if (!mGpsStatusTrackingActive) {
            mGpsStatusTrackingActive = mLocMan.addGpsStatusListener(mGpsStatusListener);
            if (DEBUG) log("startGpsStatusTracking: addGpsStatusListener returned: " + mGpsStatusTrackingActive);
        }
    }

    private void stopGpsStatusTracking() {
        if (mGpsStatusTrackingActive) {
            mLocMan.removeGpsStatusListener(mGpsStatusListener);
            mGpsStatusTrackingActive = false;
            if (DEBUG) log("stopGpsStatusTracking: GPS status tracking stopped");
        }
    }

    public boolean isGpsEnabled() {
        return mGpsEnabled;
    }

    public boolean isGpsFixed() {
        return mGpsFixed;
    }

    public void setGpsEnabled(boolean enabled) {
        final int currentUserId = Utils.getCurrentUser();
        if (!isUserLocationRestricted(currentUserId)) {
            try {
                final ContentResolver cr = mContext.getContentResolver();
                final int mode = enabled ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY :
                    Settings.Secure.LOCATION_MODE_BATTERY_SAVING;
                XposedHelpers.callStaticMethod(Settings.Secure.class, "putIntForUser",
                        cr, Settings.Secure.LOCATION_MODE, mode, currentUserId);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    private int getLocationMode() {
        try {
            final int currentUserId = Utils.getCurrentUser();
            final ContentResolver cr = mContext.getContentResolver();
            final int mode = (int) XposedHelpers.callStaticMethod(Settings.Secure.class, "getIntForUser",
                    cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF, currentUserId);
            if (DEBUG) log("getLocationMode: mode=" + mode);
            return mode;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return Settings.Secure.LOCATION_MODE_OFF;
        }
    }

    private boolean isUserLocationRestricted(int userId) {
        try {
            final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            return (boolean) XposedHelpers.callMethod(um, "hasUserRestriction",
                    UserManager.DISALLOW_SHARE_LOCATION,
                    Utils.getUserHandle(userId));
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    private void notifyGpsEnabledChanged() {
        synchronized (mListeners) {
            for (Listener l : mListeners) {
                l.onGpsEnabledChanged(mGpsEnabled);
            }
        }
    }

    private void notifyGpsFixChanged() {
        synchronized (mListeners) {
            for (Listener l : mListeners) {
                l.onGpsFixChanged(mGpsFixed);
            }
        }
    }

    public void registerListener(Listener l) {
        if (l == null) return;
        synchronized (mListeners) {
            if (!mListeners.contains(l)) {
                mListeners.add(l);
            }
        }
    }

    public void unregisterListener(Listener l) {
        if (l == null) return;
        synchronized (mListeners) {
            if (mListeners.contains(l)) {
                mListeners.remove(l);
            }
        }
    }
}
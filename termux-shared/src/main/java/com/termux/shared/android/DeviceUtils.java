package com.termux.shared.android;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

public class DeviceUtils {

    /**
     * Check if the device is a Wear OS watch or not.
     */
    public static boolean isWatchDevice(@NonNull Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    /**
     * Check if the device screen is round or not. Only relevant on watches.
     */
    public static boolean isScreenRound(@NonNull Context context) {
        return context.getResources().getConfiguration().isScreenRound();
    }

}
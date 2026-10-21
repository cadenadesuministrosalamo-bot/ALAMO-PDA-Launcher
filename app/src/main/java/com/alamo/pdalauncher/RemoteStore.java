package com.alamo.pdalauncher;

import android.content.Context;
import android.content.SharedPreferences;

final class RemoteStore {
    private static final String PREFS = "alamo_pda_launcher";
    private static final String KEY_PDA = "pda";
    private static final String KEY_DEVICE_TOKEN = "device_token";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_CONTROL_URL = "control_url";
    private static final String KEY_ADMIN_URL = "admin_url";
    private static final String KEY_SOUND_COMMAND = "sound_command";

    private RemoteStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void saveRegistration(Context context, String pda, String deviceToken,
                                 String apiUrl, String controlUrl, String adminUrl) {
        prefs(context).edit()
                .putString(KEY_PDA, value(pda))
                .putString(KEY_DEVICE_TOKEN, value(deviceToken))
                .putString(KEY_API_URL, value(apiUrl))
                .putString(KEY_CONTROL_URL, value(controlUrl))
                .putString(KEY_ADMIN_URL, value(adminUrl))
                .apply();
    }

    static boolean isRegistered(Context context) {
        return !getPda(context).isEmpty()
                && !getDeviceToken(context).isEmpty()
                && !getApiUrl(context).isEmpty();
    }

    static String getPda(Context context) {
        return prefs(context).getString(KEY_PDA, "");
    }

    static String getDeviceToken(Context context) {
        return prefs(context).getString(KEY_DEVICE_TOKEN, "");
    }

    static String getApiUrl(Context context) {
        return prefs(context).getString(KEY_API_URL, "");
    }

    static String getControlUrl(Context context) {
        return prefs(context).getString(KEY_CONTROL_URL, "");
    }

    static String getAdminUrl(Context context) {
        return prefs(context).getString(KEY_ADMIN_URL, "");
    }

    static void setSoundCommand(Context context, String commandId) {
        prefs(context).edit().putString(KEY_SOUND_COMMAND, value(commandId)).apply();
    }

    static String getSoundCommand(Context context) {
        return prefs(context).getString(KEY_SOUND_COMMAND, "");
    }

    static void clearSoundCommand(Context context) {
        prefs(context).edit().remove(KEY_SOUND_COMMAND).apply();
    }

    private static String value(String input) {
        return input == null ? "" : input.trim();
    }
}

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
    private static final String KEY_APP_NAME = "app_name";
    private static final String KEY_APP_PACKAGE = "app_package";
    private static final String KEY_APP_ACTIVITY = "app_activity";
    private static final String KEY_COMPANY_NAME = "company_name";
    private static final String KEY_LAST_MESSAGE_ID = "last_message_id";
    private static final String KEY_NOTIFIED_MESSAGE_IDS = "notified_message_ids_v23";

    private RemoteStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void saveRegistration(Context context, String pda, String deviceToken,
                                 String apiUrl, String controlUrl, String adminUrl,
                                 String companyName, String appName,
                                 String appPackage, String appActivity) {
        prefs(context).edit()
                .putString(KEY_PDA, value(pda))
                .putString(KEY_DEVICE_TOKEN, value(deviceToken))
                .putString(KEY_API_URL, value(apiUrl))
                .putString(KEY_CONTROL_URL, value(controlUrl))
                .putString(KEY_ADMIN_URL, value(adminUrl))
                .putString(KEY_COMPANY_NAME, value(companyName))
                .putString(KEY_APP_NAME, value(appName))
                .putString(KEY_APP_PACKAGE, value(appPackage))
                .putString(KEY_APP_ACTIVITY, value(appActivity))
                .apply();
    }

    static boolean isRegistered(Context context) {
        return !getPda(context).isEmpty()
                && !getDeviceToken(context).isEmpty()
                && !getApiUrl(context).isEmpty();
    }

    static String getPda(Context context) { return prefs(context).getString(KEY_PDA, ""); }
    static String getDeviceToken(Context context) { return prefs(context).getString(KEY_DEVICE_TOKEN, ""); }
    static String getApiUrl(Context context) { return prefs(context).getString(KEY_API_URL, ""); }
    static String getControlUrl(Context context) { return prefs(context).getString(KEY_CONTROL_URL, ""); }
    static String getAdminUrl(Context context) { return prefs(context).getString(KEY_ADMIN_URL, ""); }
    static String getCompanyName(Context context) { return prefs(context).getString(KEY_COMPANY_NAME, "ALAMO"); }
    static String getAppName(Context context) { return prefs(context).getString(KEY_APP_NAME, "APLICACIÓN OPERATIVA"); }
    static String getAppPackage(Context context) { return prefs(context).getString(KEY_APP_PACKAGE, ""); }
    static String getAppActivity(Context context) { return prefs(context).getString(KEY_APP_ACTIVITY, ""); }

    static void setSoundCommand(Context context, String commandId) {
        prefs(context).edit().putString(KEY_SOUND_COMMAND, value(commandId)).apply();
    }
    static String getSoundCommand(Context context) { return prefs(context).getString(KEY_SOUND_COMMAND, ""); }
    static void clearSoundCommand(Context context) { prefs(context).edit().remove(KEY_SOUND_COMMAND).apply(); }

    static String getLastMessageId(Context context) { return prefs(context).getString(KEY_LAST_MESSAGE_ID, ""); }
    static void setLastMessageId(Context context, String messageId) {
        prefs(context).edit().putString(KEY_LAST_MESSAGE_ID, value(messageId)).apply();
    }

    static boolean wasMessageNotified(Context context, String messageId) {
        String id = value(messageId);
        if (id.isEmpty()) return false;
        String raw = prefs(context).getString(KEY_NOTIFIED_MESSAGE_IDS, "");
        if (raw == null || raw.isEmpty()) return false;
        String[] parts = raw.split("\\|");
        for (String part : parts) if (id.equals(part)) return true;
        return false;
    }

    static void rememberMessageNotified(Context context, String messageId) {
        String id = value(messageId);
        if (id.isEmpty()) return;
        String raw = prefs(context).getString(KEY_NOTIFIED_MESSAGE_IDS, "");
        StringBuilder out = new StringBuilder(id);
        int kept = 1;
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split("\\|");
            for (String part : parts) {
                if (part == null || part.isEmpty() || id.equals(part)) continue;
                if (kept >= 60) break;
                out.append('|').append(part);
                kept++;
            }
        }
        prefs(context).edit().putString(KEY_NOTIFIED_MESSAGE_IDS, out.toString()).apply();
    }

    private static String value(String input) { return input == null ? "" : input.trim(); }
}

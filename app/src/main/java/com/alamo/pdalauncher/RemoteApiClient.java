package com.alamo.pdalauncher;

import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class RemoteApiClient {
    private RemoteApiClient() {}

    static JSONObject redeem(String apiUrl, String pda, String auth) throws Exception {
        Uri url = Uri.parse(apiUrl).buildUpon()
                .appendQueryParameter("api", "launcher")
                .appendQueryParameter("action", "redeem")
                .appendQueryParameter("pda", pda)
                .appendQueryParameter("auth", auth)
                .build();
        return getJson(url.toString());
    }

    static JSONObject poll(String apiUrl, String pda, String deviceToken) throws Exception {
        Uri url = Uri.parse(apiUrl).buildUpon()
                .appendQueryParameter("api", "launcher")
                .appendQueryParameter("action", "poll")
                .appendQueryParameter("pda", pda)
                .appendQueryParameter("deviceToken", deviceToken)
                .build();
        return getJson(url.toString());
    }

    static JSONObject ack(String apiUrl, String pda, String deviceToken,
                          String commandId, String status, String detail) throws Exception {
        Uri.Builder b = Uri.parse(apiUrl).buildUpon()
                .appendQueryParameter("api", "launcher")
                .appendQueryParameter("action", "ack")
                .appendQueryParameter("pda", pda)
                .appendQueryParameter("deviceToken", deviceToken)
                .appendQueryParameter("commandId", commandId)
                .appendQueryParameter("status", status);
        if (detail != null && !detail.trim().isEmpty()) {
            b.appendQueryParameter("detail", detail.trim());
        }
        return getJson(b.build().toString());
    }

    private static JSONObject getJson(String urlText) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(9000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ALAMO-PDA-Launcher/2.0");

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            StringBuilder out = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) out.append(line);
                }
            }

            if (out.length() == 0) {
                throw new IllegalStateException("Respuesta vacía del servidor (HTTP " + code + ").");
            }

            return new JSONObject(out.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

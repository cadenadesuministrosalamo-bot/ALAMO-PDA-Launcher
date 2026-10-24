package com.alamo.pdalauncher;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONObject;

public class LauncherActivity extends Activity {

    private TextView statusText;
    private TextView detailText;
    private Button retryButton;
    private Button controlButton;
    private Button adminButton;
    private volatile boolean authorizedThisLaunch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        statusText = findViewById(R.id.statusText);
        detailText = findViewById(R.id.detailText);
        retryButton = findViewById(R.id.retryButton);
        controlButton = findViewById(R.id.controlButton);
        adminButton = findViewById(R.id.adminButton);

        retryButton.setOnClickListener(v -> {
            if (authorizedThisLaunch) openDigipAuthorized();
            else processIntent(getIntent());
        });
        controlButton.setOnClickListener(v -> openStoredUrl(false));
        adminButton.setOnClickListener(v -> openStoredUrl(true));

        requestNotificationPermissionIfNeeded();

        if (RemoteStore.isRegistered(this)) {
            RemoteMonitorService.start(this);
        }

        processIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        authorizedThisLaunch = false;
        processIntent(intent);
    }

    private void processIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null
                || !"alamopda".equalsIgnoreCase(data.getScheme())
                || !"open".equalsIgnoreCase(data.getHost())) {
            showProtected();
            return;
        }

        String auth = safe(data.getQueryParameter("auth"));
        String pda = safe(data.getQueryParameter("pda"));
        String apiUrl = safe(data.getQueryParameter("api"));

        if (auth.isEmpty() || pda.isEmpty() || apiUrl.isEmpty()) {
            showProtected();
            return;
        }

        redeemAuthorization(apiUrl, pda, auth);
    }

    private void redeemAuthorization(String apiUrl, String pda, String auth) {
        statusText.setText("Validando acceso…");
        detailText.setText("Control PDA está autorizando la apertura de la aplicación operativa.");
        retryButton.setVisibility(View.GONE);
        controlButton.setVisibility(View.GONE);
        adminButton.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                JSONObject json = RemoteApiClient.redeem(apiUrl, pda, auth);
                if (!json.optBoolean("ok", false)) {
                    String message = json.optString("message", "Autorización rechazada.");
                    runOnUiThread(() -> showProtected(message));
                    return;
                }

                String deviceToken = json.optString("deviceToken", "");
                String serverApiUrl = json.optString("apiUrl", apiUrl);
                String controlUrl = json.optString("controlUrl", "");
                String adminUrl = json.optString("adminUrl", "");
                String companyName = json.optString("companyName", "ALAMO");
                String appName = json.optString("appName", "APLICACIÓN OPERATIVA");
                String appPackage = json.optString("appPackage", "");
                String appActivity = json.optString("appActivity", "");
                boolean appEnabled = json.optBoolean("appEnabled", true);

                if (!appEnabled) {
                    runOnUiThread(() -> showProtected("La aplicación operativa está deshabilitada por Administración."));
                    return;
                }

                if (deviceToken.trim().isEmpty()) {
                    runOnUiThread(() -> showProtected("El servidor no entregó una credencial válida al Launcher."));
                    return;
                }

                RemoteStore.saveRegistration(
                        this,
                        pda,
                        deviceToken,
                        serverApiUrl,
                        controlUrl,
                        adminUrl,
                        companyName,
                        appName,
                        appPackage,
                        appActivity
                );
                RemoteMonitorService.start(this);
                authorizedThisLaunch = true;

                runOnUiThread(this::openDigipAuthorized);

            } catch (Exception e) {
                String message = "No se pudo validar con Control PDA. Revise la conexión de red. " + safeMessage(e);
                runOnUiThread(() -> showProtected(message));
            }
        }).start();
    }

    private void openDigipAuthorized() {
        if (!authorizedThisLaunch) {
            showProtected();
            return;
        }

        String appName = safe(RemoteStore.getAppName(this));
        String appPackage = safe(RemoteStore.getAppPackage(this));
        String appActivity = safe(RemoteStore.getAppActivity(this));
        if (appName.isEmpty()) appName = "APLICACIÓN OPERATIVA";

        statusText.setText("Abriendo " + appName + "…");
        detailText.setText("Acceso autorizado por Control PDA.");
        retryButton.setVisibility(View.GONE);

        if (appPackage.isEmpty()) {
            showDigipError("No hay aplicación configurada.", "Administración debe configurar el package Android de la aplicación operativa.");
            return;
        }

        if (!appActivity.isEmpty()) {
            try {
                Intent explicitIntent = new Intent(Intent.ACTION_MAIN);
                explicitIntent.setComponent(new ComponentName(appPackage, appActivity));
                explicitIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                explicitIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(explicitIntent);
                finish();
                return;
            } catch (Exception ignored) {
                // Respaldo mediante PackageManager debajo.
            }
        }

        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(appPackage);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                finish();
                return;
            }
        } catch (Exception ignored) {}

        showDigipError(
                "No se pudo abrir " + appName + ".",
                "Verifique que el package " + appPackage + " y la Activity configurada sean correctos."
        );
    }

    private void showProtected() {
        showProtected("Para abrir la aplicación operativa, inicie sesión en Control PDA y use el botón configurado por Administración.");
    }

    private void showProtected(String detail) {
        authorizedThisLaunch = false;
        statusText.setText("Acceso protegido");
        String finalDetail = detail == null ? "" : detail;
        if (!notificationsAllowed()) {
            finalDetail += "\n\nActive las notificaciones de ALAMO PDA Launcher para recibir avisos de Administración mientras trabaja en la aplicación operativa.";
        }
        detailText.setText(finalDetail);
        retryButton.setVisibility(View.GONE);
        updateRecoveryButtons();
    }

    private void showDigipError(String title, String detail) {
        statusText.setText(title);
        detailText.setText(detail);
        retryButton.setText("REINTENTAR APLICACIÓN");
        retryButton.setVisibility(View.VISIBLE);
        updateRecoveryButtons();
    }

    private void updateRecoveryButtons() {
        boolean registered = RemoteStore.isRegistered(this);
        // V2.2: los accesos de recuperación siempre permanecen visibles.
        // Si todavía no existe registro, al pulsarlos se explica cómo habilitarlos.
        controlButton.setVisibility(View.VISIBLE);
        adminButton.setVisibility(View.VISIBLE);
        controlButton.setAlpha(registered ? 1.0f : 0.72f);
        adminButton.setAlpha(registered ? 1.0f : 0.72f);
    }

    private void openStoredUrl(boolean admin) {
        String url = admin ? RemoteStore.getAdminUrl(this) : RemoteStore.getControlUrl(this);
        if (url == null || url.trim().isEmpty()) {
            statusText.setText("Recuperación no registrada");
            detailText.setText("Este Launcher todavía no recibió los datos de este PDA. Abra Control PDA desde el navegador, inicie sesión y pulse ABRIR APLICACIÓN una vez para registrar el equipo.");
            return;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            detailText.setText("No se pudo abrir el navegador. " + safeMessage(e));
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2107);
        }
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 2107 && !notificationsAllowed()) {
            detailText.setText("Notificaciones desactivadas. Sin este permiso, Administración no podrá avisarle mientras esté trabajando fuera de Control PDA. Puede habilitarlas en Ajustes > Aplicaciones > ALAMO PDA Launcher > Notificaciones.");
        } else if (requestCode == 2107 && RemoteStore.isRegistered(this)) {
            RemoteMonitorService.start(this);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}

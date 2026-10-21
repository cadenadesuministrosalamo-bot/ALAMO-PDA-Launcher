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

    private static final String DIGIP_PACKAGE = "digip.patagoniawms.prod";
    private static final String DIGIP_SPLASH =
            "digip.patagoniawms.UI.Activities.activitySplash";

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
        detailText.setText("Control PDA está autorizando la apertura de DIGIP WMS.");
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
                        adminUrl
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

        statusText.setText("Abriendo DIGIP WMS…");
        detailText.setText("Acceso autorizado por Control PDA.");
        retryButton.setVisibility(View.GONE);

        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(DIGIP_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                finish();
                return;
            }
        } catch (SecurityException | ActivityNotFoundException ignored) {
            // Respaldo debajo.
        } catch (Exception ignored) {
            // Respaldo debajo.
        }

        try {
            Intent explicitIntent = new Intent(Intent.ACTION_MAIN);
            explicitIntent.setComponent(new ComponentName(DIGIP_PACKAGE, DIGIP_SPLASH));
            explicitIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            explicitIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(explicitIntent);
            finish();

        } catch (SecurityException e) {
            showDigipError(
                    "Android bloqueó el acceso a DIGIP WMS.",
                    "La aplicación está instalada, pero Android rechazó su apertura. " + safeMessage(e)
            );
        } catch (ActivityNotFoundException e) {
            showDigipError(
                    "No se encontró DIGIP WMS.",
                    "Verifique que el paquete " + DIGIP_PACKAGE + " esté instalado en este PDA."
            );
        } catch (Exception e) {
            showDigipError("No se pudo abrir DIGIP WMS.", safeMessage(e));
        }
    }

    private void showProtected() {
        showProtected("Para abrir DIGIP WMS, inicie sesión en Control PDA y pulse ABRIR DIGIP WMS.");
    }

    private void showProtected(String detail) {
        authorizedThisLaunch = false;
        statusText.setText("Acceso protegido");
        detailText.setText(detail);
        retryButton.setVisibility(View.GONE);
        updateRecoveryButtons();
    }

    private void showDigipError(String title, String detail) {
        statusText.setText(title);
        detailText.setText(detail);
        retryButton.setText("REINTENTAR DIGIP");
        retryButton.setVisibility(View.VISIBLE);
        updateRecoveryButtons();
    }

    private void updateRecoveryButtons() {
        boolean registered = RemoteStore.isRegistered(this);
        controlButton.setVisibility(registered ? View.VISIBLE : View.GONE);
        adminButton.setVisibility(registered ? View.VISIBLE : View.GONE);
    }

    private void openStoredUrl(boolean admin) {
        String url = admin ? RemoteStore.getAdminUrl(this) : RemoteStore.getControlUrl(this);
        if (url == null || url.trim().isEmpty()) {
            detailText.setText("Todavía no hay una URL registrada. Abra este Launcher una vez desde Control PDA.");
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

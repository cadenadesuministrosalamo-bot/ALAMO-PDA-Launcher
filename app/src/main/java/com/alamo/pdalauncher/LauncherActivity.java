package com.alamo.pdalauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class LauncherActivity extends Activity {

    private static final String DIGIP_PACKAGE = "digip.patagoniawms.prod";
    private static final String DIGIP_SPLASH =
            "digip.patagoniawms.UI.Activities.activitySplash";

    private TextView statusText;
    private TextView detailText;
    private Button retryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        statusText = findViewById(R.id.statusText);
        detailText = findViewById(R.id.detailText);
        retryButton = findViewById(R.id.retryButton);

        retryButton.setOnClickListener(v -> openDigip());

        // Espera mínima para que Android termine de abrir esta Activity
        // antes de saltar a la aplicación objetivo.
        new Handler(Looper.getMainLooper()).postDelayed(this::openDigip, 120);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openDigip();
    }

    private void openDigip() {
        statusText.setText("Abriendo DIGIP WMS…");
        detailText.setText("Buscando la aplicación instalada.");
        retryButton.setVisibility(View.GONE);

        // Método 1: pedir a Android el launcher real del paquete instalado.
        // Esto es preferible porque sigue funcionando si DIGIP cambia el nombre
        // interno de su Activity en una futura actualización.
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(DIGIP_PACKAGE);

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                finish();
                return;
            }
        } catch (SecurityException | ActivityNotFoundException ignored) {
            // Continuamos con el componente conocido.
        } catch (Exception ignored) {
            // Continuamos con el componente conocido.
        }

        // Método 2: respaldo con la Activity conocida del APK analizado.
        try {
            Intent explicitIntent = new Intent(Intent.ACTION_MAIN);
            explicitIntent.setComponent(new ComponentName(DIGIP_PACKAGE, DIGIP_SPLASH));
            explicitIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            explicitIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            startActivity(explicitIntent);
            finish();
            return;

        } catch (SecurityException e) {
            showError("Android bloqueó el acceso a DIGIP WMS.",
                    "La aplicación está instalada, pero su Activity de inicio no permite " +
                    "ser abierta desde otra aplicación. Detalle: " + safeMessage(e));
        } catch (ActivityNotFoundException e) {
            showError("No se encontró DIGIP WMS.",
                    "Verifique que el paquete " + DIGIP_PACKAGE + " esté instalado en este PDA.");
        } catch (Exception e) {
            showError("No se pudo abrir DIGIP WMS.",
                    safeMessage(e));
        }
    }

    private void showError(String title, String detail) {
        statusText.setText(title);
        detailText.setText(detail);
        retryButton.setVisibility(View.VISIBLE);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}

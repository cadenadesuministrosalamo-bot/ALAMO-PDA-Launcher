package com.alamo.pdalauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RemoteMonitorService extends Service {

    public static final String ACTION_LOCAL_FOUND = "com.alamo.pdalauncher.LOCAL_FOUND";
    private static final String CHANNEL_STATUS = "alamo_remote_status";
    private static final String CHANNEL_ALARM = "alamo_remote_alarm";
    private static final int NOTIFICATION_ID = 7167;
    private static final long POLL_MS = 20000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pollRunning = new AtomicBoolean(false);

    private MediaPlayer mediaPlayer;
    private ToneGenerator toneGenerator;
    private Runnable toneRunnable;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private int previousAlarmVolume = -1;
    private String activeSoundCommand = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollOnce();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, RemoteMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void markFound(Context context) {
        Intent intent = new Intent(context, RemoteMonitorService.class);
        intent.setAction(ACTION_LOCAL_FOUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(NOTIFICATION_ID, buildStatusNotification(false));
        handler.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_LOCAL_FOUND.equals(intent.getAction())) {
            handleLocalFound();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopSoundInternal();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollOnce() {
        if (!RemoteStore.isRegistered(this)) return;
        if (!pollRunning.compareAndSet(false, true)) return;

        final String apiUrl = RemoteStore.getApiUrl(this);
        final String pda = RemoteStore.getPda(this);
        final String deviceToken = RemoteStore.getDeviceToken(this);

        executor.execute(() -> {
            try {
                JSONObject json = RemoteApiClient.poll(apiUrl, pda, deviceToken);
                if (!json.optBoolean("ok", false)) return;

                String action = json.optString("action", "NONE").toUpperCase();
                String commandId = json.optString("commandId", "");

                if ("SONAR".equals(action) && !commandId.isEmpty()) {
                    handler.post(() -> ensureSounding(commandId));
                } else if ("DETENER".equals(action) && !commandId.isEmpty()) {
                    handler.post(() -> {
                        stopSoundInternal();
                        ackAsync(commandId, "DETENIDO", "Alarma detenida por orden ADMIN.");
                    });
                }
            } catch (Exception ignored) {
                // Sin red: no destruimos el registro; se reintenta en el siguiente ciclo.
            } finally {
                pollRunning.set(false);
            }
        });
    }

    private void ensureSounding(String commandId) {
        if (commandId.equals(activeSoundCommand) && isSoundActive()) return;

        stopSoundInternal();
        activeSoundCommand = commandId;
        RemoteStore.setSoundCommand(this, commandId);

        try {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }

            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            if (alarmUri != null) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                mediaPlayer.setDataSource(this, alarmUri);
                mediaPlayer.setLooping(true);
                mediaPlayer.prepare();
                mediaPlayer.start();
            } else {
                startToneFallback();
            }
        } catch (Exception e) {
            startToneFallback();
        }

        startVibration();
        acquireWakeLock();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildStatusNotification(true));

        ackAsync(commandId, "SONANDO", "Alarma y vibración activadas por el Launcher.");
    }

    private boolean isSoundActive() {
        return (mediaPlayer != null && mediaPlayer.isPlaying()) || toneGenerator != null;
    }

    private void startToneFallback() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            toneRunnable = new Runnable() {
                @Override
                public void run() {
                    if (toneGenerator == null) return;
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
                    handler.postDelayed(this, 1200);
                }
            };
            handler.post(toneRunnable);
        } catch (Exception ignored) {}
    }

    private void startVibration() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = new long[]{0, 700, 350, 700, 350};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 1));
            } else {
                vibrator.vibrate(pattern, 1);
            }
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ALAMO:RemoteAlarm");
            wakeLock.acquire(60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void stopSoundInternal() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Exception ignored) {}
        mediaPlayer = null;

        try {
            if (toneRunnable != null) handler.removeCallbacks(toneRunnable);
            if (toneGenerator != null) toneGenerator.release();
        } catch (Exception ignored) {}
        toneGenerator = null;
        toneRunnable = null;

        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) {}
        vibrator = null;

        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;

        try {
            if (audioManager != null && previousAlarmVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
            }
        } catch (Exception ignored) {}
        previousAlarmVolume = -1;
        audioManager = null;

        activeSoundCommand = "";
        RemoteStore.clearSoundCommand(this);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildStatusNotification(false));
    }

    private void handleLocalFound() {
        String commandId = !activeSoundCommand.isEmpty()
                ? activeSoundCommand
                : RemoteStore.getSoundCommand(this);
        stopSoundInternal();
        if (!commandId.isEmpty()) {
            ackAsync(commandId, "ENCONTRADO", "PDA encontrado desde la notificación del dispositivo.");
        }
    }

    private void ackAsync(String commandId, String status, String detail) {
        if (!RemoteStore.isRegistered(this) || commandId == null || commandId.trim().isEmpty()) return;
        final String apiUrl = RemoteStore.getApiUrl(this);
        final String pda = RemoteStore.getPda(this);
        final String token = RemoteStore.getDeviceToken(this);

        executor.execute(() -> {
            try {
                RemoteApiClient.ack(apiUrl, pda, token, commandId, status, detail);
            } catch (Exception ignored) {}
        });
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS,
                "ALAMO Control PDA",
                NotificationManager.IMPORTANCE_LOW
        );
        status.setDescription("Mantiene disponible el control remoto del PDA.");
        nm.createNotificationChannel(status);

        NotificationChannel alarm = new NotificationChannel(
                CHANNEL_ALARM,
                "Búsqueda de PDA",
                NotificationManager.IMPORTANCE_HIGH
        );
        alarm.setDescription("Aviso cuando Administración solicita localizar este PDA.");
        nm.createNotificationChannel(alarm);
    }

    private Notification buildStatusNotification(boolean sounding) {
        String channel = sounding ? CHANNEL_ALARM : CHANNEL_STATUS;
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);

        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(sounding ? "🔊 ALAMO · PDA en búsqueda" : "ALAMO · Control PDA activo")
                .setContentText(sounding
                        ? "Este equipo está siendo localizado. Pulse ENCONTRADO para detener."
                        : "Control remoto disponible para " + RemoteStore.getPda(this))
                .setOngoing(!sounding)
                .setAutoCancel(false)
                .setPriority(sounding ? Notification.PRIORITY_MAX : Notification.PRIORITY_LOW);

        if (sounding) {
            Intent found = new Intent(this, RemoteActionReceiver.class);
            found.setAction(ACTION_LOCAL_FOUND);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 77, found, flags);
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_mylocation,
                    "PDA ENCONTRADO",
                    pi
            ).build());
        }

        return b.build();
    }
}

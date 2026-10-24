package com.alamo.pdalauncher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RemoteMonitorService extends Service {

    public static final String ACTION_LOCAL_FOUND = "com.alamo.pdalauncher.LOCAL_FOUND";
    private static final String CHANNEL_STATUS = "alamo_remote_status_v22";
    private static final String CHANNEL_ALARM = "alamo_remote_alarm_v22";
    private static final String CHANNEL_MSG_NORMAL = "alamo_messages_normal_v22";
    private static final String CHANNEL_MSG_IMPORTANT = "alamo_messages_important_v22";
    private static final String CHANNEL_MSG_URGENT = "alamo_messages_urgent_v22";
    private static final int NOTIFICATION_ID = 7167;
    private static final int MESSAGE_NOTIFICATION_BASE = 7300;
    private static final long DEFAULT_POLL_MS = 10000L;

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

    private final Runnable pollRunnable = this::pollOnce;

    public static void start(Context context) {
        Intent intent = new Intent(context, RemoteMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void markFound(Context context) {
        Intent intent = new Intent(context, RemoteMonitorService.class);
        intent.setAction(ACTION_LOCAL_FOUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
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
        if (intent != null && ACTION_LOCAL_FOUND.equals(intent.getAction())) handleLocalFound();
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
    public void onTaskRemoved(Intent rootIntent) {
        // Algunos Android/OEM eliminan servicios al cerrar apps recientes.
        // Programamos un reinicio ligero para mantener notificaciones y control remoto.
        if (RemoteStore.isRegistered(this)) {
            try {
                Intent restart = new Intent(this, BootReceiver.class);
                restart.setAction(BootReceiver.ACTION_RESTART_MONITOR);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi = PendingIntent.getBroadcast(this, 7168, restart, flags);
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5000L, pi);
            } catch (Exception ignored) {}
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void scheduleNext(long delayMs) {
        long safe = Math.max(2500L, Math.min(delayMs, 60000L));
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, safe);
    }

    private void pollOnce() {
        if (!RemoteStore.isRegistered(this)) {
            scheduleNext(20000L);
            return;
        }
        if (!pollRunning.compareAndSet(false, true)) {
            scheduleNext(5000L);
            return;
        }

        final String apiUrl = RemoteStore.getApiUrl(this);
        final String pda = RemoteStore.getPda(this);
        final String deviceToken = RemoteStore.getDeviceToken(this);

        executor.execute(() -> {
            long nextPoll = DEFAULT_POLL_MS;
            try {
                JSONObject json = RemoteApiClient.poll(apiUrl, pda, deviceToken);
                if (json.optBoolean("ok", false)) {
                    nextPoll = json.optLong("nextPollMs", DEFAULT_POLL_MS);

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

                    JSONObject message = json.optJSONObject("message");
                    if (message != null) {
                        String id = message.optString("id", "");
                        if (!id.isEmpty() && !id.equals(RemoteStore.getLastMessageId(this))) {
                            String type = message.optString("tipo", "AVISO_GENERAL");
                            String priority = message.optString("prioridad", "NORMAL");
                            String title = message.optString("asunto", "Mensaje de Administración");
                            String body = message.optString("mensaje", "");
                            String sender = message.optString("enviadoPor", "ADMIN");
                            handler.post(() -> {
                                if (showOperationalMessage(id, type, priority, title, body, sender)) {
                                    messageAckAsync(id);
                                }
                            });
                        }
                    }
                }
            } catch (Exception ignored) {
                nextPoll = 15000L;
            } finally {
                pollRunning.set(false);
                final long delay = nextPoll;
                handler.post(() -> scheduleNext(delay));
            }
        });
    }

    private boolean showOperationalMessage(String id, String type, String priority,
                                        String title, String body, String sender) {
        if (!notificationsAllowed()) return false;
        RemoteStore.setLastMessageId(this, id);
        String p = priority == null ? "NORMAL" : priority.trim().toUpperCase();
        String channel = "URGENTE".equals(p) ? CHANNEL_MSG_URGENT
                : ("IMPORTANTE".equals(p) ? CHANNEL_MSG_IMPORTANT : CHANNEL_MSG_NORMAL);

        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(RemoteStore.getControlUrl(this)));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, id.hashCode(), open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);

        String company = RemoteStore.getCompanyName(this);
        String prefix = "URGENTE".equals(p) ? "⚠ " : "🔔 ";
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(prefix + company + " · " + title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body + "\nEnviado por: " + sender))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority("URGENTE".equals(p) ? Notification.PRIORITY_MAX
                        : ("IMPORTANTE".equals(p) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if ("URGENTE".equals(p) || "IMPORTANTE".equals(p)) {
                b.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE | Notification.DEFAULT_LIGHTS);
            } else {
                b.setDefaults(Notification.DEFAULT_LIGHTS);
            }
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(MESSAGE_NOTIFICATION_BASE + Math.abs(id.hashCode() % 500), b.build());
            return true;
        }
        return false;
    }

    private void messageAckAsync(String messageId) {
        if (!RemoteStore.isRegistered(this) || messageId == null || messageId.trim().isEmpty()) return;
        final String apiUrl = RemoteStore.getApiUrl(this);
        final String pda = RemoteStore.getPda(this);
        final String token = RemoteStore.getDeviceToken(this);
        executor.execute(() -> {
            try { RemoteApiClient.messageAck(apiUrl, pda, token, messageId); }
            catch (Exception ignored) {}
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
            } else startToneFallback();
        } catch (Exception e) { startToneFallback(); }

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
                @Override public void run() {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 1));
            else vibrator.vibrate(pattern, 1);
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
        try { if (mediaPlayer != null) { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); mediaPlayer.release(); } } catch (Exception ignored) {}
        mediaPlayer = null;
        try { if (toneRunnable != null) handler.removeCallbacks(toneRunnable); if (toneGenerator != null) toneGenerator.release(); } catch (Exception ignored) {}
        toneGenerator = null; toneRunnable = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        vibrator = null;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        try { if (audioManager != null && previousAlarmVolume >= 0) audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0); } catch (Exception ignored) {}
        previousAlarmVolume = -1; audioManager = null;
        activeSoundCommand = "";
        RemoteStore.clearSoundCommand(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildStatusNotification(false));
    }

    private void handleLocalFound() {
        String commandId = !activeSoundCommand.isEmpty() ? activeSoundCommand : RemoteStore.getSoundCommand(this);
        stopSoundInternal();
        if (!commandId.isEmpty()) ackAsync(commandId, "ENCONTRADO", "PDA encontrado desde la notificación del dispositivo.");
    }

    private void ackAsync(String commandId, String status, String detail) {
        if (!RemoteStore.isRegistered(this) || commandId == null || commandId.trim().isEmpty()) return;
        final String apiUrl = RemoteStore.getApiUrl(this);
        final String pda = RemoteStore.getPda(this);
        final String token = RemoteStore.getDeviceToken(this);
        executor.execute(() -> {
            try { RemoteApiClient.ack(apiUrl, pda, token, commandId, status, detail); }
            catch (Exception ignored) {}
        });
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel status = new NotificationChannel(CHANNEL_STATUS, "Estado de Control PDA", NotificationManager.IMPORTANCE_DEFAULT);
        status.setDescription("Notificación permanente que confirma que ALAMO PDA Launcher está activo.");
        status.setSound(null, null);
        status.enableVibration(false);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);

        NotificationChannel alarm = new NotificationChannel(CHANNEL_ALARM, "Búsqueda de PDA", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("Aviso cuando Administración solicita localizar este PDA.");
        alarm.enableVibration(true);
        nm.createNotificationChannel(alarm);

        NotificationChannel normal = new NotificationChannel(CHANNEL_MSG_NORMAL, "Mensajes operativos", NotificationManager.IMPORTANCE_DEFAULT);
        normal.setDescription("Avisos generales de Administración.");
        normal.setSound(null, null);
        normal.enableVibration(false);
        nm.createNotificationChannel(normal);

        NotificationChannel important = new NotificationChannel(CHANNEL_MSG_IMPORTANT, "Mensajes importantes", NotificationManager.IMPORTANCE_HIGH);
        important.setDescription("Llamados y cambios operativos importantes.");
        important.enableVibration(true);
        important.setVibrationPattern(new long[]{0, 250, 150, 250});
        nm.createNotificationChannel(important);

        NotificationChannel urgent = new NotificationChannel(CHANNEL_MSG_URGENT, "Mensajes urgentes", NotificationManager.IMPORTANCE_HIGH);
        urgent.setDescription("Avisos urgentes de Administración.");
        urgent.enableVibration(true);
        urgent.setVibrationPattern(new long[]{0, 700, 250, 700, 250, 700});
        nm.createNotificationChannel(urgent);
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private Notification buildStatusNotification(boolean sounding) {
        String channel = sounding ? CHANNEL_ALARM : CHANNEL_STATUS;
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
        String company = RemoteStore.getCompanyName(this);
        String pda = RemoteStore.getPda(this);
        Intent openLauncher = new Intent(this, LauncherActivity.class);
        openLauncher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) openFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(this, 7167, openLauncher, openFlags);

        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(sounding ? "🔊 " + company + " · PDA en búsqueda" : company + " Control PDA · " + (pda.isEmpty() ? "Launcher activo" : pda + " ONLINE"))
                .setContentText(sounding
                        ? "Este equipo está siendo localizado. Pulse ENCONTRADO para detener."
                        : "Launcher activo · Mensajes y control remoto habilitados")
                .setContentIntent(openPi)
                .setOngoing(!sounding)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(sounding ? Notification.PRIORITY_MAX : Notification.PRIORITY_LOW);

        if (!sounding && !RemoteStore.getControlUrl(this).isEmpty()) {
            Intent control = new Intent(Intent.ACTION_VIEW, Uri.parse(RemoteStore.getControlUrl(this)));
            int controlFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) controlFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent controlPi = PendingIntent.getActivity(this, 7169, control, controlFlags);
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view, "ABRIR CONTROL PDA", controlPi).build());
        }

        if (sounding) {
            Intent found = new Intent(this, RemoteActionReceiver.class);
            found.setAction(ACTION_LOCAL_FOUND);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 77, found, flags);
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_mylocation, "PDA ENCONTRADO", pi).build());
        }
        return b.build();
    }
}

package com.eagle.recorderdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class RecordService extends Service {

    private static final String TAG = RecordService.class.getSimpleName();

    public static final String SERVICE_ACTION = "imotor.intent.action.RECORD_SERVICE";

    private final IBinder mLocalBinder = new LocalBinder();
    private static final String CHANNEL_ID = "com.imotor.recorderdemo";
    private static final int NOTIFICATION_ID = 1;

    private SoundRecorder mSoundRecorder;
    private Notification.Builder mNotificationBuilder;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        mSoundRecorder  = SoundRecorder.getInstance(this.getApplicationContext());
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(c);
        Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_DEFAULT);
        i.setClassName(getPackageName(), "com.imotor.recorderdemo.MainActivity");
        PendingIntent pi = PendingIntent.getActivity(this,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setContentTitle(getString(R.string.app_name));
        mNotificationBuilder = builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildNotification(false));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (SERVICE_ACTION.equals(action)) {
                Log.d(TAG, "auto recording");
                if (!mSoundRecorder.isRecording()) {
                    mSoundRecorder.startRecording();
                }
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    public void setRecordListener(SoundRecorder.RecordStateListener listener) {
        mSoundRecorder.setRecordingListener(listener);
    }

    public SoundRecorder getSoundRecorder () {
        return mSoundRecorder;
    }

    public SoundRecorder.State getRecordState() {
        return mSoundRecorder.getState();
    }

    public void startRecord() {
        mSoundRecorder.startRecording();
    }

    public void stopRecord() {
        mSoundRecorder.stopRecording();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mLocalBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestory");
        cancelNotification();
        if (mSoundRecorder.isRecording()) {
            mSoundRecorder.stopRecording();
        }
        mSoundRecorder = null;
    }

    class LocalBinder extends Binder {
        RecordService getService() {
            return RecordService.this;
        }
    }


    private Notification buildNotification(boolean enabled) {
        mNotificationBuilder.setContentTitle(getString(R.string.app_name));
        return mNotificationBuilder.build();
    }

    private void showNotification(boolean enabled) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, buildNotification(enabled));
    }

    private void cancelNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

}

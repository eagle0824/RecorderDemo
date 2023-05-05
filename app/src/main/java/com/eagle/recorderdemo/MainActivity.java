package com.eagle.recorderdemo;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;


import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.View;
import android.widget.TextView;


public class MainActivity extends AppCompatActivity implements SoundRecorder.RecordStateListener {

    private TextView mState;
    private ServiceConnection mSeviceConnection;
    private RecordService mServce;
    private VUMeter mVUMeter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkPermission();
        mState = (TextView) findViewById(R.id.display_state);
        mVUMeter = (VUMeter) findViewById(R.id.uvMeter);
        bindService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    public void onViewClick(View view) {
        switch(view.getId()) {
            case R.id.start:
                doStartRecord();
                break;
            case R.id.stop:
                doStopRecord();
                break;
        };
    }

    @Override
    public void onRecordState(SoundRecorder.State state) {
        switch (state) {
            case IDLE:
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mVUMeter.setRecorder(null);
                        mState.setText("IDLE");
                    }
                });
                break;
            case RECORDING:
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mState.setText("RECORDING");
                        mVUMeter.setRecorder(mServce.getSoundRecorder());
                    }
                });
                break;
            case ERROR:
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mVUMeter.setRecorder(null);
                        mState.setText("ERROR");
                    }
                });
                break;
        }
    }

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {

        }
    };

    private void doStartRecord() {
        if (mServce != null) {
            mServce.startRecord();
        }
    }

    private void doStopRecord() {
        if (mServce != null) {
            mServce.stopRecord();
        }
    }

    private void checkPermission() {
        int hasWriteStoragePermission = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!(hasWriteStoragePermission == PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(MainActivity.this, PERMISSIONS,
                    101);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        //通过requestCode来识别是否同一个请求
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            } else {
                finish();
            }
        }
    }

    static String[] PERMISSIONS = {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
    };

    private void bindService() {
        if (mSeviceConnection == null) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(MainActivity.this, RecordService.class));
            intent.setPackage(getPackageName());
            mSeviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    mServce = ((RecordService.LocalBinder)service).getService();
                    mServce.setRecordListener(MainActivity.this);
                    onRecordState(mServce.getRecordState());
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {

                }
            };
            bindService(intent, mSeviceConnection, Context.BIND_AUTO_CREATE);
        }

    }

    private void unbindService() {
        if (mSeviceConnection != null) {
            unbindService(mSeviceConnection);
            mSeviceConnection = null;
        }
    }
}
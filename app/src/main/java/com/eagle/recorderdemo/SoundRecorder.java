/*
 * Copyright (C) 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.eagle.recorderdemo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * A helper class to provide methods to record audio input from the MIC to the internal storage
 * and to playback the same recorded audio file.
 */
public class SoundRecorder {

    private static final String TAG = "RecorderDemo";


    private static final int RECORDING_RATE = 32000; // can go up to 44K, if needed
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO;
    private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;

    private static int BUFFER_SIZE = AudioRecord
            .getMinBufferSize(RECORDING_RATE, CHANNEL_IN, FORMAT);

    private static final int CHANNELS_OUT = AudioFormat.CHANNEL_OUT_MONO;


    public static final String DIR_NAME = "rec";
    private File mOutputFileName = null;
    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final Context mContext;
    private State mState = State.IDLE;

    private AsyncTask<Void, Void, Void> mRecordingAsyncTask;
    private AsyncTask<Void, Void, Void> mPlayingAsyncTask;
    private RecordStateListener mListener;
    private AcousticEchoCanceler canceler;

    private volatile double mMaxAmp = 0.0f;

    public enum State {
        IDLE, RECORDING, ERROR, PLAYING
    }

    private static SoundRecorder sInstatnce;

    public static SoundRecorder getInstance(Context context) {
        if (sInstatnce == null) {
            sInstatnce = new SoundRecorder(context);
        }
        return sInstatnce;
    }

    private SoundRecorder(Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        mContext = context;
    }

    public boolean isRecording() {
        return mState == State.RECORDING;
    }

    public void setRecordingListener(RecordStateListener listener) {
        mListener = listener;
    }

    public State getState() {
        return mState;
    }

    public int getMaxAmplitude() {
        if (mState != State.RECORDING) {
            return 0;
        }
        return (int)mMaxAmp;
    }

    /**
     * Starts recording from the MIC.
     */
    public void startRecording() {
        if (mState != State.IDLE) {
            Log.w(TAG, "Requesting to start recording while state was not IDLE");
            return;
        }

        mRecordingAsyncTask = new AsyncTask<Void, Void, Void>() {

            private AudioRecord mAudioRecord;
            private AudioTrack mAudioTrack;

            @Override
            protected void onPreExecute() {
                boolean result = createRecordFile();
                if (!result) {
                    mOutputFileName = null;
                    setCurrentState(State.ERROR);
                } else {
                    setCurrentState(State.RECORDING);
                }
            }

            @Override
            protected Void doInBackground(Void... params) {
                if (mOutputFileName == null) {
                    Log.e(TAG, "create output file failed ");
                    return null;
                }

                Log.d(TAG, "BUFFER SIZE : " + BUFFER_SIZE);
                mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        RECORDING_RATE, CHANNEL_IN, FORMAT, BUFFER_SIZE);

                int minTrackBufferSize = AudioTrack.getMinBufferSize(RECORDING_RATE, CHANNEL_OUT, FORMAT);
                boolean hasPlaybackTrack = hasPlaybackTrack();

                if (!hasPlaybackTrack) {
                    Log.d(TAG, "not config playback track please set prop 'recorddemo.audiotrack' true");
                }

                if (hasPlaybackTrack) {
                    mAudioTrack = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(FORMAT)
                                    .setSampleRate(RECORDING_RATE)
                                    .setChannelMask(CHANNEL_OUT)
                                    .build())
                            .setBufferSizeInBytes(minTrackBufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                }

                initAEC(mAudioRecord.getAudioSessionId());

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(mOutputFileName);
                    if (FORMAT == AudioFormat.ENCODING_PCM_FLOAT) {
                        float[] buffer = new float[BUFFER_SIZE];
                        if (hasPlaybackTrack) {
                            mAudioTrack.play();
                        }
                        mAudioRecord.startRecording();
                        while (!isCancelled()) {
                            int read = mAudioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                            byte[] data = convertTo16Bit(buffer);
                            //Log.d(TAG, " read  ====> " + read + " data size : " + data.length);
                            fos.write(data, 0, data.length);
                        }
                    } else {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        if (hasPlaybackTrack) {
                            mAudioTrack.play();
                        }
                        mAudioRecord.startRecording();
                        while (!isCancelled()) {
                            int read = mAudioRecord.read(buffer, 0, buffer.length);
                            long v = 0;
                            // 将 buffer 内容取出，进行平方和运算
                            for (int i = 0; i < read; i++) {
                                v += buffer[i] * buffer[i];
                            }
                            // 平方和除以数据总长度，得到音量大小。
                            double mean = v / (double) read;
                            mMaxAmp = 10 * Math.log10(mean);

                            if (hasPlaybackTrack) {
                                if (read > 0) {
                                    int result = mAudioTrack.write(buffer, 0, read);
                                    //Log.d(TAG, " write result  ====> " + result);
                                }
                            }
                            fos.write(buffer, 0, read);
                        }
                    }
                } catch (IOException | NullPointerException | IndexOutOfBoundsException e) {
                    Log.e(TAG, "Failed to record data: " + e, e);
                    e.printStackTrace();
                    setCurrentState(State.ERROR);
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                    if (hasPlaybackTrack) {
                        mAudioTrack.stop();;
                        mAudioTrack.release();
                        mAudioTrack = null;
                    }
                    mAudioRecord.release();
                    mAudioRecord = null;
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                mState = State.IDLE;
                mRecordingAsyncTask = null;
            }

            @Override
            protected void onCancelled() {
                if (mState == State.RECORDING) {
                    Log.d(TAG, "Stopping the recording ...");
                    setCurrentState(State.IDLE);
                } else {
                    Log.w(TAG, "Requesting to stop recording while state was not RECORDING");
                }
                mRecordingAsyncTask = null;
            }

        };

        mRecordingAsyncTask.execute();
    }

    //消除回音
    public boolean initAEC(int audioSession) {
        if (canceler != null) {
            return false;
        }

        AudioEffect.Descriptor[] descriptors = AudioEffect.queryEffects();
        for (AudioEffect.Descriptor descriptor : descriptors) {
            Log.d(TAG, " descriptor : " + descriptor.name + " type : "  + descriptor.type + " uuid : " + descriptor.uuid + " " + descriptor.connectMode);
        }
        Log.d(TAG, "AcousticEchoCanceler isAvailable : " + AcousticEchoCanceler.isAvailable());
        if (!AcousticEchoCanceler.isAvailable()){

            return false;
        }
        canceler = AcousticEchoCanceler.create(audioSession);
        if (canceler!=null) {
            canceler.setEnabled(true);
            return canceler.getEnabled();
        }else{
            return false;
        }

    }

    private void setCurrentState(State state) {
        if (mState != state) {
            mState = state;
            if (mState != State.RECORDING) {
                mMaxAmp = 0.0f;
            }
            if (mListener != null) {
                Log.d(TAG, "onRecordState : " + state);
                mListener.onRecordState(state);
            }
        }
    }


    private byte[] convertTo16Bit(float[] data) {
        short[] shortValue = new short[data.length];
        byte[] byte16bit = new byte[shortValue.length *2];
        for (int i=0; i<data.length; i++) {
            shortValue[i] = (short) (32768 * data[i]);
        }
        byte16bit = toByteArray(shortValue);
        return byte16bit;
    }

    private byte[] toByteArray(short[] src) {
        int count = src.length;
        byte[] dest = new byte[count << 1];
        for (int i=0; i< count; i++) {
            dest[i * 2] = (byte) (src[i]);
            dest[i * 2 + 1] = (byte) (src[i] >> 8);
        }
        return dest;
    }

    private boolean createRecordFile() {
        String sampleDirPath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator+DIR_NAME+File.separator;
        File sampleDir = new File(sampleDirPath);
        Log.d(TAG, "sampleDirPath : " + sampleDirPath);
        if (!sampleDir.exists()) {
            sampleDir.mkdirs();
        }
        if (!sampleDir.canWrite()) {
            sampleDir = new File("/sdcard/"+DIR_NAME);
            sampleDir.mkdirs();
        }// Workaround for broken sdcard support on the device.



        Log.d(TAG, "sampleDir : " + sampleDir.getAbsolutePath());
        String fileName = String.format(Locale.US, "%s_%s.pcm", "recording", getDisplayTime());
        try {
            mOutputFileName = new File(sampleDir, fileName);
            mOutputFileName.createNewFile();
        } catch (IOException e) {
            mOutputFileName = null;
            Log.w(TAG, "error", e);
            return false;
        }
        return true;
    }

    public void stopRecording() {
        if (mRecordingAsyncTask != null) {
            mRecordingAsyncTask.cancel(true);
        }
    }

    public void stopPlaying() {
        if (mPlayingAsyncTask != null) {
            mPlayingAsyncTask.cancel(true);
        }
    }

    /**
     * Starts playback of the recorded audio file.
     */
    public void startPlay() {
        if (mState != State.IDLE) {
            Log.w(TAG, "Requesting to play while state was not IDLE");
            return;
        }

        if (!mOutputFileName.exists()) {
            // there is no recording to play
            return;
        }
        final int intSize = AudioTrack.getMinBufferSize(RECORDING_RATE, CHANNELS_OUT, FORMAT);

        mPlayingAsyncTask = new AsyncTask<Void, Void, Void>() {

            private AudioTrack mAudioTrack;

            @Override
            protected void onPreExecute() {
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                        mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0 /* flags */);
                mState = State.PLAYING;
            }

            @Override
            protected Void doInBackground(Void... params) {
                try {
                    mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, RECORDING_RATE,
                            CHANNELS_OUT, FORMAT, intSize, AudioTrack.MODE_STREAM);
                    byte[] buffer = new byte[intSize * 2];
                    FileInputStream in = null;
                    BufferedInputStream bis = null;
                    mAudioTrack.setVolume(AudioTrack.getMaxVolume());
                    mAudioTrack.play();
                    try {
                        in = new FileInputStream(mOutputFileName);
                        bis = new BufferedInputStream(in);
                        int read;
                        while (!isCancelled() && (read = bis.read(buffer, 0, buffer.length)) > 0) {
                            mAudioTrack.write(buffer, 0, read);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to read the sound file into a byte array", e);
                    } finally {
                        try {
                            if (in != null) {
                                in.close();
                            }
                            if (bis != null) {
                                bis.close();
                            }
                        } catch (IOException e) { /* ignore */}

                        mAudioTrack.release();
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to start playback", e);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                cleanup();
            }

            @Override
            protected void onCancelled() {
                cleanup();
            }

            private void cleanup() {
                mState = State.IDLE;
                mPlayingAsyncTask = null;
            }
        };

        mPlayingAsyncTask.execute();
    }

    public interface RecordStateListener {
        public void onRecordState(State state);
    }
    /**
     * Cleans up some resources related to {@link AudioTrack} and {@link AudioRecord}
     */
    public void cleanup() {
        Log.d(TAG, "cleanup() is called");
        stopPlaying();
        stopRecording();
    }


    public static String getDisplayTime() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
        Date date = new Date(System.currentTimeMillis());
        return simpleDateFormat.format(date);
    }

    private boolean hasPlaybackTrack() {
        return SystemProperties.getBoolean("recorddemo.audiotrack", false);
    }
}
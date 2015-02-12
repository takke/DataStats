package jp.takke.datastats;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import jp.takke.util.MyLog;


public class MainActivity extends Activity {

    private boolean mPreparingConfigArea = false;

    private ILayerService mServiceIF = null;

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {

            MyLog.d("onServiceConnected[" + name + "]");

            mServiceIF = ILayerService.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

            MyLog.d("onServiceDisconnected[" + name + "]");

            mServiceIF = null;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prepareStartStopButton();

        prepareConfigArea();

        preparePreviewArea();

        final Intent service = new Intent(this, LayerService.class);
        bindService(service, mServiceConnection, Context.BIND_AUTO_CREATE);
    }


    @Override
    protected void onDestroy() {

        if (mServiceIF != null) {
            unbindService(mServiceConnection);
        }

        super.onDestroy();
    }


    private void prepareStartStopButton() {

        // start
        {
            final Button button = (Button) findViewById(R.id.start_button);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    doRestartService();
                }
            });
        }

        // stop
        {
            final Button button = (Button) findViewById(R.id.stop_button);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    if (mServiceIF != null) {

                        try {
                            mServiceIF.stop();
                        } catch (RemoteException e) {
                            MyLog.e(e);
                        }

                        unbindService(mServiceConnection);

                        mServiceIF = null;
                    }
                }
            });
        }
    }


    private void doRestartService() {

        if (mServiceIF != null) {
            // restart
            try {
                mServiceIF.restart();
            } catch (RemoteException e) {
                MyLog.e(e);
            }
        } else {
            // rebind
            final Intent service = new Intent(MainActivity.this, LayerService.class);
            bindService(service, mServiceConnection, Context.BIND_AUTO_CREATE);
        }

        final TextView kbText = (TextView) findViewById(R.id.preview_kb_text);
        kbText.setText("-");
    }


    private void prepareConfigArea() {

        mPreparingConfigArea = true;

        final SeekBar seekBar = (SeekBar) findViewById(R.id.posSeekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final TextView textView = (TextView) findViewById(R.id.pos_text);
                textView.setText("" + progress + "%");

                final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                final SharedPreferences.Editor editor = pref.edit();
                editor.putInt(C.PREF_KEY_X_POS, progress);
                editor.apply();

                // restart
                doRestartService();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        final int xPos = pref.getInt(C.PREF_KEY_X_POS, 100);
        seekBar.setProgress(xPos);

        // Interval Spinner
        {
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item);
            final int[] intervals = new int[]{500, 1000, 1500, 2000};
            for (int interval : intervals) {
                adapter.add("" + (interval / 1000) + "." + (interval % 1000 / 100) + "sec");
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            final Spinner spinner = (Spinner) findViewById(R.id.intervalSpinner);
            spinner.setAdapter(adapter);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                    if (mPreparingConfigArea) {
                        return;
                    }
                    MyLog.d("onItemSelected: [" + position + "]");

                    final int interval = intervals[position];

                    final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                    final SharedPreferences.Editor editor = pref.edit();
                    editor.putInt(C.PREF_KEY_INTERVAL_MSEC, interval);
                    editor.apply();

                    // restart
                    doRestartService();
                }


                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });
            final int currentIntervalMsec = pref.getInt(C.PREF_KEY_INTERVAL_MSEC, 1000);
            for (int i = 0; i < intervals.length; i++) {
                if (currentIntervalMsec == intervals[i]) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }

        // Max Speed[KB] Spinner (Bar)
        {
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item);
            final int[] speeds = new int[]{10, 50, 100, 500, 1024, 2048, 5120, 10240};
            for (int s : speeds) {

                if (s >= 1024) {
                    adapter.add("" + (s / 1024) + "MB/s");
                } else {
                    adapter.add("" + s + "KB/s");
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            final Spinner spinner = (Spinner) findViewById(R.id.maxSpeedSpinner);
            spinner.setAdapter(adapter);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                    if (mPreparingConfigArea) {
                        return;
                    }
                    MyLog.d("onItemSelected: [" + position + "]");

                    final int speed = speeds[position];

                    final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                    final SharedPreferences.Editor editor = pref.edit();
                    editor.putInt(C.PREF_KEY_BAR_MAX_SPEED_KB, speed);
                    editor.apply();

                    // restart
                    doRestartService();
                }


                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });
            final int currentSpeed = pref.getInt(C.PREF_KEY_BAR_MAX_SPEED_KB, 100);
            for (int i = 0; i < speeds.length; i++) {
                if (currentSpeed == speeds[i]) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }

        mPreparingConfigArea = false;
    }


    private void preparePreviewArea() {

        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final TextView kbText = (TextView) findViewById(R.id.preview_kb_text);
                kbText.setText("" + (progress/10) + "." + (progress%10) + "KB");

                restartWithPreview(progress/10, progress%10);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        final int[] sampleButtonIds = new int[]{R.id.sample_1kb_button,
                R.id.sample_20kb_button,
                R.id.sample_50kb_button,
                R.id.sample_80kb_button,
                R.id.sample_100kb_button
        };
        final int[] samples = new int[]{1, 20, 50, 80, 100};

        for (int i = 0; i < sampleButtonIds.length; i++) {

            final Button button = (Button) findViewById(sampleButtonIds[i]);
            final int kb = samples[i];

            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    restartWithPreview(kb, 0);
                }
            });
        }
    }


    private void restartWithPreview(long kb, long kbd1) {

        if (mServiceIF != null) {
            // preview
            try {
                mServiceIF.startSnapshot(kb * 1024 + kbd1*100);
            } catch (RemoteException e) {
                MyLog.e(e);
            }
        }


        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        seekBar.setProgress((int) (kb*10+kbd1));

        final TextView kbText = (TextView) findViewById(R.id.preview_kb_text);
        kbText.setText(kb + "." + kbd1 + "KB");
    }
}

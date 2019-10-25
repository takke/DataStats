package jp.takke.datastats;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ZoomControls;

import jp.takke.util.MyLog;
import jp.takke.util.TkConfig;


public class MainActivity extends Activity {

    private static final int REQUEST_SYSTEM_OVERLAY = 1;

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

        MyLog.d("MainActivity.onCreate");

        prepareConfigArea();

        preparePreviewArea();

        // M以降の権限対応
        if (!OverlayUtil.checkOverlayPermission(this)) {
            requestOverlayPermission();
        } else {
            doBindService();
        }

        // 外部ストレージのログファイルを削除する
        MyLog.deleteBigExternalLogFile();
    }

    private void doBindService() {

        final Intent serviceIntent = new Intent(this, LayerService.class);

        // start
        MyLog.d("MainActivity: startService of LayerService");
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // bind
        MyLog.d("MainActivity: bindService of LayerService");
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void requestOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            final Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_SYSTEM_OVERLAY);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == REQUEST_SYSTEM_OVERLAY) {
            if (OverlayUtil.checkOverlayPermission(this)) {
                MyLog.i("MainActivity: overlay permission OK");
                doBindService();
            } else {
                MyLog.i("MainActivity: overlay permission NG");
                finish();
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {

        // プレビュー状態の解除
        if (mServiceIF != null) {
            // restart
            try {
                mServiceIF.restart();
            } catch (RemoteException e) {
                MyLog.e(e);
            }
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        if (mServiceIF != null) {
            unbindService(mServiceConnection);
        }

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // start
        {
            final MenuItem item = menu.add(R.string.config_start);
//            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            item.setOnMenuItemClickListener(item1 -> {

                doRestartService();
                return true;
            });
        }

        // stop
        {
            final MenuItem item = menu.add(R.string.config_stop);
//            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            item.setOnMenuItemClickListener(item1 -> {

                doStopService();
                return true;
            });
        }

        // restart
        {
            final MenuItem item = menu.add(R.string.config_restart);
//            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            item.setOnMenuItemClickListener(item1 -> {

                doStopService();
                doRestartService();
                return true;
            });
        }

        // debug
        {
            final MenuItem item = menu.add(R.string.config_debug_mode);
//            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            item.setOnMenuItemClickListener(item1 -> {

                TkConfig.debugMode = !TkConfig.debugMode;
                item1.setChecked(TkConfig.debugMode);

                // save
                final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                final SharedPreferences.Editor edit = pref.edit();
                edit.putBoolean(C.PREF_KEY_DEBUG_MODE, TkConfig.debugMode);
                edit.apply();

                return true;
            });

            item.setCheckable(true);
            item.setChecked(TkConfig.debugMode);
        }


        return true;
    }

    private void doStopService() {
        
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

    private void doRestartService() {

        if (mPreparingConfigArea) {
            MyLog.d("MainActivity.doRestartService -> cancel (preparing)");
            return;
        }
        MyLog.d("MainActivity.doRestartService");

        if (mServiceIF != null) {
            // restart
            try {
                mServiceIF.restart();
            } catch (RemoteException e) {
                MyLog.e(e);
            }
        } else {
            // rebind
            doBindService();
        }

        final TextView kbText = findViewById(R.id.preview_kb_text);
        kbText.setText("-");
    }

    @SuppressLint("SetTextI18n")
    private void prepareConfigArea() {

        mPreparingConfigArea = true;
        
        Config.loadPreferences(this);

        // auto start
        final CheckBox autoStartOnBoot = findViewById(R.id.autoStartOnBoot);
        autoStartOnBoot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            final SharedPreferences.Editor editor = pref.edit();
            editor.putBoolean(C.PREF_KEY_START_ON_BOOT, isChecked);
            editor.apply();
        });
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        final boolean startOnBoot = pref.getBoolean(C.PREF_KEY_START_ON_BOOT, true);
        autoStartOnBoot.setChecked(startOnBoot);

        // hide when in fullscreen
        final CheckBox hideCheckbox = findViewById(R.id.hideWhenInFullscreen);
        hideCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            final SharedPreferences pref1 = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            final SharedPreferences.Editor editor = pref1.edit();
            editor.putBoolean(C.PREF_KEY_HIDE_WHEN_IN_FULLSCREEN, isChecked);
            editor.apply();
        });
        hideCheckbox.setChecked(Config.hideWhenInFullscreen);

        // Logarithm bar
        final CheckBox logCheckbox = findViewById(R.id.logarithmCheckbox);
        logCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            final SharedPreferences pref12 = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            final SharedPreferences.Editor editor = pref12.edit();
            editor.putBoolean(C.PREF_KEY_LOGARITHM_BAR, isChecked);
            editor.apply();

            // restart
            doRestartService();

            // 補間モードは logMode on の場合のみ有効
            final CheckBox interpolateCheckBox = findViewById(R.id.interpolateCheckBox);
            interpolateCheckBox.setEnabled(isChecked);

        });
        logCheckbox.setChecked(Config.logBar);

        // Interpolate mode
        final CheckBox interpolateCheckBox = findViewById(R.id.interpolateCheckBox);
        interpolateCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            final SharedPreferences pref13 = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            final SharedPreferences.Editor editor = pref13.edit();
            editor.putBoolean(C.PREF_KEY_INTERPOLATE_MODE, isChecked);
            editor.apply();

            // kill surface
            doStopService();

            // restart
            doRestartService();
        });
        interpolateCheckBox.setChecked(Config.interpolateMode);
        // 補間モードは logMode on の場合のみ有効
        interpolateCheckBox.setEnabled(Config.logBar);

        // text size
        final ZoomControls textSizeZoom = findViewById(R.id.text_size_zoom);
        textSizeZoom.setOnZoomOutClickListener(v -> updateTextSize(false));
        textSizeZoom.setOnZoomInClickListener(v -> updateTextSize(true));
        final TextView textSizeValue = findViewById(R.id.text_size_value);
        textSizeValue.setText(Config.textSizeSp + "sp");

        // pos
        final SeekBar seekBar = findViewById(R.id.posSeekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final TextView textView = findViewById(R.id.pos_text);
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
        seekBar.setProgress(Config.xPos);

        // Interval Spinner
        {
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item);
            final int[] intervals = new int[]{500, 1000, 1500, 2000};
            for (int interval : intervals) {
                adapter.add("" + (interval / 1000) + "." + (interval % 1000 / 100) + "sec");
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            final Spinner spinner = findViewById(R.id.intervalSpinner);
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

            final Spinner spinner = findViewById(R.id.maxSpeedSpinner);
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
            final int currentSpeed = pref.getInt(C.PREF_KEY_BAR_MAX_SPEED_KB, 10240);
            for (int i = 0; i < speeds.length; i++) {
                if (currentSpeed == speeds[i]) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }

        // 通信速度の単位
        {
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item);

            adapter.add("KB/s");
            adapter.add("Kbps");

            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            final Spinner spinner = findViewById(R.id.unitTypeSpinner);
            spinner.setAdapter(adapter);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                    if (mPreparingConfigArea) {
                        return;
                    }
                    MyLog.d("unitTypeSpinner onItemSelected: [" + position + "]");

                    final boolean unitTypeBps = position == 1;

                    final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                    final SharedPreferences.Editor editor = pref.edit();
                    editor.putBoolean(C.PREF_KEY_UNIT_TYPE_BPS, unitTypeBps);
                    editor.apply();

                    // restart
                    doRestartService();
                }


                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });
            final boolean unitTypeBps = pref.getBoolean(C.PREF_KEY_UNIT_TYPE_BPS, false);
            spinner.setSelection(unitTypeBps ? 1 : 0);
        }

        mPreparingConfigArea = false;
    }

    @SuppressLint("SetTextI18n")
    private void updateTextSize(boolean isZoomIn) {
        
        if (isZoomIn) {
            if (Config.textSizeSp >= 24) {
                return;
            }
            Config.textSizeSp ++;
        } else {
            if (Config.textSizeSp <= 6) {
                return;
            }
            Config.textSizeSp --;
        }

        final TextView textSizeValue = findViewById(R.id.text_size_value);
        textSizeValue.setText(Config.textSizeSp + "sp");
        
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        final SharedPreferences.Editor editor = pref.edit();
        editor.putInt(C.PREF_KEY_TEXT_SIZE_SP, Config.textSizeSp);
        editor.apply();

        // restart
        Config.loadPreferences(this);
        
        MySurfaceView.sForceRedraw = true;
        startSnapshot(1);
        MySurfaceView.sForceRedraw = false;

        new Handler().postDelayed(() -> {

            MySurfaceView.sForceRedraw = true;
            startSnapshot(1);
            MySurfaceView.sForceRedraw = false;

            doRestartService();
        }, 1);
    }

    private void preparePreviewArea() {

        final SeekBar seekBar = findViewById(R.id.seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final TextView kbText = findViewById(R.id.preview_kb_text);
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

            final Button button = findViewById(sampleButtonIds[i]);
            final int kb = samples[i];

            button.setOnClickListener(v -> restartWithPreview(kb, 0));
        }
    }

    @SuppressLint("SetTextI18n")
    private void restartWithPreview(long kb, long kbd1) {

        startSnapshot(kb * 1024 + kbd1 * 100);


        final SeekBar seekBar = findViewById(R.id.seekBar);
        seekBar.setProgress((int) (kb*10+kbd1));

        final TextView kbText = findViewById(R.id.preview_kb_text);
        kbText.setText(kb + "." + kbd1 + "KB");
    }

    private void startSnapshot(long previewBytes) {
        if (mServiceIF != null) {
            // preview
            try {
                mServiceIF.startSnapshot(previewBytes);
            } catch (RemoteException e) {
                MyLog.e(e);
            }
        }
    }
}

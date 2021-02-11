package jp.takke.datastats

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import android.provider.Settings
import android.view.Menu
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.ZoomControls
import androidx.preference.PreferenceManager
import jp.takke.util.MyLog
import jp.takke.util.TkConfig


class MainActivity : Activity() {

    private var mPreparingConfigArea = false

    private var mServiceIF: ILayerService? = null

    private val mServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {

            MyLog.d("onServiceConnected[$name]")

            mServiceIF = ILayerService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {

            MyLog.d("onServiceDisconnected[$name]")

            mServiceIF = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MyLog.d("MainActivity.onCreate")

        prepareConfigArea()

        preparePreviewArea()

        // M以降の権限対応
        if (!OverlayUtil.checkOverlayPermission(this)) {
            requestOverlayPermission()
        } else {
            doBindService()
        }

        // 外部ストレージのログファイルを削除する
        MyLog.deleteBigExternalLogFile()
    }

    private fun doBindService() {

        val serviceIntent = Intent(this, LayerService::class.java)

        // start
        MyLog.d("MainActivity: startService of LayerService")
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // bind
        MyLog.d("MainActivity: bindService of LayerService")
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_SYSTEM_OVERLAY)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {

        if (requestCode == REQUEST_SYSTEM_OVERLAY) {
            if (OverlayUtil.checkOverlayPermission(this)) {
                MyLog.i("MainActivity: overlay permission OK")

                // restart service
                doStopService()
                doRestartService()
            } else {
                MyLog.i("MainActivity: overlay permission NG")
                finish()
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {

        // プレビュー状態の解除
        if (mServiceIF != null) {
            // restart
            try {
                mServiceIF!!.restart()
            } catch (e: RemoteException) {
                MyLog.e(e)
            }

        }

        super.onPause()
    }

    override fun onDestroy() {

        if (mServiceIF != null) {
            unbindService(mServiceConnection)
        }

        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {

        // start
        run {
            val item = menu.add(R.string.config_start)

            item.setOnMenuItemClickListener {

                doRestartService()
                true
            }
        }

        // stop
        run {
            val item = menu.add(R.string.config_stop)

            item.setOnMenuItemClickListener {

                doStopService()
                true
            }
        }

        // restart
        run {
            val item = menu.add(R.string.config_restart)

            item.setOnMenuItemClickListener {

                doStopService()
                doRestartService()
                true
            }
        }

        // debug
        run {
            val item = menu.add(R.string.config_debug_mode)

            item.setOnMenuItemClickListener { item1 ->

                TkConfig.debugMode = !TkConfig.debugMode
                item1.isChecked = TkConfig.debugMode

                // save
                val pref = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                val edit = pref.edit()
                edit.putBoolean(C.PREF_KEY_DEBUG_MODE, TkConfig.debugMode)
                edit.apply()

                true
            }

            item.isCheckable = true
            item.setChecked(TkConfig.debugMode)
        }


        return true
    }

    private fun doStopService() {

        if (mServiceIF != null) {

            try {
                mServiceIF!!.stop()
            } catch (e: RemoteException) {
                MyLog.e(e)
            }

            unbindService(mServiceConnection)

            mServiceIF = null
        }
    }

    private fun doRestartService() {

        if (mPreparingConfigArea) {
            MyLog.d("MainActivity.doRestartService -> cancel (preparing)")
            return
        }
        MyLog.d("MainActivity.doRestartService")

        if (mServiceIF != null) {
            // restart
            try {
                mServiceIF!!.restart()
            } catch (e: RemoteException) {
                MyLog.e(e)
            }

        } else {
            // rebind
            doBindService()
        }

        val kbText = findViewById<TextView>(R.id.preview_kb_text)
        kbText.text = "-"
    }

    @SuppressLint("SetTextI18n")
    private fun prepareConfigArea() {

        mPreparingConfigArea = true

        Config.loadPreferences(this)

        // auto start
        val autoStartOnBoot = findViewById<CheckBox>(R.id.autoStartOnBoot)
        autoStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            val pref = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val editor = pref.edit()
            editor.putBoolean(C.PREF_KEY_START_ON_BOOT, isChecked)
            editor.apply()
        }
        val pref = PreferenceManager.getDefaultSharedPreferences(this)
        val startOnBoot = pref.getBoolean(C.PREF_KEY_START_ON_BOOT, true)
        autoStartOnBoot.isChecked = startOnBoot

        // hide when in fullscreen
        val hideCheckbox = findViewById<CheckBox>(R.id.hideWhenInFullscreen)
        hideCheckbox.setOnCheckedChangeListener { _, isChecked ->
            val pref1 = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val editor = pref1.edit()
            editor.putBoolean(C.PREF_KEY_HIDE_WHEN_IN_FULLSCREEN, isChecked)
            editor.apply()
        }
        hideCheckbox.isChecked = Config.hideWhenInFullscreen

        // Logarithm bar
        val logCheckbox = findViewById<CheckBox>(R.id.logarithmCheckbox)
        logCheckbox.setOnCheckedChangeListener { _, isChecked ->
            val pref12 = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val editor = pref12.edit()
            editor.putBoolean(C.PREF_KEY_LOGARITHM_BAR, isChecked)
            editor.apply()

            // restart
            doRestartService()

            // 補間モードは logMode on の場合のみ有効
            val interpolateCheckBox = findViewById<CheckBox>(R.id.interpolateCheckBox)
            interpolateCheckBox.isEnabled = isChecked

        }
        logCheckbox.isChecked = Config.logBar

        // Interpolate mode
        val interpolateCheckBox = findViewById<CheckBox>(R.id.interpolateCheckBox)
        interpolateCheckBox.setOnCheckedChangeListener { _, isChecked ->
            val pref13 = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val editor = pref13.edit()
            editor.putBoolean(C.PREF_KEY_INTERPOLATE_MODE, isChecked)
            editor.apply()

            // kill surface
            doStopService()

            // restart
            doRestartService()
        }
        interpolateCheckBox.isChecked = Config.interpolateMode
        // 補間モードは logMode on の場合のみ有効
        interpolateCheckBox.isEnabled = Config.logBar

        // text size
        val textSizeZoom = findViewById<ZoomControls>(R.id.text_size_zoom)
        textSizeZoom.setOnZoomOutClickListener {
            updateTextSize(false)
        }
        textSizeZoom.setOnZoomInClickListener {
            updateTextSize(true)
        }
        val textSizeValue = findViewById<TextView>(R.id.text_size_value)
        textSizeValue.text = Config.textSizeSp.toString() + "sp"

        // pos
        val seekBar = findViewById<SeekBar>(R.id.posSeekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val textView = findViewById<TextView>(R.id.pos_text)
                textView.text = "$progress%"

                val editor = pref.edit()
                editor.putInt(C.PREF_KEY_X_POS, progress)
                editor.apply()

                // restart
                doRestartService()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        seekBar.progress = Config.xPos

        // Interval Spinner
        run {
            val adapter = ArrayAdapter<String>(
                    this, android.R.layout.simple_spinner_item)
            val intervals = intArrayOf(500, 1000, 1500, 2000)
            for (interval in intervals) {
                adapter.add("" + interval / 1000 + "." + interval % 1000 / 100 + "sec")
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            val spinner = findViewById<Spinner>(R.id.intervalSpinner)
            spinner.adapter = adapter
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {

                    if (mPreparingConfigArea) {
                        return
                    }
                    MyLog.d("onItemSelected: [$position]")

                    val interval = intervals[position]

                    val editor = pref.edit()
                    editor.putInt(C.PREF_KEY_INTERVAL_MSEC, interval)
                    editor.apply()

                    // restart
                    doRestartService()
                }


                override fun onNothingSelected(parent: AdapterView<*>) {

                }
            }
            val currentIntervalMsec = pref.getInt(C.PREF_KEY_INTERVAL_MSEC, 1000)
            for (i in intervals.indices) {
                if (currentIntervalMsec == intervals[i]) {
                    spinner.setSelection(i)
                    break
                }
            }
        }

        // Max Speed[KB] Spinner (Bar)
        run {
            val adapter = ArrayAdapter<String>(
                    this, android.R.layout.simple_spinner_item)
            val speeds = intArrayOf(10, 50, 100, 500, 1024, 2048, 5120, 10240)
            for (s in speeds) {

                if (s >= 1024) {
                    adapter.add("" + s / 1024 + "MB/s")
                } else {
                    adapter.add("" + s + "KB/s")
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            val spinner = findViewById<Spinner>(R.id.maxSpeedSpinner)
            spinner.adapter = adapter
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {

                    if (mPreparingConfigArea) {
                        return
                    }
                    MyLog.d("onItemSelected: [$position]")

                    val speed = speeds[position]

                    val editor = pref.edit()
                    editor.putInt(C.PREF_KEY_BAR_MAX_SPEED_KB, speed)
                    editor.apply()

                    // restart
                    doRestartService()
                }


                override fun onNothingSelected(parent: AdapterView<*>) {

                }
            }
            val currentSpeed = pref.getInt(C.PREF_KEY_BAR_MAX_SPEED_KB, 10240)
            for (i in speeds.indices) {
                if (currentSpeed == speeds[i]) {
                    spinner.setSelection(i)
                    break
                }
            }
        }

        // 通信速度の単位
        run {
            val adapter = ArrayAdapter<String>(
                    this, android.R.layout.simple_spinner_item)

            adapter.add("KB/s")
            adapter.add("Kbps")

            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            val spinner = findViewById<Spinner>(R.id.unitTypeSpinner)
            spinner.adapter = adapter
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {

                    if (mPreparingConfigArea) {
                        return
                    }
                    MyLog.d("unitTypeSpinner onItemSelected: [$position]")

                    val unitTypeBps = position == 1

                    val editor = pref.edit()
                    editor.putBoolean(C.PREF_KEY_UNIT_TYPE_BPS, unitTypeBps)
                    editor.apply()

                    // restart
                    doRestartService()
                }


                override fun onNothingSelected(parent: AdapterView<*>) {

                }
            }
            val unitTypeBps = pref.getBoolean(C.PREF_KEY_UNIT_TYPE_BPS, false)
            spinner.setSelection(if (unitTypeBps) 1 else 0)
        }

        mPreparingConfigArea = false
    }

    @SuppressLint("SetTextI18n")
    private fun updateTextSize(isZoomIn: Boolean) {

        if (isZoomIn) {
            if (Config.textSizeSp >= 24) {
                return
            }
            Config.textSizeSp++
        } else {
            if (Config.textSizeSp <= 6) {
                return
            }
            Config.textSizeSp--
        }

        val textSizeValue = findViewById<TextView>(R.id.text_size_value)
        textSizeValue.text = Config.textSizeSp.toString() + "sp"

        val pref = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
        val editor = pref.edit()
        editor.putInt(C.PREF_KEY_TEXT_SIZE_SP, Config.textSizeSp)
        editor.apply()

        // restart
        Config.loadPreferences(this)

        MySurfaceView.sForceRedraw = true
        startSnapshot(1)
        MySurfaceView.sForceRedraw = false

        Handler().postDelayed({

            MySurfaceView.sForceRedraw = true
            startSnapshot(1)
            MySurfaceView.sForceRedraw = false

            doRestartService()
        }, 1)
    }

    private fun preparePreviewArea() {

        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            @SuppressLint("SetTextI18n")
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val kbText = findViewById<TextView>(R.id.preview_kb_text)
                kbText.text = "" + progress / 10 + "." + progress % 10 + "KB"

                restartWithPreview((progress / 10).toLong(), (progress % 10).toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val sampleButtonIds = intArrayOf(R.id.sample_1kb_button, R.id.sample_20kb_button, R.id.sample_50kb_button, R.id.sample_80kb_button, R.id.sample_100kb_button)
        val samples = intArrayOf(1, 20, 50, 80, 100)

        for (i in sampleButtonIds.indices) {

            val button = findViewById<Button>(sampleButtonIds[i])
            val kb = samples[i]

            button.setOnClickListener {
                restartWithPreview(kb.toLong(), 0)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun restartWithPreview(kb: Long, kbd1: Long) {

        startSnapshot(kb * 1024 + kbd1 * 100)


        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        seekBar.progress = (kb * 10 + kbd1).toInt()

        val kbText = findViewById<TextView>(R.id.preview_kb_text)
        kbText.text = kb.toString() + "." + kbd1 + "KB"
    }

    private fun startSnapshot(previewBytes: Long) {
        if (mServiceIF != null) {
            // preview
            try {
                mServiceIF!!.startSnapshot(previewBytes)
            } catch (e: RemoteException) {
                MyLog.e(e)
            }

        }
    }

    companion object {

        private const val REQUEST_SYSTEM_OVERLAY = 1
    }
}

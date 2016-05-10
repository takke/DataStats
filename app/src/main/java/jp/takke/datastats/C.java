package jp.takke.datastats;

public class C {
    public static final String PREF_KEY_DEBUG_MODE = "debugMode";
    public static final String PREF_KEY_RESIDENT_MODE = "residentMode";
    public static final String PREF_KEY_X_POS = "xPos";
    public static final String PREF_KEY_INTERVAL_MSEC = "intervalMsec";
    public static final String PREF_KEY_BAR_MAX_SPEED_KB = "barMaxSpeedKB";
    public static final String PREF_KEY_START_ON_BOOT = "startOnBoot";
    public static final String PREF_KEY_LOGARITHM_BAR = "logBar";
    public static final String PREF_KEY_HIDE_WHEN_IN_FULLSCREEN = "hideWhenInFullscreen";
    public static final String PREF_KEY_INTERPOLATE_MODE = "interpolateMode";
    public static final String PREF_KEY_TEXT_SIZE_SP = "textSizeSp";
    public static final String PREF_KEY_UNIT_TYPE_BPS = "unitTypeBps";

    public static final int DEFAULT_TEXT_SIZE_SP = 8;

    // 初期Alarmの遅延時間[ms]
    public static final int ALARM_STARTUP_DELAY_MSEC = 1000;

    // Service維持のためのAlarmの更新間隔[ms]
    public static final int ALARM_INTERVAL_MSEC = 60 * 1000;

    public static final int SCREEN_ON_LOGIC_DELAY_MSEC = 3000;
    public static final int SCREEN_OFF_LOGIC_DELAY_MSEC = 3000;
}

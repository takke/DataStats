package jp.takke.util;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MyLog {
	
//	public static void v(String msg) {
//		if (TkUtil.isEmulator()) {
//			Log.v(TkConsts.LOG_NAME, msg);
//		}
//	}
//
//	public static void v(String msg, Throwable th) {
//		if (TkUtil.isEmulator()) {
//			Log.v(TkConsts.LOG_NAME, msg, th);
//		}
//	}

	
	public static void d(String msg) {
		if (TkConfig.debugMode || TkUtil.isEmulator()) {
			Log.d(TkConsts.LOG_NAME, msg);
		}

		MyLog.dumpToExternalLogFile(Log.DEBUG, msg);
	}
	
//	/**
//	 * msg 内の {elapsed} 部分を経過時刻に変換し出力する
//	 *
//	 * @param msg メッセージ
//	 * @param startTick 測定開始時刻[ms]
//	 */
//	public static void dWithElapsedTime(String msg, long startTick) {
//
//		if (TkConfig.debugMode || TkUtil.isEmulator()) {
//			d(msg.replace("{elapsed}", (System.currentTimeMillis() - startTick) + ""));
//		}
//	}
	
	public static void d(String msg, Throwable th) {
		if (TkConfig.debugMode || TkUtil.isEmulator()) {
			Log.d(TkConsts.LOG_NAME, msg, th);
		}
		
		MyLog.dumpToExternalLogFile(Log.DEBUG, msg);
		MyLog.dumpToExternalLogFile(Log.DEBUG, Log.getStackTraceString(th));
	}

	
	public static void i(String msg) {
		Log.i(TkConsts.LOG_NAME, msg);

		MyLog.dumpToExternalLogFile(Log.INFO, msg);
	}

//	public static void iWithElapsedTime(String msg, long startTick) {
//
//		i(msg.replace("{elapsed}", (System.currentTimeMillis() - startTick) + ""));
//	}
	
//	public static void i(String msg, Throwable th) {
//		Log.i(TkConsts.LOG_NAME, msg, th);
//
//		MyLog.dumpToExternalLogFile(Log.INFO, msg);
//		MyLog.dumpToExternalLogFile(Log.INFO, Log.getStackTraceString(th));
//	}

	
	public static void w(String msg) {
		Log.w(TkConsts.LOG_NAME, msg);

		MyLog.dumpToExternalLogFile(Log.WARN, msg);
	}

//	public static void wWithElapsedTime(String msg, long startTick) {
//
//		if (TkConfig.debugMode || TkUtil.isEmulator()) {
//			w(msg.replace("{elapsed}", (System.currentTimeMillis() - startTick) + ""));
//		}
//	}
//
//	public static void w(String msg, Throwable th) {
//		Log.w(TkConsts.LOG_NAME, msg, th);
//
//		MyLog.dumpToExternalLogFile(Log.WARN, msg);
//		MyLog.dumpToExternalLogFile(Log.WARN, Log.getStackTraceString(th));
//	}
//
//	public static void w(Throwable th) {
//		Log.w(TkConsts.LOG_NAME, th.getMessage(), th);
//
//		MyLog.dumpToExternalLogFile(Log.WARN, Log.getStackTraceString(th));
//	}

	
//	public static void e(String msg) {
//		Log.e(TkConsts.LOG_NAME, msg);
//
//		MyLog.dumpToExternalLogFile(Log.ERROR, msg);
//	}
//
//	public static void e(String msg, Throwable th) {
//		Log.e(TkConsts.LOG_NAME, msg, th);
//
//		MyLog.dumpToExternalLogFile(Log.ERROR, msg);
//		MyLog.dumpToExternalLogFile(Log.ERROR, Log.getStackTraceString(th));
//	}

	public static void e(Throwable th) {
		Log.e(TkConsts.LOG_NAME, th.getMessage(), th);
		
		MyLog.dumpToExternalLogFile(Log.ERROR, Log.getStackTraceString(th));
	}

	/**
	 * 外部ストレージ(通常はSDカード)にログを出力する
	 * 
	 * @param error エラーレベル
	 * @param msg メッセージ
	 */
	@SuppressLint("SimpleDateFormat")
	private static synchronized void dumpToExternalLogFile(int error, String msg) {
		
		// 外部ストレージ出力条件確認
		if (!TkConfig.debugMode) {
			// DEBUG ログはデバッグモードのみ出力する
			return;
		}

		try {
			// 保存先の決定
			final File fout = IOUtil.getExternalStorageFile(TkConsts.EXTERNAL_FILE_DIRNAME, null);
			if (fout == null) {
				// メディア非マウントなど
				return;
			}
			final String path = fout.getAbsolutePath() + "/" + TkConsts.EXTERNAL_LOG_FILENAME;

			// ファイルに書き込む
			final FileOutputStream out = new FileOutputStream(path, true);	// append
            final BufferedOutputStream bout = new BufferedOutputStream(out);

            // 日付時刻
			final SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd\tHH:mm:ss.SSS");
            bout.write(sdf.format(new Date()).getBytes());
            bout.write("\t".getBytes());

			// エラーレベル
			switch (error) {
			case Log.INFO:	bout.write("[INFO]".getBytes());	break;
			case Log.WARN:	bout.write("[WARN]".getBytes());	break;
			case Log.ERROR:	bout.write("[ERROR]".getBytes());	break;
			case Log.DEBUG:	bout.write("[DEBUG]".getBytes());	break;
			default:
				break;
			}

			// ログ本文
            bout.write("\t".getBytes());
            bout.write(msg.getBytes("UTF-8"));
            bout.write("\n".getBytes());

            bout.flush();
            bout.close();
            out.close();

		} catch (Exception e) {
			Log.e("DataStats", e.getMessage(), e);
		}
	}

	/**
	 * 外部ストレージのログファイルがある一定サイズ以上の場合に削除する
	 *
	 * 通常は起動時にチェックさせる
	 */
	public static void deleteBigExternalLogFile() {

		if (!TkConfig.debugMode) {
			return;
		}

		try {
			// 保存先の決定
			final File fout = IOUtil.getExternalStorageFile(TkConsts.EXTERNAL_FILE_DIRNAME, null);
			if (fout == null) {
				// メディア非マウントなど
				return;
			}
			final String path = fout.getAbsolutePath() + "/" + TkConsts.EXTERNAL_LOG_FILENAME;

			// チェック＆削除
			final File file = new File(path);
			//noinspection PointlessArithmeticExpression
			final int MAXFILESIZE = 1 * 1024 * 1024;	// [MB]

			Log.i(TkConsts.LOG_NAME, "external log size check, size[" + file.length() + "], limit[" + MAXFILESIZE + "]");

			if (file.length() > MAXFILESIZE) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
			}

		} catch (Exception e) {
			Log.e(TkConsts.LOG_NAME, e.getMessage(), e);
		}
	}

}

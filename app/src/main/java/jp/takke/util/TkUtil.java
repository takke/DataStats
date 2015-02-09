package jp.takke.util;

import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.sqlite.SQLiteException;
import android.provider.MediaStore.Images;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;


public class TkUtil {

	// チェック高速化のためのキャッシュ
	private static boolean isEmulatorChecked = false;
	private static boolean isEmulatorCache = false;
	
	public static boolean isEmulator() {
		
		if (!isEmulatorChecked) {
			// 未チェック(未キャッシュ)の場合にのみ実際にチェックする
//			isEmulatorCache = android.os.Build.MODEL.equals("sdk");
			isEmulatorCache = false;
			if (android.os.Build.DEVICE.equals("generic")) {
			     if (android.os.Build.BRAND.equals("generic")) {
			          isEmulatorCache = true;
			     }
			}
			isEmulatorChecked = true;
		}
		
		return isEmulatorCache;
	}

	

}

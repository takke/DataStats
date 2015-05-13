package jp.takke.util;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public class IOUtil {

    /**
	 * 指定されたディレクトリへのファイルオブジェクトを取得する
	 *
	 * @param directory ディレクトリ
	 * @return File オブジェクト
	 */
	public static File getExternalStorageFile(String directory, Context context) {

		final String status = Environment.getExternalStorageState();
		File fout;
		if (!status.equals(Environment.MEDIA_MOUNTED)) {
			// 未マウントなのでデータディレクトリを返す
			if (context == null) {
				return null;
			}
			fout = new File(context.getApplicationInfo().dataDir + "/files/");
		} else {
			fout = new File(Environment.getExternalStorageDirectory() + "/" + directory + "/");
		}
        //noinspection ResultOfMethodCallIgnored
        fout.mkdirs();
		return fout;
	}
}

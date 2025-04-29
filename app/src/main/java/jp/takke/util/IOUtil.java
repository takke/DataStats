package jp.takke.util;

import android.content.Context;

import java.io.File;

import androidx.annotation.Nullable;

public class IOUtil {

    /**
     * 内部ストレージのアプリ領域のディレクトリを取得する
     * <p>
     * 例) /storage/sdcard0/Android/data/XXX/files
     */
    public static File getInternalStorageAppFilesDirectoryAsFile(@Nullable Context context) {

        if (context == null) {
            return null;
        }

        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            return null;
        }

        // 初回は存在していないので作成しておく
        externalFilesDir.mkdirs();

        return externalFilesDir;
    }
}

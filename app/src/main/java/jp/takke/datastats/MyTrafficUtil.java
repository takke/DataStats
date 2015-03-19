package jp.takke.datastats;

import android.content.res.Resources;

public class MyTrafficUtil {

    // 文字色変更基準[Bytes]
    public static long sHighLimit;
    public static long sMiddleLimit;


    public static long convertByteToKb(long bytes) {
        return bytes / 1024;
    }


    public static long convertByteToD1Kb(long bytes) {

        final long b1023 = bytes % 1024; // [0, 1023]
        
        // to [0, 9]
        if (b1023 >= 900)
            return 9;
        
        if (b1023 == 0)
            return 0;

        if (b1023 <= 100)
            return 1;

        return b1023 / 100;
    }


    static int getTextShadowColorByBytes(Resources resources, long bytes) {

        if (bytes < sMiddleLimit) {
            return resources.getColor(R.color.textShadowColorLow);
        }
        if (bytes < sHighLimit) {
            return resources.getColor(R.color.textShadowColorMiddle);
        }
        return resources.getColor(R.color.textShadowColorHigh);
    }


    static int getTextColorByBytes(Resources resources, long bytes) {

        if (bytes < sMiddleLimit) {
            return resources.getColor(R.color.textColorLow);
        }
        if (bytes < sHighLimit) {
            return resources.getColor(R.color.textColorMiddle);
        }
        return resources.getColor(R.color.textColorHigh);
    }
}

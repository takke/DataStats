package jp.takke.datastats;

import android.content.res.Resources;

import java.io.BufferedReader;
import java.io.FileReader;

public class MyTrafficUtil {


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

        if (bytes < Config.middleLimit) {
            return resources.getColor(R.color.textShadowColorLow);
        }
        if (bytes < Config.highLimit) {
            return resources.getColor(R.color.textShadowColorMiddle);
        }
        return resources.getColor(R.color.textShadowColorHigh);
    }


    static int getTextColorByBytes(Resources resources, long bytes) {

        if (bytes < Config.middleLimit) {
            return resources.getColor(R.color.textColorLow);
        }
        if (bytes < Config.highLimit) {
            return resources.getColor(R.color.textColorMiddle);
        }
        return resources.getColor(R.color.textColorHigh);
    }


    static long getLoopbackRxBytes() {

        return readLongValueFromFile("/sys/class/net/lo/statistics/rx_bytes");
    }


    static long getLoopbackTxBytes() {

        return readLongValueFromFile("/sys/class/net/lo/statistics/tx_bytes");
    }


    private static long readLongValueFromFile(String path) {

        try {
            final FileReader in = new FileReader(path);
            final BufferedReader br = new BufferedReader(in);

            final String line = br.readLine();

            br.close();
            in.close();

            if (line == null) {
                return 0;
            }
            return Long.valueOf(line);

        } catch (Throwable ignored) {
            return 0;
        }
    }
}

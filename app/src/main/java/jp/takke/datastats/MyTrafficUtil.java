package jp.takke.datastats;

import android.content.res.Resources;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

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

        long rxBytes = 0;
        File file = new File("/sys/class/net/lo/statistics/rx_bytes");
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));

            String line;
            if ((line = br.readLine()) != null) {
                rxBytes = Long.valueOf(line);
            }

            br.close();
        } catch (IOException e) {
            rxBytes = 0;
        }

        return rxBytes;
    }

    static long getLoopbackTxBytes() {

        long txBytes = 0;
        File file = new File("/sys/class/net/lo/statistics/tx_bytes");
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));

            String line;
            if ((line = br.readLine()) != null) {
                txBytes = Long.valueOf(line);
            }

            br.close();
        } catch (IOException e) {
            txBytes = 0;
        }

        return txBytes;
    }
}

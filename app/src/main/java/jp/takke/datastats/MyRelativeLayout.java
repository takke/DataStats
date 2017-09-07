package jp.takke.datastats;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Display;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import java.lang.reflect.Method;

public class MyRelativeLayout extends RelativeLayout {

    private boolean mFullScreen;


    public MyRelativeLayout(Context context) {
        super(context);
    }


    public MyRelativeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }


    public boolean isFullScreen() {
        return mFullScreen;
    }


    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        if (oldh == 0) {
            return;
        }

//        MyLog.d("MyRelativeLayout.onSizeChanged: [" + h + "], real[" + getRealSize().y + "]");
        mFullScreen = h == getRealSize().y;
    }


    // code from "http://blog.sfapps.jp/2015/12/28/android_fullscreen_monitoring/"
    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private Point getRealSize() {
        Display display = ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Point point = new Point(0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            display.getRealSize(point);
            return point;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            try {
                Method getRawWidth = Display.class.getMethod("getRawWidth");
                Method getRawHeight = Display.class.getMethod("getRawHeight");
                int width = (Integer) getRawWidth.invoke(display);
                int height = (Integer) getRawHeight.invoke(display);
                point.set(width, height);
                return point;
            } catch (Exception ignored) {
            }
        } else {
            point.set(display.getWidth(), display.getHeight());
        }
        return point;
    }
}

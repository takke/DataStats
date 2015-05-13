package jp.takke.datastats;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.LinkedList;

import jp.takke.util.MyLog;

public class MySurfaceView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final long TARGET_FPS = 20;

    private SurfaceHolder mSurfaceHolder;
    private Thread mThread;

//    private boolean mSleeping;
    private boolean mThreadActive;

    private int mScreenWidth;
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    private int mScreenHeight;

//    private ArrayDeque<Long> mDrawTimes = new ArrayDeque<>();


    private class Traffic {

        public long time;
        public long tx;
        public int pTx;
        public long rx;
        public int pRx;

        public Traffic(long time, long tx, int pTx, long rx, int pRx) {
            this.time = time;
            this.tx = tx;
            this.pTx = pTx;
            this.rx = rx;
            this.pRx = pRx;
        }
    }

    private LinkedList<Traffic> mTrafficList = new LinkedList<>();
    private static final int TRAFFIC_LIST_COUNT_MAX = 3;

    private Bitmap mDownloadMarkBitmap;
    private Bitmap mUploadMarkBitmap;
    private Drawable uploadDrawable;
    private Drawable downloadDrawable;
    
    // 前のフレームの値
    private int mLastPTx;
    private int mLastPRx;
    private long mLastTx;
    private long mLastRx;


    public static boolean sForceRedraw = false;


    public MySurfaceView(Context context) {
        super(context);

        init();
    }
    
    
    public MySurfaceView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        
        init();
    }


    private void init() {

        mSurfaceHolder = getHolder();
        mSurfaceHolder.addCallback(this);

        // make this surfaceview transparent
        mSurfaceHolder.setFormat(PixelFormat.TRANSLUCENT);
        setFocusable(true);
        setZOrderOnTop(true);
    }


    public void setSleeping(boolean sleeping) {

//        mSleeping = sleeping;

        if (sleeping) {
            stopThread();
        } else {
            startThread();
        }
    }


    public void drawBlank() {

        MyLog.d("drawBlank");

        myDrawFrame(-1, -1, 0, 0);
    }


    public void setTraffic(long tx, int pTx, long rx, int pRx) {

        mTrafficList.add(new Traffic(System.currentTimeMillis(), tx, pTx, rx, pRx));
        
        // 過去データ削除
        if (mTrafficList.size() > TRAFFIC_LIST_COUNT_MAX) {
            final int n = mTrafficList.size() - TRAFFIC_LIST_COUNT_MAX;
            for (int i = 0; i < n; i++) {
                mTrafficList.removeFirst();
            }
        }
        
        if (!Config.interpolateMode || sForceRedraw) {
            myDraw();
        }
    }
    
    
    @Override
    public void run() {

        while (mThread != null && mThreadActive) {

            myDraw();
        }

    }


    public void myDraw() {

        final long startTime = System.currentTimeMillis();

        try {
            myDrawFrame(startTime);
        } catch (Exception ignored) {
            MyLog.e(ignored);
        }

        final long now = System.currentTimeMillis();
        long waitMs = 1000 / TARGET_FPS - (now - startTime);
        if (waitMs < 0) {
            waitMs = 1;
        }
        SystemClock.sleep(waitMs);
        
        // for actual FPS
//        mDrawTimes.addLast(now);
//        if (mDrawTimes.size() > 24) {
//            final int n = mDrawTimes.size() - 24;
//            for (int i=0; i<n; i++) {
//                mDrawTimes.removeFirst();
//            }
//        }
    }


    private void myDrawFrame(long now) {

        if (mTrafficList.size() <= 0) {
            return;
        }
        
        final Traffic t = mTrafficList.getLast();
        final long tx = t.tx;
        final long rx = t.rx;

//        MyLog.d(" myDrawFrame: tx[" + tx + "B], rx[" + rx + "B] " + now + "");

        // skip zero
        if (!sForceRedraw &&
            mLastTx == 0 && mLastPTx == 0 && tx == 0 &&
            mLastRx == 0 && mLastPRx == 0 && rx == 0) {
            
            // 一度ゼロになったら通信量が発生するまで待機する
//            MyLog.d("MySurfaceView.myDrawFrame: same frame, zero");
            return;
        }
            

        // 補間実行
        final int pTx = (Config.interpolateMode && Config.logBar) ? interpolate(t, now, true)  : t.pTx;
        final int pRx = (Config.interpolateMode && Config.logBar) ? interpolate(t, now, false) : t.pRx;

        // 前回と同じなら再描画しない
        if (!sForceRedraw &&
            pTx == mLastPTx && mLastTx == tx &&
            pRx == mLastPRx && mLastRx == rx) {
//            MyLog.d("MySurfaceView.myDrawFrame: same frame, tx[" + pTx + "=" + tx + "], rx[" + pRx + "=" + rx + "]");
            return;
//        } else {
//            MyLog.d("MySurfaceView.myDrawFrame: tx[" + pTx + "], rx[" + pRx + "]");
        }
        mLastPTx = pTx;
        mLastPRx = pRx;
        mLastTx = tx;
        mLastRx = rx;

        myDrawFrame(tx, rx, pTx, pRx);
    }


    private void myDrawFrame(long tx, long rx, int pTx, int pRx) {

        //--------------------------------------------------
        // draw start
        //--------------------------------------------------
        final Canvas canvas = mSurfaceHolder.lockCanvas();
        if (canvas == null) {
            return;
        }


        //--------------------------------------------------
        // clear background
        //--------------------------------------------------
        final Paint paint = new Paint();
        final Resources resources = getResources();

        // clear
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        // Background
        canvas.drawColor(resources.getColor(R.color.textBackgroundColor));


        //--------------------------------------------------
        // upload, download
        //--------------------------------------------------
        final int paddingRight = resources.getDimensionPixelSize(R.dimen.overlay_padding_right);
        final int xDownloadStart = mScreenWidth / 2;

        // upload gradient
        if (uploadDrawable == null) {
            uploadDrawable = resources.getDrawable(R.drawable.upload_background);
        }
        final int xUploadEnd = (int) (pTx / 1000f * xDownloadStart);
        uploadDrawable.setBounds(0, 0, xUploadEnd, mScreenHeight);
        uploadDrawable.draw(canvas);
        if (pTx > 0) {
            paint.setColor(resources.getColor(R.color.uploadBorder));
            paint.setStrokeWidth(resources.getDimensionPixelSize(R.dimen.updown_bar_right_border_size));
            canvas.drawLine(xUploadEnd, 0, xUploadEnd, mScreenHeight, paint);
        }

        // download gradient
        if (downloadDrawable == null) {
            downloadDrawable = resources.getDrawable(R.drawable.download_background);
        }
        final int xDownloadEnd = (int) (xDownloadStart + pRx / 1000f * xDownloadStart);
        downloadDrawable.setBounds(xDownloadStart, 0, xDownloadEnd, mScreenHeight);
        downloadDrawable.draw(canvas);
        if (pRx > 0) {
            paint.setColor(resources.getColor(R.color.downloadBorder));
            paint.setStrokeWidth(resources.getDimensionPixelSize(R.dimen.updown_bar_right_border_size));
            canvas.drawLine(xDownloadEnd, 0, xDownloadEnd, mScreenHeight, paint);
        }


        final float scaledDensity = resources.getDisplayMetrics().scaledDensity;
        final int textSizeSp = Config.textSizeSp;
        final float textSizePx = textSizeSp * scaledDensity;

        // upload text
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(MyTrafficUtil.getTextColorByBytes(resources, tx));
        paint.setShadowLayer(1.5f, 1.5f, 1.5f, MyTrafficUtil.getTextShadowColorByBytes(resources, tx));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(textSizePx);
        canvas.drawText(getReadableUDText(tx), xDownloadStart - paddingRight, paint.getTextSize(), paint);

        // download text
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(MyTrafficUtil.getTextColorByBytes(resources, rx));
        paint.setShadowLayer(1.5f, 1.5f, 1.5f, MyTrafficUtil.getTextShadowColorByBytes(resources, rx));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(textSizePx);
        canvas.drawText(getReadableUDText(rx), mScreenWidth - paddingRight, paint.getTextSize(), paint);

        paint.setShader(null);

        // upload/download mark
        final Paint paintUd = new Paint();
        final float udMarkSize = (textSizeSp+2) * scaledDensity;
        final int paddingLeft = resources.getDimensionPixelSize(R.dimen.overlay_padding_left);
        {
            if (mUploadMarkBitmap == null) {
                mUploadMarkBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_find_previous_holo_dark);
            }
            final Matrix matrix = new Matrix();
            final float s = udMarkSize / mUploadMarkBitmap.getWidth();
            matrix.setScale(s, s);
            matrix.postTranslate(paddingLeft, 0);
            canvas.drawBitmap(mUploadMarkBitmap, matrix, paintUd);
        }
        {
            if (mDownloadMarkBitmap == null) {
                mDownloadMarkBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_find_next_holo_dark);
            }
            final Matrix matrix = new Matrix();
            final float s = udMarkSize / mDownloadMarkBitmap.getWidth();
            matrix.setScale(s, s);
            matrix.postTranslate(xDownloadStart + paddingLeft, 0);
            canvas.drawBitmap(mDownloadMarkBitmap, matrix, paintUd);
        }

        // FPS
//        {
//            paint.setColor(Color.rgb(0x80, 0x80, 0x80));
//            paint.setTextSize(20);
//            paint.setTextAlign(Paint.Align.RIGHT);
//            final float fps = calcCurrentFps(startTime);
//            final long now = System.currentTimeMillis();
//            canvas.drawText(((int) fps) + "." + (int) ((fps * 10) % 10) + "[fps] " + (now - startTime) + "ms",
//                    mScreenWidth-paddingRight, mScreenHeight-10, paint);
//        }


        mSurfaceHolder.unlockCanvasAndPost(canvas);
    }


    private String getReadableUDText(long bytes) {

//        MyLog.d(" getReadableUDText: " + bytes + "B");

        if (bytes < 0) {
            return "";
        }

        if (Config.unitTypeBps) {
            final long bits = bytes * 8;
            return MyTrafficUtil.convertByteToKb(bits) + "." + MyTrafficUtil.convertByteToD1Kb(bits) + "Kbps";
        } else {
            return MyTrafficUtil.convertByteToKb(bytes) + "." + MyTrafficUtil.convertByteToD1Kb(bytes) + "KB/s";
        }
    }


    private int interpolate(Traffic t, long now, boolean getTx) {

        final int currentP = getTx ? t.pTx : t.pRx;
        
        final int n = mTrafficList.size() - 1;
        if (n < 2) {
            return currentP;
        } else {

            // 最後の2つ分の差分時間を補間に使う
            final long lastIntervalTime = mTrafficList.get(n).time - mTrafficList.get(n-1).time;
            final long elapsed = now - mTrafficList.get(n).time;
            if (elapsed > lastIntervalTime * 3) {
                // 十分時間が経過しているので収束させる
                return currentP;
            } else {
                
                // 補間準備
                final double[] x = new double[n + 1];
                final double[] y = new double[n + 1];
                for (int i = 0; i < n + 1; i++) {
                    final Traffic t1 = mTrafficList.get(i);
                    x[i] = t1.time;
                    y[i] = getTx ? t1.pTx : t1.pRx;
                }

                // 要は lastIntervalTime 分だけ遅れて描画される感じ
                // でも lastIntervalTime だと遅すぎるので少し短くしておく
                final long at = now - lastIntervalTime / 2;
                final int p = (int) lagrange(n, x, y, at);
                if (p < 0) {
                    return 0;
                } else if (p > 1000) {
                    return 1000;
                }
                
                // 前回の差分から方向を算出し、現在値を超えていたら超えないようにする
                final int lastP = getTx ? mLastPTx : mLastPRx;
                final int direction = p - lastP;
                if (direction > 0 && p > currentP) {
                    // 上昇方向で超過しているので制限する
                    return currentP;
                } else if (direction < 0 && p < currentP) {
                    // 下降方向で下回っているので制限する
                    return currentP;
                }
                
                return p;
            }
        }
    }


    private double lagrange(int n, double[] x, double[] y, double x1) {

        double s1, s2, y1 = 0.0;
        int i1, i2;

        for (i1 = 0; i1 <= n; i1++) {
            s1 = 1.0;
            s2 = 1.0;
            for (i2 = 0; i2 <= n; i2++) {
                if (i2 != i1) {
                    s1 *= (x1 - x[i2]);
                    s2 *= (x[i1] - x[i2]);
                }
            }
            y1 += y[i1] * s1 / s2;
        }

        return y1;
    }


//    @SuppressWarnings("UnusedDeclaration")
//    private float calcCurrentFps(final long now) {
//        if (mDrawTimes.size() <= 0) {
//            return 1;
//        }
//        final long firstDrawTime = mDrawTimes.getFirst();
//        return (1000f * mDrawTimes.size() / (now - firstDrawTime));
//    }
    

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

        MyLog.d("MySurfaceView.surfaceCreated");

    }


    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        MyLog.d("MySurfaceView.surfaceChanged[" + width + "," + height + "]");

        mScreenWidth = width;
        mScreenHeight = height;

        startThread();

        // 初期描画のために強制的に1フレーム描画する
        sForceRedraw = true;
        myDraw();
        sForceRedraw = false;
    }


    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

        MyLog.d("MySurfaceView.surfaceDestroyed");
        
        stopThread();
    }


    private void startThread() {

        // 補間モードは logMode on の場合のみ有効
        if (Config.interpolateMode && Config.logBar) {

            if (mThread == null) {
                mThread = new Thread(this);
                mThreadActive = true;
                mThread.start();
                MyLog.d("MySurfaceView.startThread: thread start");
            } else {
                MyLog.d("MySurfaceView.startThread: already running");
            }
        }
    }


    private void stopThread() {

        if (mThreadActive && mThread != null) {
            MyLog.d("MySurfaceView.stopThread");
            
            mThreadActive = false;
            while (true) {
                try {
                    mThread.join();
                    break;
                } catch (InterruptedException ignored) {
                    MyLog.e(ignored);
                }
            }
            mThread = null;
        } else {
            MyLog.d("MySurfaceView.stopThread: no thread");
        }
    }

}

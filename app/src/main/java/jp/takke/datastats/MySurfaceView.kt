package jp.takke.datastats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.res.ResourcesCompat
import jp.takke.util.MyLog
import java.util.*

class MySurfaceView : SurfaceView, SurfaceHolder.Callback, Runnable {

    private var mSurfaceHolder: SurfaceHolder? = null
    private var mThread: Thread? = null

    private var mThreadActive: Boolean = false

    private var mScreenWidth: Int = 0
    private var mScreenHeight: Int = 0

    private val mTrafficList = LinkedList<Traffic>()

    private var mDownloadMarkBitmap: Bitmap? = null
    private var mUploadMarkBitmap: Bitmap? = null
    private var uploadDrawable: Drawable? = null
    private var downloadDrawable: Drawable? = null

    // 前のフレームの値
    private var mLastPTx: Int = 0
    private var mLastPRx: Int = 0
    private var mLastTx: Long = 0
    private var mLastRx: Long = 0


    private inner class Traffic(
            var time: Long,
            var tx: Long,
            var pTx: Int,
            var rx: Long,
            var pRx: Int
    )


    constructor(context: Context) : super(context) {

        init()
    }

    constructor(context: Context, attributeSet: AttributeSet) : super(context, attributeSet) {

        init()
    }

    private fun init() {

        mSurfaceHolder = holder
        mSurfaceHolder!!.addCallback(this)

        // make this surface view transparent
        mSurfaceHolder!!.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = true
        setZOrderOnTop(true)
    }

    fun setSleeping(sleeping: Boolean) {

//        mSleeping = sleeping;

        if (sleeping) {
            stopThread()
        } else {
            startThread()
        }
    }

    fun drawBlank() {

        MyLog.d("drawBlank")

        myDrawFrame(-1, -1, 0, 0)
    }

    fun setTraffic(tx: Long, pTx: Int, rx: Long, pRx: Int) {

        mTrafficList.add(Traffic(System.currentTimeMillis(), tx, pTx, rx, pRx))

        // 過去データ削除
        if (mTrafficList.size > TRAFFIC_LIST_COUNT_MAX) {
            val n = mTrafficList.size - TRAFFIC_LIST_COUNT_MAX
            for (i in 0 until n) {
                mTrafficList.removeFirst()
            }
        }

        if (!Config.interpolateMode || sForceRedraw) {
            myDraw()
        }
    }

    override fun run() {

        while (mThread != null && mThreadActive) {

            myDraw()
        }

    }

    private fun myDraw() {

        val startTime = System.currentTimeMillis()

        try {
            myDrawFrame(startTime)
        } catch (e: Exception) {
            MyLog.e(e)
        }

        val now = System.currentTimeMillis()
        var waitMs = 1000 / TARGET_FPS - (now - startTime)
        if (waitMs < 0) {
            waitMs = 1
        }
        SystemClock.sleep(waitMs)

        // for actual FPS
//        mDrawTimes.addLast(now);
//        if (mDrawTimes.size() > 24) {
//            final int n = mDrawTimes.size() - 24;
//            for (int i=0; i<n; i++) {
//                mDrawTimes.removeFirst();
//            }
//        }
    }

    private fun myDrawFrame(now: Long) {

        if (mTrafficList.size <= 0) {
            return
        }

        val t = mTrafficList.last
        val tx = t.tx
        val rx = t.rx

        //        MyLog.d(" myDrawFrame: tx[" + tx + "B], rx[" + rx + "B] " + now + "");

        // skip zero
        if (!sForceRedraw &&
                mLastTx == 0L && mLastPTx == 0 && tx == 0L &&
                mLastRx == 0L && mLastPRx == 0 && rx == 0L) {

            // 一度ゼロになったら通信量が発生するまで待機する
            //            MyLog.d("MySurfaceView.myDrawFrame: same frame, zero");
            return
        }


        // 補間実行
        val pTx = if (Config.interpolateMode && Config.logBar) interpolate(t, now, true) else t.pTx
        val pRx = if (Config.interpolateMode && Config.logBar) interpolate(t, now, false) else t.pRx

        // 前回と同じなら再描画しない
        if (!sForceRedraw &&
                pTx == mLastPTx && mLastTx == tx &&
                pRx == mLastPRx && mLastRx == rx) {
//            MyLog.d("MySurfaceView.myDrawFrame: same frame, tx[" + pTx + "=" + tx + "], rx[" + pRx + "=" + rx + "]");
            return
//        } else {
//            MyLog.d("MySurfaceView.myDrawFrame: tx[" + pTx + "], rx[" + pRx + "]");
        }
        mLastPTx = pTx
        mLastPRx = pRx
        mLastTx = tx
        mLastRx = rx

        myDrawFrame(tx, rx, pTx, pRx)
    }

    private fun myDrawFrame(tx: Long, rx: Long, pTx: Int, pRx: Int) {

        //--------------------------------------------------
        // draw start
        //--------------------------------------------------
        val canvas = mSurfaceHolder!!.lockCanvas() ?: return


        //--------------------------------------------------
        // clear background
        //--------------------------------------------------
        val paint = Paint()
        val resources = resources

        // clear
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Background
        canvas.drawColor(ResourcesCompat.getColor(resources, R.color.textBackgroundColor, null))


        //--------------------------------------------------
        // upload, download
        //--------------------------------------------------
        val paddingRight = resources.getDimensionPixelSize(R.dimen.overlay_padding_right)
        val xDownloadStart = mScreenWidth / 2

        // upload gradient
        if (uploadDrawable == null) {
            uploadDrawable = ResourcesCompat.getDrawable(resources, R.drawable.upload_background, null)
        }
        val xUploadEnd = (pTx / 1000f * xDownloadStart).toInt()
        uploadDrawable!!.setBounds(0, 0, xUploadEnd, mScreenHeight)
        uploadDrawable!!.draw(canvas)
        if (pTx > 0) {
            paint.color = ResourcesCompat.getColor(resources, R.color.uploadBorder, null)
            paint.strokeWidth = resources.getDimensionPixelSize(R.dimen.updown_bar_right_border_size).toFloat()
            canvas.drawLine(xUploadEnd.toFloat(), 0f, xUploadEnd.toFloat(), mScreenHeight.toFloat(), paint)
        }

        // download gradient
        if (downloadDrawable == null) {
            downloadDrawable = ResourcesCompat.getDrawable(resources, R.drawable.download_background, null)
        }
        val xDownloadEnd = (xDownloadStart + pRx / 1000f * xDownloadStart).toInt()
        downloadDrawable!!.setBounds(xDownloadStart, 0, xDownloadEnd, mScreenHeight)
        downloadDrawable!!.draw(canvas)
        if (pRx > 0) {
            paint.color = ResourcesCompat.getColor(resources, R.color.downloadBorder, null)
            paint.strokeWidth = resources.getDimensionPixelSize(R.dimen.updown_bar_right_border_size).toFloat()
            canvas.drawLine(xDownloadEnd.toFloat(), 0f, xDownloadEnd.toFloat(), mScreenHeight.toFloat(), paint)
        }


        val scaledDensity = resources.displayMetrics.scaledDensity
        val textSizeSp = Config.textSizeSp
        val textSizePx = textSizeSp * scaledDensity

        // upload text
        paint.typeface = Typeface.MONOSPACE
        paint.color = MyTrafficUtil.getTextColorByBytes(resources, tx)
        paint.setShadowLayer(1.5f, 1.5f, 1.5f, MyTrafficUtil.getTextShadowColorByBytes(resources, tx))
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = textSizePx
        canvas.drawText(getReadableUDText(tx), (xDownloadStart - paddingRight).toFloat(), paint.textSize, paint)

        // download text
        paint.typeface = Typeface.MONOSPACE
        paint.color = MyTrafficUtil.getTextColorByBytes(resources, rx)
        paint.setShadowLayer(1.5f, 1.5f, 1.5f, MyTrafficUtil.getTextShadowColorByBytes(resources, rx))
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = textSizePx
        canvas.drawText(getReadableUDText(rx), (mScreenWidth - paddingRight).toFloat(), paint.textSize, paint)

        paint.shader = null

        // upload/download mark
        val paintUd = Paint()
        val udMarkSize = (textSizeSp + 2) * scaledDensity
        val paddingLeft = resources.getDimensionPixelSize(R.dimen.overlay_padding_left)
        run {
            if (mUploadMarkBitmap == null) {
                mUploadMarkBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_find_previous_holo_dark)
            }
            val matrix = Matrix()
            val s = udMarkSize / mUploadMarkBitmap!!.width
            matrix.setScale(s, s)
            matrix.postTranslate(paddingLeft.toFloat(), 0f)
            canvas.drawBitmap(mUploadMarkBitmap!!, matrix, paintUd)
        }
        run {
            if (mDownloadMarkBitmap == null) {
                mDownloadMarkBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_find_next_holo_dark)
            }
            val matrix = Matrix()
            val s = udMarkSize / mDownloadMarkBitmap!!.width
            matrix.setScale(s, s)
            matrix.postTranslate((xDownloadStart + paddingLeft).toFloat(), 0f)
            canvas.drawBitmap(mDownloadMarkBitmap!!, matrix, paintUd)
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


        mSurfaceHolder!!.unlockCanvasAndPost(canvas)
    }

    private fun getReadableUDText(bytes: Long): String {

        //        MyLog.d(" getReadableUDText: " + bytes + "B");

        if (bytes < 0) {
            return ""
        }

        return if (Config.unitTypeBps) {
            val bits = bytes * 8
            MyTrafficUtil.convertByteToKb(bits).toString() + "." + MyTrafficUtil.convertByteToD1Kb(bits) + "Kbps"
        } else {
            MyTrafficUtil.convertByteToKb(bytes).toString() + "." + MyTrafficUtil.convertByteToD1Kb(bytes) + "KB/s"
        }
    }

    private fun interpolate(t: Traffic, now: Long, getTx: Boolean): Int {

        val currentP = if (getTx) t.pTx else t.pRx

        val n = mTrafficList.size - 1
        if (n < 2) {
            return currentP
        } else {

            // 最後の2つ分の差分時間を補間に使う
            val lastIntervalTime = mTrafficList[n].time - mTrafficList[n - 1].time
            val elapsed = now - mTrafficList[n].time
            if (elapsed > lastIntervalTime * 3) {
                // 十分時間が経過しているので収束させる
                return currentP
            } else {

                // 補間準備
                val x = DoubleArray(n + 1)
                val y = DoubleArray(n + 1)
                for (i in 0 until n + 1) {
                    val t1 = mTrafficList[i]
                    x[i] = t1.time.toDouble()
                    y[i] = (if (getTx) t1.pTx else t1.pRx).toDouble()
                }

                // 要は lastIntervalTime 分だけ遅れて描画される感じ
                // でも lastIntervalTime だと遅すぎるので少し短くしておく
                val at = now - lastIntervalTime / 2
                val p = lagrange(n, x, y, at.toDouble()).toInt()
                if (p < 0) {
                    return 0
                } else if (p > 1000) {
                    return 1000
                }

                // 前回の差分から方向を算出し、現在値を超えていたら超えないようにする
                val lastP = if (getTx) mLastPTx else mLastPRx
                val direction = p - lastP
                if (direction > 0 && p > currentP) {
                    // 上昇方向で超過しているので制限する
                    return currentP
                } else if (direction < 0 && p < currentP) {
                    // 下降方向で下回っているので制限する
                    return currentP
                }

                return p
            }
        }
    }

    private fun lagrange(n: Int, x: DoubleArray, y: DoubleArray, x1: Double): Double {

        var s1: Double
        var s2: Double
        var y1 = 0.0
        var i1 = 0
        var i2: Int

        while (i1 <= n) {
            s1 = 1.0
            s2 = 1.0
            i2 = 0
            while (i2 <= n) {
                if (i2 != i1) {
                    s1 *= x1 - x[i2]
                    s2 *= x[i1] - x[i2]
                }
                i2++
            }
            y1 += y[i1] * s1 / s2
            i1++
        }

        return y1
    }

    override fun surfaceCreated(holder: SurfaceHolder) {

        MyLog.d("MySurfaceView.surfaceCreated")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {

        MyLog.d("MySurfaceView.surfaceChanged[$width,$height]")

        mScreenWidth = width
        mScreenHeight = height

        startThread()

        // 初期描画のために強制的に1フレーム描画する
        sForceRedraw = true
        myDraw()
        sForceRedraw = false
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {

        MyLog.d("MySurfaceView.surfaceDestroyed")

        stopThread()
    }

    private fun startThread() {

        // 補間モードは logMode on の場合のみ有効
        if (Config.interpolateMode && Config.logBar) {

            if (mThread == null) {
                mThread = Thread(this)
                mThreadActive = true
                mThread!!.start()
                MyLog.d("MySurfaceView.startThread: thread start")
            } else {
                MyLog.d("MySurfaceView.startThread: already running")
            }
        }
    }

    private fun stopThread() {

        if (mThreadActive && mThread != null) {
            MyLog.d("MySurfaceView.stopThread")

            mThreadActive = false
            while (true) {
                try {
                    mThread!!.join()
                    break
                } catch (e: InterruptedException) {
                    MyLog.e(e)
                }

            }
            mThread = null
        } else {
            MyLog.d("MySurfaceView.stopThread: no thread")
        }
    }

    companion object {

        private const val TARGET_FPS: Long = 20
        private const val TRAFFIC_LIST_COUNT_MAX = 3


        var sForceRedraw = false
    }

}

package jp.takke.datastats

import android.content.res.Resources
import java.io.BufferedReader
import java.io.FileReader

object MyTrafficUtil {
  fun convertByteToKb(bytes: Long): Long {
    return bytes / 1024
  }


  fun convertByteToD1Kb(bytes: Long): Long {
    val b1023 = bytes % 1024 // [0, 1023]

    // to [0, 9]
    if (b1023 >= 900) return 9

    if (b1023 == 0L) return 0

    if (b1023 <= 100) return 1

    return b1023 / 100
  }


  fun getTextShadowColorByBytes(resources: Resources, bytes: Long): Int {
    if (bytes < Config.middleLimit) {
      return resources.getColor(R.color.textShadowColorLow)
    }
    if (bytes < Config.highLimit) {
      return resources.getColor(R.color.textShadowColorMiddle)
    }
    return resources.getColor(R.color.textShadowColorHigh)
  }


  fun getTextColorByBytes(resources: Resources, bytes: Long): Int {
    if (bytes < Config.middleLimit) {
      return resources.getColor(R.color.textColorLow)
    }
    if (bytes < Config.highLimit) {
      return resources.getColor(R.color.textColorMiddle)
    }
    return resources.getColor(R.color.textColorHigh)
  }


  val loopbackRxBytes: Long
    get() = readLongValueFromFile("/sys/class/net/lo/statistics/rx_bytes")


  val loopbackTxBytes: Long
    get() = readLongValueFromFile("/sys/class/net/lo/statistics/tx_bytes")


  private fun readLongValueFromFile(path: String): Long {
    try {
      val `in` = FileReader(path)
      val br = BufferedReader(`in`)

      val line = br.readLine()

      br.close()
      `in`.close()

      if (line == null) {
        return 0
      }
      return line.toLong()
    } catch (ignored: Throwable) {
      return 0
    }
  }
}

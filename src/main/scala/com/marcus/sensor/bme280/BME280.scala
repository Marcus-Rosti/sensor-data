package com.marcus.sensor.bme280

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.marcus.sensor.{Reader, Reading}
import com.pi4j.io.i2c.I2CBus
import com.pi4j.io.i2c.I2CDevice
import com.pi4j.io.i2c.I2CFactory

import scala.concurrent.Future
import scala.math._

class BME280 extends Reader {

  private def bound(value: Int): Int =
    Option.when(value > 32767)(value - 65536).getOrElse(value)
  override val feed: Source[Reading, NotUsed] = ???

  
  // Create I2C bus
  val bus: I2CBus = I2CFactory.getInstance(I2CBus.BUS_1)
  // Get I2C device, BME280 I2C address is 0x76(108)
  val device: I2CDevice = bus.getDevice(0x76)

  // Read 24 bytes of data from address 0x88(136)
  val b1: Array[Byte] = new Array[Byte](24)
  device.read(0x88, b1, 0, 24)

  // Convert the data
  // temp coefficients
  val dig_T1: Int = (b1(0) & 0xff) + ((b1(1) & 0xff) * 256)
  val dig_T2: Int = bound((b1(2) & 0xff) + ((b1(3) & 0xff) * 256))
  val dig_T3: Int = bound((b1(4) & 0xff) + ((b1(5) & 0xff) * 256))

  // pressure coefficients
  val dig_P1: Int = (b1(6) & 0xff) + ((b1(7) & 0xff) * 256)
  val dig_P2: Int = bound((b1(8) & 0xff) + ((b1(9) & 0xff) * 256))
  val dig_P3: Int = bound((b1(10) & 0xff) + ((b1(11) & 0xff) * 256))
  val dig_P4: Int = bound((b1(12) & 0xff) + ((b1(13) & 0xff) * 256))
  val dig_P5: Int = bound((b1(14) & 0xff) + ((b1(15) & 0xff) * 256))
  val dig_P6: Int = bound((b1(16) & 0xff) + ((b1(17) & 0xff) * 256))
  val dig_P7: Int = bound((b1(18) & 0xff) + ((b1(19) & 0xff) * 256))
  val dig_P8: Int = bound((b1(20) & 0xff) + ((b1(21) & 0xff) * 256))
  val dig_P9: Int = bound((b1(22) & 0xff) + ((b1(23) & 0xff) * 256))

  // Read 1 byte of data from address 0xA1(161)
  val dig_H1: Int = device.read(0xa1).toByte & 0xff

  // Read 7 bytes of data from address 0xE1(225)
  device.read(0xe1, b1, 0, 7)

  // humidity coefficients
  val dig_H2: Int = bound((b1(0) & 0xff) + (b1(1) * 256))
  val dig_H3: Int = b1(2) & 0xff
  val dig_H4: Int = bound((b1(3) & 0xff) * 16) + (b1(4) & 0xf)
  val dig_H5: Int = bound((b1(4) & 0xff) / 16) + ((b1(5) & 0xff) * 16)
  val dig_H6_temp: Int = b1(6) & 0xff
  val dig_H6: Int = Option.when(dig_H6_temp > 127)(dig_H6_temp - 256).getOrElse(dig_H6_temp)

  // Select control humidity register
  // Humidity over sampling rate = 1
  device.write(0xf2, 0x01.toByte)
  // Select control measurement register
  // Normal mode, temp and pressure over sampling rate = 1
  device.write(0xf4, 0x27.toByte)
  // Select config register
  // Stand_by time = 1000 ms
  device.write(0xf5, 0xa0.toByte)

  // Read 8 bytes of data from address 0xF7(247)
  // pressure msb1, pressure msb, pressure lsb, temp msb1, temp msb, temp lsb, humidity lsb, humidity msb
  val data: Array[Byte] = new Array[Byte](8)
  device.read(0xf7, data, 0, 8)

  // Convert pressure and temperature data to 19-bits
  val adc_p: Long = (((data(0) & 0xff).toLong * 65536) + ((data(1) & 0xff).toLong * 256) + (data(
          2
        ) & 0xf0).toLong) / 16

  val adc_t: Long = (((data(3) & 0xff).toLong * 65536) + ((data(4) & 0xff).toLong * 256) + (data(
          5
        ) & 0xf0).toLong) / 16
  // Convert the humidity data
  val adc_h: Long = (data(6) & 0xff).toLong * 256 + (data(7) & 0xff).toLong

  // Temperature offset calculations
  val var1: Double = (adc_t.toDouble / 16384.0 - dig_T1.toDouble / 1024.0) * dig_T2.toDouble

  val var2: Double =
    ((adc_t.toDouble / 131072.0 - dig_T1.toDouble / 8192.0) * (adc_t.toDouble / 131072.0 - dig_T1.toDouble / 8192.0)) * dig_T3.toDouble
  val t_fine: Double = var1 + var2
  val cTemp: Double = (var1 + var2) / 5120.0
  val fTemp: Double = cTemp * 1.8 + 32

  // Pressure offset calculations
  val var1_2: Double = (t_fine.toDouble / 2.0) - 64000.0
  val var2_2: Double = var1_2 * var1_2 * dig_P6.toDouble / 32768.0
  val var2_3: Double = var2_2 + var1_2 * dig_P5.toDouble * 2.0
  val var2_4: Double = (var2_3 / 4.0) + (dig_P4.toDouble * 65536.0)

  val var1_3: Double =
    (dig_P3.toDouble * var1_2 * var1_2 / 524288.0 + dig_P2.toDouble * var1) / 524288.0
  val var1_4: Double = (1.0 + var1_3 / 32768.0) * dig_P1.toDouble
  val p_temp: Double = 1048576.0 - adc_p.toDouble
  val p: Double = (p_temp - (var2 / 4096.0)) * 6250.0 / var1_4
  val var1_5: Double = dig_P9.toDouble * p * p / 2147483648.0
  val var2_5: Double = p * dig_P8.toDouble / 32768.0
  val pressure: Double = (p + (var1_5 + var2_5 + dig_P7.toDouble) / 16.0) / 100

  // Humidity offset calculations
  val var_H_temp: Double = (t_fine.toDouble) - 76800.0

  val var_H: Double =
    (adc_h - (dig_H4 * 64.0 + dig_H5 / 16384.0 * var_H_temp)) * (dig_H2 / 65536.0 * (1.0 + dig_H6 / 67108864.0 * var_H_temp * (1.0 + dig_H3 / 67108864.0 * var_H_temp)))
  val humidity_temp: Double = var_H * (1.0 - dig_H1 * var_H / 524288.0)
  val humidity: Double = min(100, max(0, humidity_temp))

  // Output data to screen
  System.out.printf("Temperature in Celsius : %.2f C %n", cTemp)
  System.out.printf("Temperature in Fahrenheit : %.2f F %n", fTemp)
  System.out.printf("Pressure : %.2f hPa %n", pressure)
  System.out.printf("Relative Humidity : %.2f %% RH %n", humidity)

}

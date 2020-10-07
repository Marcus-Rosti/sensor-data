package com.marcus.sensor

import java.nio.ByteBuffer

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream.scaladsl.{RestartSource, Source, StreamConverters}
import com.fazecast.jSerialComm.SerialPort
import com.marcus.Reading

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class SDS011(comPort: SerialPort, pm25FeedName: String, pm10FeedName: String, rateLimit: Int)(
  implicit
  log: LoggingAdapter,
  executionContext: ExecutionContext
) {

  val rl = rateLimit

  private val alpha = 0.5

  private def emwa(alpha: Double)(tminus1: Reading, t: Reading) =
    t.copy(
      value = alpha * t.value + (1 - alpha) * tminus1.value
    )

  private val alphaEmwa = emwa(alpha)(_, _)

  @inline
  private def baToDouble(ba: Array[Byte]): Double =
    ByteBuffer.wrap(ba).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort().toDouble / 10

  lazy val feedData: Source[Reading, NotUsed] = RestartSource.withBackoff(
    minBackoff = 10.seconds,
    maxBackoff = 1.minute,
    randomFactor = 0.2, // adds 20% "noise" to vary the intervals slightly
    maxRestarts = 20 // limits the amount of restarts to 20
  ) { () =>
    log.info(s"Ensuring port is closed ${comPort.closePort}")
    comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
    log.info(s"Opening port ${comPort.openPort}")

    // Create a source from a future of a source
    StreamConverters
      .fromInputStream(() => comPort.getInputStream)
      .filter(_.size == 10)
      .async
      .map(_.toArray)
      .map { ba =>
        Seq(
          Reading(baToDouble(ba.slice(2, 4)), pm25FeedName),
          Reading(baToDouble(ba.slice(4, 6)), pm10FeedName)
        )
      }
      .log("readings")
      .conflate((oldReadings, newReadings) => {
        Seq(
          alphaEmwa(oldReadings.head, newReadings.head),
          alphaEmwa(oldReadings.last, newReadings.last)
        )
      })
      .log("conflated")
      .throttle(rateLimit, 1.minute)
      .mapConcat(identity)
      .mapMaterializedValue(_ => NotUsed)
      .watchTermination() { (_, done) =>
        done
          .andThen(_ => comPort.closePort())
          .onComplete {
            case Success(_) =>
              log.info("Completed successfully")
            case Failure(ex) =>
              log.error(ex, "Completed with failure")
          }
      }
  }
}

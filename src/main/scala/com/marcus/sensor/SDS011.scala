package com.marcus.sensor

import java.nio.ByteBuffer

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.StreamConverters
import com.fazecast.jSerialComm.SerialPort

import scala.math._

class SDS011(comPort: SerialPort, pm25FeedName: String, pm10FeedName: String)(implicit
  system: ActorSystem
) extends Reader {
  import system.{dispatcher, log}

  private val alpha = 1d - 1d / E

  private val alphaEWMA = Reading.ewma(alpha)(_, _)

  @inline
  private def baToDouble(ba: Array[Byte]): Double =
    ByteBuffer.wrap(ba).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort().toDouble / 10

  private val restartSettings: RestartSettings = RestartSettings(
    minBackoff = 10.seconds,
    maxBackoff = 1.minute,
    randomFactor = 0.2
  ).withMaxRestarts(20, within = 20.minutes)

  lazy val feed: Source[Reading, NotUsed] = RestartSource.withBackoff(restartSettings) { () =>
    log.info(s"Ensuring port is closed ${comPort.closePort}")
    comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
    log.info(s"Opening port ${comPort.openPort}")

    // Create a source from a future of a source
    StreamConverters
      .fromInputStream(() => comPort.getInputStream)
      .filter(_.size.equals(10))
      .async
      .map(_.toArray)
      .map { ba =>
        Seq(
          Reading(baToDouble(ba.slice(2, 4)), pm25FeedName),
          Reading(baToDouble(ba.slice(4, 6)), pm10FeedName)
        )
      }
      .log("readings")
      .async
      .conflate((oldReadings, newReadings) => {
        Seq(
          alphaEWMA(oldReadings.head, newReadings.head),
          alphaEWMA(oldReadings.last, newReadings.last)
        )
      })
      .log("conflated")
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

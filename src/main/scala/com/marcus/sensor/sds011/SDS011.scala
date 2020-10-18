package com.marcus.sensor.sds011

import java.nio.ByteBuffer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{Materializer, RestartSettings}
import akka.stream.scaladsl.{RestartSource, Sink, Source, StreamConverters}
import akka.util.ByteString
import com.fazecast.jSerialComm.SerialPort
import com.marcus.sensor.Reading

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.math.E
import scala.util.{Failure, Success}

trait SDS011 {
  val stream: Source[ByteString, NotUsed]
}

// @TODO(mrosti): variable refresh rate. If values are "stable" sleep longer, else sleep shorter
class IntervalReader(comPort: SerialPort, pollFrequency: FiniteDuration)(implicit
  system: ActorSystem
) extends SDS011 {
  import system.{dispatcher, log}

  implicit val matermializer: Materializer = Materializer.apply(system)

  // from: https://github.com/luetzel/sds011/blob/master/sds011_pylab.py
  private val powerOnBytes =
    Seq(
      0xaa, //head
      0xb4, //command 1
      0x06, //data byte 1
      0x01, //data byte 2 (set mode)
      0x01, //data byte 3 (wake)
      0x00, //data byte 4
      0x00, //data byte 5
      0x00, //data byte 6
      0x00, //data byte 7
      0x00, //data byte 8
      0x00, //data byte 9
      0x00, //data byte 10
      0x00, //data byte 11
      0x00, //data byte 12
      0x00, //data byte 13
      0xff, //data byte 14 (device id byte 1)
      0xff, //data byte 15 (device id byte 2)
      0x06, //checksum
      0xab //tail`
    ).map(_.toByte)

  private val powerOffBytes =
    Seq(
      0xaa, //head
      0xb4, //command 1
      0x06, //data byte 1
      0x01, //data byte 2 (set mode)
      0x00, //data byte 3 (sleep)
      0x00, //data byte 4
      0x00, //data byte 5
      0x00, //data byte 6
      0x00, //data byte 7
      0x00, //data byte 8
      0x00, //data byte 9
      0x00, //data byte 10
      0x00, //data byte 11
      0x00, //data byte 12
      0x00, //data byte 13
      0xff, //data byte 14 (device id byte 1)
      0xff, //data byte 15 (device id byte 2)
      0x05, //checksum
      0xab //tail
    ).map(_.toByte)

  private def powerOn: Future[Boolean] =
    Future {
      log.debug("Turning on")
      comPort.writeBytes(powerOnBytes.toArray, powerOnBytes.length)
    }.map(_.equals(powerOnBytes.length))
      .andThen(t => log.debug(s"Turned on: ${t.toString}"))

  private def powerOff: Future[Boolean] =
    Future {
      log.debug("Turning off")
      comPort.writeBytes(powerOffBytes.toArray, powerOffBytes.length)
    }.map(_.equals(powerOnBytes.length))
      .andThen(t => log.debug(s"Turned off: ${t.toString}"))

  private def sample: Future[ByteString] =
    for {
      _ <- powerOn
      x <-
        StreamConverters
          .fromInputStream(() => comPort.getInputStream)
          .log("Read")
          .filter(_.size.equals(10))
          .take(2)
          .log("Filter")
          .runWith(Sink.last)
      _ <- powerOff
    } yield x

  override val stream: Source[ByteString, NotUsed] = Source
    .tick(
      0.seconds,
      pollFrequency,
      ()
    )
    .mapAsync(1)(_ => sample)
    .mapMaterializedValue(_ => NotUsed)
}

class ConstantReader(comPort: SerialPort) extends SDS011 {

  val stream: Source[ByteString, NotUsed] = StreamConverters
    .fromInputStream(() => comPort.getInputStream)
    .filter(_.size.equals(10))
    .mapMaterializedValue(_ => NotUsed)

}

object SDS011 {
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

  def apply(
    comPort: SerialPort,
    pollInterval: FiniteDuration,
    pm25FeedName: String,
    pm10FeedName: String
  )(implicit
    system: ActorSystem
  ): Source[Reading, NotUsed] = {
    import system.{dispatcher, log}
    RestartSource.withBackoff(restartSettings) { () =>
      log.info(s"Ensuring port is closed ${comPort.closePort}")
      comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
      log.info(s"Opening port ${comPort.openPort}")
      Option
        .when(pollInterval <= 1.second)(new ConstantReader(comPort))
        .getOrElse(new IntervalReader(comPort, pollInterval))
        .stream
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
}

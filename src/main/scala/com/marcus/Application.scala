package com.marcus

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.stream.scaladsl._
import com.fazecast.jSerialComm.SerialPort
import com.marcus.http.AdafruitAccessor
import com.marcus.sensor.SDS011
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.http.scaladsl.model.HttpResponse
import akka.stream.RestartSettings
import com.marcus.sensor.Reading

object Application extends App {

  implicit val sys: ActorSystem = ActorSystem("air-quality")
  implicit val log: LoggingAdapter = sys.log
  val config: Config = ConfigFactory.load().getConfig("air-quality")
  log.debug(config.toString)

  val username: String = config.getString("adafruit.username")
  val pm25FeedName: String = config.getString("adafruit.feeds.pm25.name")
  val pm10FeedName: String = config.getString("adafruit.feeds.pm10.name")
  val adafruitRateLimitPerMinute: Int = config.getInt("adafruit.rate-limit")
  val adafruitKey: String = config.getString("adafruit.passkey")

  val sensorPortName: String = config.getString("sensor.sds011.port-name")

  val comPort: SerialPort = SerialPort.getCommPort(sensorPortName)

  val feedData: Source[Reading, NotUsed] =
    new SDS011(comPort, pm25FeedName, pm10FeedName).feed

  val feeds: Source[Reading, NotUsed] = Source.combine(feedData, Source.empty[Reading])(Merge(_))

  val httpFlow: Flow[Reading, HttpResponse, NotUsed] = new AdafruitAccessor(
    username,
    adafruitKey,
    adafruitRateLimitPerMinute
  ).httpFlow

  CoordinatedShutdown(sys).addJvmShutdownHook {
    log.warning("Shutting down comPort!!")
    comPort.closePort
    log.warning("Shutting down http pools!!")
    Await.result(Http().shutdownAllConnectionPools(), 1.minute)
    log.warning("Shutting down actor system!!")
  }

  lazy val mainFlow: Source[HttpResponse, NotUsed.type] =
    feedData
      .log("feedData")
      .throttle(adafruitRateLimitPerMinute - 1, 1.minute)
      .async
      .via(httpFlow)
      .log("sent")
      .mapMaterializedValue(_ => NotUsed)

  private val restartSettings: RestartSettings = RestartSettings(
    minBackoff = 500.milliseconds,
    maxBackoff = 2.minutes,
    randomFactor = 0.2
  ).withMaxRestarts(Integer.MAX_VALUE, within = 10.days)
  RestartSource
    .withBackoff(restartSettings) { () => mainFlow }
    .runWith(Sink.onComplete {
      case Success(done) =>
        log.info(s"Completed: $done")
      case Failure(ex) =>
        sys.terminate()
        log.error(ex, "Failed")
    })

}

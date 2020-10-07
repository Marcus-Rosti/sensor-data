package com.marcus

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.{Done, NotUsed}
import com.fazecast.jSerialComm.SerialPort
import com.marcus.http.AdafruitAccessor
import com.marcus.sensor.SDS011
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

case class Reading(value: Double, feed_key: String, created_at: DateTime = DateTime.now)

object Application extends App {

  implicit val sys: ActorSystem = ActorSystem("air-quality")
  import sys.dispatcher
  implicit val log: LoggingAdapter = sys.log
  val config: Config = ConfigFactory.load().getConfig("air-quality")
  log.debug(config.toString)

  val username: String = config.getString("adafruit.username")
  val pm25FeedName: String = config.getString("adafruit.feeds.pm25.name")
  val pm10FeedName: String = config.getString("adafruit.feeds.pm10.name")
  val adafruitRateLimitPerMinute: Int = config.getInt("adafruit.rate-limit")
  val adafruitKey: String = config.getString("adafruit.passkey")

  private val header = HttpHeader.parse("X-AIO-Key", adafruitKey) match {
    case ParsingResult.Ok(header, _) => header
    case _                           => throw new Exception("how did this happen")
  }
  val sensorRateLimit: Int = config.getInt("sensor.sds011.rate-limit")
  val sensorPortName: String = config.getString("sensor.sds011.port-name")

  val comPort: SerialPort = SerialPort.getCommPort(sensorPortName)

  val feedData = new SDS011(comPort, pm25FeedName, pm10FeedName, sensorRateLimit).feedData

  val httpFlow = new AdafruitAccessor(
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

  def post(reading: Reading): Future[Done] = {

    RestartSource
      .withBackoff(
        minBackoff = 500.milliseconds,
        maxBackoff = 10.minutes,
        randomFactor = 0.2,
        maxRestarts = 10
      ) { () =>
        Source
          .single(
            HttpRequest(
              method = HttpMethods.POST,
              uri = s"http://io.adafruit.com/api/v2/$username/feeds/${reading.feed_key}/data",
              headers = List(header),
              entity = HttpEntity(
                ContentTypes.`application/json`,
                s"""{"value": ${reading.value}, "created_at": "${reading.created_at}"}"""
              )
            )
          )
          .mapAsync(1)(hr =>
            Http().singleRequest(hr).andThen { case Success(el) => el.entity.discardBytes() }
          )
          .mapAsync(parallelism = 1) {
            case HttpResponse(StatusCodes.OK, _, _, _) => Future.successful(Done)
            case HttpResponse(StatusCodes.TooManyRequests, _, _, _) =>
              log.warning("SLOW DOWN YOUR REQUESTS")
              throw new Exception("Slow down")
            case HttpResponse(statusCode, _, _, _) =>
              log.error(s"Some other error $statusCode")
              throw new Exception(s"$statusCode")
          }
      }
      .runWith(Sink.head)
      .recover { _ =>
        log.error("I give up")
        Done
      }

  }

  lazy val mainFlow =
    feedData
      .log("feedData")
      .async
      .via(httpFlow)
      .log("sent")
      .mapMaterializedValue(_ => NotUsed)

  RestartSource
    .withBackoff(
      minBackoff = 500.milliseconds,
      maxBackoff = 2.minutes,
      randomFactor = 0.2,
      maxRestarts = 100000
    ) { () => mainFlow }
    .runWith(Sink.onComplete {
      case Success(done) =>
        log.info(s"Completed: $done")
      case Failure(ex) =>
        sys.terminate()
        log.error(ex, "Failed")
    })

}

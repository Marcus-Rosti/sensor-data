package com.marcus.http

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.RetryFlow
import com.marcus.sensor.Reading

class AdafruitAccessor(username: String, adafruitKey: String, adafruitRateLimitPerMinute: Int)(
  implicit system: ActorSystem
) {
  import system.{dispatcher, log}

  lazy val httpFlow: Flow[Reading, HttpResponse, NotUsed] = {
    Flow[Reading]
      .mapAsyncUnordered(4)(createDataRequest)
      .log("http")
      .async
      .throttle(adafruitRateLimitPerMinute, 1.minutes)
      .via(poolClientFlow)
      .async
      .log("sent")
  }

  lazy val poolClientFlow: Flow[(HttpRequest, Reading), HttpResponse, Http.HostConnectionPool] = {
    val flow =
      Http().cachedHostConnectionPool[Reading]("io.adafruit.com").mapAsync(1) { e =>
        e._1.map(_.entity.discardBytes())
        Future.fromTry(e._1)
      }
    RetryFlow.withBackoff(
      minBackoff = 50.millis,
      maxBackoff = 5.minute,
      randomFactor = 0.2,
      maxRetries = 20,
      flow
    )(decideRetry = {
      case (_, HttpResponse(StatusCodes.OK, _, _, _)) => None
      case el =>
        log.warning(s"retrying => ${el._1._2}")
        Some(el._1)
    })

  }

  def createDataRequest(reading: Reading): Future[(HttpRequest, Reading)] =
    Future {
      val headers = HttpHeader.parse("X-AIO-Key", adafruitKey) match {
        case ParsingResult.Ok(header, _) => Seq(header)
        case _                           => Seq.empty
      }
      HttpRequest(
        method = HttpMethods.POST,
        uri = s"http://io.adafruit.com/api/v2/$username/feeds/${reading.feed_key}/data",
        headers = headers,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          s"""{"value": ${reading.value}, "created_at": "${reading.created_at}"}"""
        )
      ) -> reading
    }

}

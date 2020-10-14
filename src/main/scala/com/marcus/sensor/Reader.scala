package com.marcus.sensor

import akka.NotUsed
import akka.stream.scaladsl.Source

trait Reader {
  val feed: Source[Reading, NotUsed]
}

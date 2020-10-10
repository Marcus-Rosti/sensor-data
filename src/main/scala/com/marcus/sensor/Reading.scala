package com.marcus.sensor

import akka.http.scaladsl.model.DateTime

case class Reading(value: Double, feed_key: String, created_at: DateTime = DateTime.now)

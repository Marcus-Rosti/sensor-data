package com.marcus.sensor

import akka.http.scaladsl.model.DateTime

case class Reading(value: Double, feed_key: String, created_at: DateTime = DateTime.now)

object Reading {

  @inline
  def ewma(alpha: Double)(oldReading: Reading, newReading: Reading): Reading = {
    newReading.copy(
      value = alpha * newReading.value + (1 - alpha) * oldReading.value
    )
  }
}

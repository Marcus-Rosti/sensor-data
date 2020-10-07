# SDS011 & Akka & Adafruit based airquality monitor

I realized my 10 line python script just wasn't over engineered enough so I... rewrote it.

This is just a pet project but I think I've abstracted things enough that all you should have to do is change some config values.

The [jSerialComm](https://github.com/Fazecast/jSerialComm) input stream library worked out really well for me.

The [akka streams](https://doc.akka.io/docs/akka/current/stream/) throttling/ratelimiting with adafruit was... a pain but not that bad. I was able tune it to be about right.

The whole system has a really small footprint (16m heap and ~50m meta et cetera) and I hooked it up to run as a systemd service on my raspberry pi. 


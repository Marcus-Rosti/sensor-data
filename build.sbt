val AkkaVersion = "2.6.9"
val AkkaHttpVersion = "10.2.1"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "air-quality",
    version := "0.1.0",
    scalacOptions ++= Seq(
          "-opt:l:method,inline",
          "-opt-inline-from:**",
          "-Xlint:_",
          "-Xfatal-warnings",
          "-explaintypes",
          "-feature",
          "-unchecked",
          "-deprecation",
          "-Wunused:_",
          "-encoding",
          "UTF-8"
        ),
    mainClass in assembly := Some("com.marcus.Application"),
    scalaVersion := "2.13.3",
    libraryDependencies ++= Seq(
          "com.fazecast" % "jSerialComm" % "2.6.2",
          "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
          "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
          "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
        )
  )

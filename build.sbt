val AkkaVersion = "2.6.10"
val AkkaHttpVersion = "10.2.1"

inThisBuild(
  List(
    scalaVersion := "2.13.3",
    semanticdbEnabled := true, // enable SemanticDB
    semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
    scalafixScalaBinaryVersion := scalaBinaryVersion.value,
    scalafixOnCompile := true,
    scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.4.0"
  )
)

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
          "-explaintypes",
          "-feature",
          "-unchecked",
          "-deprecation",
          "-Wunused:_",
          "-encoding",
          "UTF-8"
        ),
    mainClass in assembly := Some("com.marcus.Application"),
    libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
          "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
          "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
          "com.fazecast" % "jSerialComm" % "2.6.2",
          "com.pi4j" % "pi4j-core" % "1.1"
        )
  )

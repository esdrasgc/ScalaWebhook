val scala3Version = "3.4.2"

// http4s e Circe usam versões alinhadas, veja em https://http4s.org/
val http4sVersion = "0.23.27" 
val circeVersion = "0.14.9"
val catsEffectVersion = "3.5.4"
val logbackVersion = "1.5.6"

lazy val root = project
  .in(file("."))
  .settings(
    name := "webhook",
    version := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      // --- http4s ---
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      // --- Circe (JSON) ---
      "io.circe" %% "circe-generic" % circeVersion,
      // --- Cats Effect ---
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      // --- Logging ---
      "ch.qos.logback" % "logback-classic" % logbackVersion,
    )
  )
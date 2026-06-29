ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "dev.dustinkeversmith"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "adaptive-problem-space-engine-phase1",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.2" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )

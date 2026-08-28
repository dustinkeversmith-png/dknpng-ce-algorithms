ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "dev.dustinkeversmith"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "adaptive-problem-space-engine-phase1",
    Test / unmanagedSourceDirectories += baseDirectory.value / "tests",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.2" % Test,
      "com.lihaoyi" %% "fastparse" % "3.1.1"
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )


name := "Scala Swing Support"

version := "0.1"

organization := "com.maqicode.swing"

scalaVersion := "2.10.0-RC2"

libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-swing" % _)

libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-actors" % _)

libraryDependencies ++= Seq(
    //"org.scalatest" %% "scalatest" % "1.6.1",
    //"org.scalatest" %% "scalatest" % "2.0.M4" % "test",
    "org.scalatest" % "scalatest_2.10.0-RC2" % "2.0.M4" % "test",
    "com.novocode" % "junit-interface" % "0.6" % "test->default",
    "com.maqicode.util" %% "scala-testing-support" % "0.1",
    "junit" % "junit" % "4.8.2" % "test"
)


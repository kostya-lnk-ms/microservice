name := "MicroService"

version := "1.0"

scalaVersion := "2.11.8"

scalacOptions ++= Seq("-feature")

libraryDependencies += "com.typesafe.akka" %% "akka-http-experimental" % "2.4.10"
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json-experimental" % "2.4.10"
libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.2"
libraryDependencies += "com.typesafe.akka" % "akka-http-testkit_2.11" % "2.4.10"



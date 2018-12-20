name := "cats-bio"

version := "0.1"

scalaVersion in ThisBuild := "2.12.7"

val common = Seq(
  resolvers += Resolver.sonatypeRepo("releases"),
  resolvers += Resolver.sonatypeRepo("snapshots"),

  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8"),

  crossScalaVersions := Seq("2.11.12", "2.12.4"),

  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % "1.4.0",
    "org.typelevel" %% "cats-testkit" % "1.4.0" % Test,
    "org.typelevel" %% "cats-effect" % "1.0.0-RC",
    "org.typelevel" %% "cats-effect-laws" % "1.0.0-RC"
  )
)

scalacOptions in ThisBuild ++= Seq(
  "-language:_",
  "-deprecation",
  "-encoding", "UTF-8", // yes, this is 2 args
  "-feature",
  "-unchecked",
  "-Yno-adapted-args",
  "-Ywarn-dead-code"
)

scalacOptions in ThisBuild ++= Seq(
  "-Ywarn-unused-import",
  "-Ywarn-numeric-widen",
  "-Xlint:-missing-interpolator,_"
)

lazy val core = project.in(file(".")).settings(common)

lazy val bench = project.in(file("bench")).settings(common).dependsOn(core).enablePlugins(JmhPlugin).settings(
  libraryDependencies ++= Seq(
    "org.scalaz" %% "scalaz-ioeffect" % "2.0.0"
  )
)

ThisBuild / tlBaseVersion := "0.1"

ThisBuild / organization := "io.github.irevive"
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("iRevive", "Maksym Ochenashko")
)
ThisBuild / startYear := Some(2025)

val Scala213 = "2.13.17"
ThisBuild / crossScalaVersions := Seq(Scala213, "3.3.7")
ThisBuild / scalaVersion := Scala213 // the default Scala

ThisBuild / tlCiDependencyGraphJob := false

lazy val Versions = new {
  val fs2grpc = "3.0.0"
  val otel4s = "0.14.0"
  val grpc = "1.76.0"

  val munit = "1.0.0"
  val munitCatsEffect = "2.1.0"
}

lazy val munitDependencies = Def.settings(
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % Versions.munit % Test,
    "org.typelevel" %%% "munit-cats-effect" % Versions.munitCatsEffect % Test,
  )
)

lazy val root = tlCrossRootProject.aggregate(
  metrics,
  trace,
  e2e
)

lazy val metrics = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/metrics"))
  .settings(munitDependencies)
  .settings(
    name := "fs2-grpc-otel4s-metrics",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "fs2-grpc-runtime" % Versions.fs2grpc,
      "org.typelevel" %%% "otel4s-core-metrics" % Versions.otel4s,
      "org.typelevel" %%% "otel4s-semconv" % Versions.otel4s,
      "org.typelevel" %%% "otel4s-semconv-experimental" % Versions.otel4s % Test,
      "org.typelevel" %%% "otel4s-semconv-metrics-experimental" % Versions.otel4s % Test,
    )
  )

lazy val trace = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/trace"))
  .enablePlugins(BuildInfoPlugin)
  .settings(munitDependencies)
  .settings(
    name := "fs2-grpc-otel4s-trace",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "fs2-grpc-runtime" % Versions.fs2grpc,
      "org.typelevel" %%% "otel4s-core-trace" % Versions.otel4s,
      "org.typelevel" %%% "otel4s-semconv" % Versions.otel4s,
    ),
    buildInfoPackage := "org.typelevel.fs2grpc.trace",
    buildInfoOptions += sbtbuildinfo.BuildInfoOption.PackagePrivate,
    buildInfoKeys := Seq[BuildInfoKey](
      "version" -> version
    )
  )

lazy val e2e = project
  .enablePlugins(NoPublishPlugin, Fs2Grpc)
  .in(file("modules/e2e-test"))
  .settings(munitDependencies)
  .settings(
    name := "fs2-grpc-e2e-test",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "fs2-grpc-runtime" % Versions.fs2grpc,
      "org.typelevel" %%% "otel4s-core-metrics" % Versions.otel4s,
      "org.typelevel" %%% "otel4s-semconv" % Versions.otel4s,
      "io.grpc" % "grpc-inprocess" % Versions.grpc,
      "org.typelevel" %%% "otel4s-sdk-testkit" % Versions.otel4s % Test,
      "org.typelevel" %%% "otel4s-semconv-experimental" % Versions.otel4s % Test,
      "org.typelevel" %%% "otel4s-semconv-metrics-experimental" % Versions.otel4s % Test,
    ),
    scalapbCodeGeneratorOptions ++= Seq(CodeGeneratorOption.Scala3Sources).filter(_ => tlIsScala3.value)
  )
  .dependsOn(metrics.jvm, trace.jvm)

# fs2-grpc-otel4s

[otel4s](https://github.com/typelevel/otel4s) instrumentation for [fs2-grpc](https://github.com/typelevel/fs2-grpc).

> [!WARN]
> It's experimental software. Use at your own risk.

## Overview

- Integrates otel4s tracing with fs2-grpc ClientAspect and ServiceAspect
- Propagates context through gRPC metadata 
- Default spans follow OpenTelemetry RPC semantic conventions with SpanFinalizer.Strategy.reportAbnormal
- Cross-built for Scala 2.13.17 and 3.3.7 on JVM, Scala.js, and Scala Native

## Installation

```scala
libraryDependencies += "io.github.irevive" %% "fs2-grpc-otel4s-trace" % "0.0.1"
```

## Usage

The example wires the trace aspects into a generated fs2-grpc service. `TestService` denotes your service implementation.

```scala
def tracedService(using TracerProvider[IO]): Resource[IO, ServerServiceDefinition] =
  for {
    serviceAspect <- Resource.eval(TraceServiceAspect.create[IO])
    dispatcher    <- Dispatcher.parallel[IO]
  } yield TestServiceFs2Grpc.serviceFull(dispatcher, new TestService, serviceAspect, ServerOptions.default)

def tracedClient(channel: Channel)(using TracerProvider[IO]): Resource[IO, TestServiceFs2Grpc[IO, Metadata]] =
  for {
    clientAspect <- Resource.eval(TraceClientAspect.create[IO]())
    dispatcher   <- Dispatcher.parallel[IO]
  } yield TestServiceFs2Grpc.mkClientFull(dispatcher, channel, clientAspect, ClientOptions.default)
```

## Configuration

`TraceClientAspect` and `TraceServiceAspect` expose a `Config` that can be refined before creation.

- `withTracerName` selects the tracer acquired from the provided TracerProvider (default fs2-grpc, version from build info).
- `withTextMapUpdater` / `withTextMapGetter` adjust metadata propagation (default ASCII gRPC metadata helpers).
- `withSpanName` and `withAttributes` override naming and attributes; defaults use the gRPC method descriptor plus RPC semantic conventions.
- `withFinalizationStrategy` sets the `SpanFinalizer.Strategy`; defaults to `reportAbnormal`.


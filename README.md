# fs2-grpc-otel4s

[otel4s](https://github.com/typelevel/otel4s) instrumentation for [fs2-grpc](https://github.com/typelevel/fs2-grpc).

> [!IMPORTANT]
> This project is archived.
>
> The integration has moved to the main [typelevel/fs2-grpc](https://github.com/typelevel/fs2-grpc) repository. New users should use `fs2-grpc-otel4s-trace` from `fs2-grpc` instead of this repository.

> [!WARNING]
> It's experimental software. Use at your own risk.

## Overview

- Integrates otel4s tracing with fs2-grpc ClientAspect and ServiceAspect
- Propagates context through gRPC metadata

## Installation

This repository is no longer the recommended source for the integration.

Use `fs2-grpc-otel4s-trace` from [typelevel/fs2-grpc](https://github.com/typelevel/fs2-grpc). While it remains unreleased there, consume the snapshot published from that repository.

The last artifact published from this archived repository was:

```scala
libraryDependencies += "io.github.irevive" %% "fs2-grpc-otel4s-trace" % "0.4.0-R1"
```

## Usage

The API shown below reflects the tracing integration now maintained in `fs2-grpc`.

The example wires the trace aspects into a generated fs2-grpc service. `TestService` denotes your service implementation.

```scala
import org.typelevel.fs2grpc.trace.*

def tracedService(using TracerProvider[IO]): Resource[IO, ServerServiceDefinition] =
  for {
    serviceAspect <- Resource.eval(TraceServiceAspect.create[IO])
    dispatcher    <- Dispatcher.parallel[IO]
  } yield TestServiceFs2Grpc.serviceFull(dispatcher, new TestService, serviceAspect, ServerOptions.default)

def tracedClient(channel: Channel)(using TracerProvider[IO]): Resource[IO, TestServiceFs2Grpc[IO, Metadata]] =
  for {
    clientAspect <- Resource.eval(TraceClientAspect.create[IO])
    dispatcher   <- Dispatcher.parallel[IO]
  } yield TestServiceFs2Grpc.mkClientFull(dispatcher, channel, clientAspect, ClientOptions.default)
```

## Configuration

`TraceClientAspect` and `TraceServiceAspect` expose a `Config` that can be refined before creation.

- `withTracerName` selects the tracer acquired from the provided TracerProvider.
- `withTextMapUpdater` / `withTextMapGetter` adjust metadata injection - default ASCII key.
- `withSpanName` and `withAttributes` override naming and attributes - defaults use the gRPC method descriptor and RPC semantic conventions.
- `withFinalizationStrategy` sets the `SpanFinalizer.Strategy` - defaults to `reportAbnormal`.

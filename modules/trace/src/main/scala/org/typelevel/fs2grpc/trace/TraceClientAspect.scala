/*
 * Copyright 2025 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.fs2grpc.trace

import cats.effect.MonadCancelThrow
import cats.syntax.all._
import fs2.Stream
import fs2.grpc.client._
import io.grpc.Metadata
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.context.propagation.TextMapUpdater
import org.typelevel.otel4s.trace.{SpanFinalizer, SpanKind, Tracer, TracerProvider}

private class TraceClientAspect[F[_]: MonadCancelThrow: Tracer](
    spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String,
    attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes,
    finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy,
)(implicit textMapUpdater: TextMapUpdater[Metadata])
    extends ClientAspect[F, F, Metadata] {

  override def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    propagate(AspectOperation.UnaryToUnaryCallTrailers, callCtx, metadata => run(req, metadata))

  override def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    propagate(AspectOperation.StreamingToUnaryCallTrailers, callCtx, metadata => run(req, metadata))

  override def visitUnaryToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => F[Res]
  ): F[Res] =
    propagate(AspectOperation.UnaryToUnaryCall, callCtx, metadata => run(req, metadata))

  override def visitUnaryToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream.eval(Tracer[F].propagate(new Metadata())).flatMap { metadata =>
      Stream
        .resource(span(AspectOperation.UnaryToStreamingCall, callCtx).resource)
        .flatMap { res =>
          metadata.merge(callCtx.ctx)
          run(req, metadata).translate(res.trace)
        }
    }

  override def visitStreamingToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[Res]
  ): F[Res] =
    propagate(AspectOperation.StreamingToUnaryCall, callCtx, metadata => run(req, metadata))

  override def visitStreamingToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream.eval(Tracer[F].propagate(new Metadata())).flatMap { metadata =>
      Stream
        .resource(span(AspectOperation.StreamingToStreamingCall, callCtx).resource)
        .flatMap { res =>
          metadata.merge(callCtx.ctx)
          run(req, metadata).translate(res.trace)
        }
    }

  private def propagate[A](
      operation: AspectOperation,
      callCtx: ClientCallContext[?, ?, Metadata],
      fa: Metadata => F[A]
  ): F[A] =
    MonadCancelThrow[F].uncancelable { poll =>
      span(operation, callCtx).surround {
        Tracer[F].propagate(new Metadata()).flatMap { metadata =>
          metadata.merge(callCtx.ctx)
          poll(fa(metadata))
        }
      }
    }

  private def span(operation: AspectOperation, ctx: ClientCallContext[?, ?, Metadata]) =
    Tracer[F]
      .spanBuilder(spanName(operation, ctx))
      .addAttributes(attributes(operation, ctx))
      .withSpanKind(SpanKind.Client)
      .withFinalizationStrategy(finalizationStrategy(operation, ctx))
      .build
}

object TraceClientAspect {

  trait Config {
    def tracerName: String
    def textMapUpdater: TextMapUpdater[Metadata]
    def spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String
    def attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes
    def finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy

    def withTracerName(tracerName: String): Config
    def withTextMapUpdater(textMapUpdater: TextMapUpdater[Metadata]): Config
    def withSpanName(spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String): Config
    def withAttributes(attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes): Config
    def withFinalizationStrategy(
        finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy
    ): Config
  }

  object Config {

    def default: Config = {
      val finalizationStrategy = SpanFinalizer.Strategy.reportAbnormal

      ConfigImpl(
        "fs2-grpc",
        TextMapUpdaters.asciiStringMetadataTextMapUpdater,
        (_, ctx) => ctx.methodDescriptor.getFullMethodName,
        (_, ctx) =>
          Attributes(
            CommonAttributes.rpcSystem,
            CommonAttributes.rpcMethod(ctx.methodDescriptor.getBareMethodName),
            CommonAttributes.rpcService(ctx.methodDescriptor.getServiceName),
          ),
        (_, _) => finalizationStrategy
      )
    }

    final case class ConfigImpl(
        tracerName: String,
        textMapUpdater: TextMapUpdater[Metadata],
        spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String,
        attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes,
        finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy
    ) extends Config {
      def withTracerName(tracerName: String): Config =
        copy(tracerName = tracerName)

      def withTextMapUpdater(textMapUpdater: TextMapUpdater[Metadata]): Config =
        copy(textMapUpdater = textMapUpdater)

      def withSpanName(spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String): Config =
        copy(spanName = spanName)

      def withAttributes(attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes): Config =
        copy(attributes = attributes)

      def withFinalizationStrategy(
          finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy
      ): Config =
        copy(finalizationStrategy = finalizationStrategy)
    }

  }

  def create[F[_]: MonadCancelThrow: TracerProvider]: F[ClientAspect[F, F, Metadata]] =
    create(Config.default)

  def create[F[_]: MonadCancelThrow: TracerProvider](config: Config): F[ClientAspect[F, F, Metadata]] =
    TracerProvider[F].tracer(config.tracerName).withVersion(BuildInfo.version).get.map { implicit tracer =>
      implicit val textMapUpdater: TextMapUpdater[Metadata] = config.textMapUpdater

      new TraceClientAspect[F](
        config.spanName,
        config.attributes,
        config.finalizationStrategy,
      )
    }

}

package org.typelevel.fs2grpc.trace

import cats.effect.MonadCancelThrow
import cats.syntax.all._
import fs2.Stream
import fs2.grpc.server._
import io.grpc.Metadata
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.trace._

import scala.util.chaining._

private class TraceServiceAspect[F[_]: MonadCancelThrow: Tracer](
    spanName: (AspectOperation, ServiceCallContext[?, ?]) => String,
    attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes,
    finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy,
)(implicit textMapGetter: TextMapGetter[Metadata])
    extends ServiceAspect[F, F, Metadata] {

  def visitUnaryToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => F[Res]
  ): F[Res] =
    joinOrRoot(AspectOperation.UnaryToUnaryCall, callCtx, run(req, callCtx.metadata))

  def visitUnaryToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => fs2.Stream[F, Res]
  ): fs2.Stream[F, Res] =
    Stream.eval(Tracer[F].joinOrRoot(callCtx.metadata)(Tracer[F].currentSpanContext)).flatMap { ctx =>
      Stream.resource(span(AspectOperation.UnaryToStreamingCall, callCtx, ctx).resource).flatMap { res =>
        run(req, callCtx.metadata).translate(res.trace)
      }
    }

  def visitStreamingToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[F, Req],
      run: (fs2.Stream[F, Req], Metadata) => F[Res]
  ): F[Res] =
    joinOrRoot(AspectOperation.StreamingToUnaryCall, callCtx, run(req, callCtx.metadata))

  def visitStreamingToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[F, Req],
      run: (fs2.Stream[F, Req], Metadata) => fs2.Stream[F, Res]
  ): fs2.Stream[F, Res] =
    Stream.eval(Tracer[F].joinOrRoot(callCtx.metadata)(Tracer[F].currentSpanContext)).flatMap { ctx =>
      Stream.resource(span(AspectOperation.StreamingToStreamingCall, callCtx, ctx).resource).flatMap { res =>
        run(req, callCtx.metadata).translate(res.trace)
      }
    }

  def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    joinOrRoot(AspectOperation.UnaryToUnaryCallTrailers, callCtx, run(req, callCtx.metadata))

  def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[F, Req],
      run: (fs2.Stream[F, Req], Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    joinOrRoot(AspectOperation.StreamingToUnaryCallTrailers, callCtx, run(req, callCtx.metadata))

  private def joinOrRoot[A](operation: AspectOperation, callCtx: ServiceCallContext[?, ?], fa: => F[A]): F[A] =
    MonadCancelThrow[F].uncancelable { poll =>
      Tracer[F].joinOrRoot(callCtx.metadata) {
        span(operation, callCtx, None).surround(poll(fa))
      }
    }

  private def span(operation: AspectOperation, ctx: ServiceCallContext[?, ?], parent: Option[SpanContext]) =
    Tracer[F]
      .spanBuilder(spanName(operation, ctx))
      .addAttributes(attributes(operation, ctx))
      .withSpanKind(SpanKind.Server)
      .withFinalizationStrategy(finalizationStrategy(operation, ctx))
      .pipe(b => parent.fold(b)(b.withParent(_)))
      .build

}

object TraceServiceAspect {

  trait Config {
    def tracerName: String
    def textMapGetter: TextMapGetter[Metadata]
    def spanName: (AspectOperation, ServiceCallContext[?, ?]) => String
    def attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes
    def finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy

    def withTracerName(tracerName: String): Config
    def withTextMapGetter(textMapGetter: TextMapGetter[Metadata]): Config
    def withSpanName(spanName: (AspectOperation, ServiceCallContext[?, ?]) => String): Config
    def withAttributes(attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes): Config
    def withFinalizationStrategy(
        finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy
    ): Config
  }

  object Config {

    def default: Config = {
      val finalizationStrategy = SpanFinalizer.Strategy.reportAbnormal

      ConfigImpl(
        "fs2-grpc",
        TextMapGetters.asciiStringMetadataTextMapGetter,
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
        textMapGetter: TextMapGetter[Metadata],
        spanName: (AspectOperation, ServiceCallContext[?, ?]) => String,
        attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes,
        finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy
    ) extends Config {
      def withTracerName(tracerName: String): Config =
        copy(tracerName = tracerName)

      def withTextMapGetter(textMapGetter: TextMapGetter[Metadata]): Config =
        copy(textMapGetter = textMapGetter)

      def withSpanName(
          spanName: (AspectOperation, ServiceCallContext[?, ?]) => String
      ): Config =
        copy(spanName = spanName)

      def withAttributes(
          attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes
      ): Config =
        copy(attributes = attributes)

      def withFinalizationStrategy(
          finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy
      ): Config =
        copy(finalizationStrategy = finalizationStrategy)
    }

  }

  def create[F[_]: MonadCancelThrow: TracerProvider]: F[ServiceAspect[F, F, Metadata]] =
    create(Config.default)

  def create[F[_]: MonadCancelThrow: TracerProvider](config: Config): F[ServiceAspect[F, F, Metadata]] =
    TracerProvider[F].tracer(config.tracerName).withVersion(BuildInfo.version).get.map { implicit tracer =>
      implicit val textMapGetter: TextMapGetter[Metadata] = config.textMapGetter

      new TraceServiceAspect[F](
        config.spanName,
        config.attributes,
        config.finalizationStrategy,
      )
    }

}

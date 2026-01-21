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

import cats.effect.{Async, IO, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.functor._
import cats.syntax.applicative._
import fs2.grpc.client.ClientOptions
import fs2.grpc.server.ServerOptions
import fs2.grpc.syntax.all._
import hello.world.test_service.{
  TestRequest,
  TestResponse,
  TestServiceFs2Grpc,
  TestServiceFs2GrpcTrailers,
  TestServiceGrpc
}
import fs2.Stream
import io.grpc.{Channel, Metadata, Server, ServerServiceDefinition}
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.sdk.trace.data.SpanData
import munit.{CatsEffectSuite, Location, TestOptions}
import org.typelevel.otel4s.{Attribute, Attributes}
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import org.typelevel.otel4s.oteljava.AttributeConverters._
import org.typelevel.otel4s.semconv.experimental.attributes.RpcExperimentalAttributes
import org.typelevel.otel4s.trace.{SpanContext, Tracer}

class TraceAspectSuite extends CatsEffectSuite {

  withFixture("follow request's span") { fixture =>
    def expectedSpanTree(traceId: String): List[SpanTree[SpanInfo]] = {
      val attributes = Attributes(
        RpcExperimentalAttributes.RpcSystem(RpcExperimentalAttributes.RpcSystemValue.Grpc.value),
        RpcExperimentalAttributes.RpcService(TestServiceGrpc.METHOD_NO_STREAMING.getServiceName),
        RpcExperimentalAttributes.RpcMethod(TestServiceGrpc.METHOD_NO_STREAMING.getBareMethodName),
      )

      List(
        SpanTree(
          SpanInfo( // root 'outside' span
            name = "root",
            attributes = Attributes.empty,
            traceId = traceId,
            kind = SpanKind.INTERNAL,
          ),
          List(
            SpanTree(
              SpanInfo( // client middleware
                name = TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
                attributes = attributes,
                traceId = traceId,
                kind = SpanKind.CLIENT,
              ),
              List(
                SpanTree(
                  SpanInfo( // service middleware
                    name = TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
                    attributes = attributes,
                    traceId = traceId,
                    kind = SpanKind.SERVER,
                  ),
                  List(
                    SpanTree(
                      SpanInfo( // service handler
                        name = "internal-handler:noStreaming",
                        attributes = Attributes.empty,
                        traceId = traceId,
                        kind = SpanKind.INTERNAL,
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    }

    for {
      rootSpanContext <- IO.deferred[SpanContext]
      response <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *> fixture.client.noStreaming(TestRequest(), new Metadata())
      }

      traceContext <- rootSpanContext.get

      spanTree <- fixture.collectSpanTree
      _ <- IO.println(spanTree.map(renderTree).mkString("\n")).whenA(false)
    } yield {
      // server middleware shouldn't inject tracing info into response metadata
      assertEquals(response._2.keys().size(), 0)
      assertEquals(spanTree, expectedSpanTree(traceContext.traceIdHex))
    }
  }

  withFixture(
    "reflect config changes",
    TraceClientAspect.Config.default
      .withTracerName("client-tracer")
      .withAttributes((a, _) => Attributes(Attribute("client-operation", a.toString))),
    TraceServiceAspect.Config.default
      .withTracerName("service-tracer")
      .withAttributes((a, _) => Attributes(Attribute("service-operation", a.toString))),
  ) { fixture =>
    def expectedSpanTree(traceId: String): List[SpanTree[SpanInfo]] = List(
      SpanTree(
        SpanInfo( // root 'outside' span
          name = "root",
          attributes = Attributes.empty,
          traceId = traceId,
          kind = SpanKind.INTERNAL,
        ),
        List(
          SpanTree(
            SpanInfo( // client middleware
              name = TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
              attributes = Attributes(
                Attribute("client-operation", "UnaryToUnaryCallTrailers"),
              ),
              traceId = traceId,
              kind = SpanKind.CLIENT,
            ),
            List(
              SpanTree(
                SpanInfo( // service middleware
                  name = TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
                  attributes = Attributes(
                    Attribute("service-operation", "UnaryToUnaryCall"),
                  ),
                  traceId = traceId,
                  kind = SpanKind.SERVER,
                ),
                List(
                  SpanTree(
                    SpanInfo( // service handler
                      name = "internal-handler:noStreaming",
                      attributes = Attributes.empty,
                      traceId = traceId,
                      kind = SpanKind.INTERNAL,
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

    for {
      rootSpanContext <- IO.deferred[SpanContext]
      response <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *> fixture.client.noStreaming(TestRequest(), new Metadata())
      }

      traceContext <- rootSpanContext.get

      spanTree <- fixture.collectSpanTree
      _ <- IO.println(spanTree.map(renderTree).mkString("\n")).whenA(false)
    } yield {
      // server middleware shouldn't inject tracing info into response metadata
      assertEquals(response._2.keys().size(), 0)
      assertEquals(spanTree, expectedSpanTree(traceContext.traceIdHex))
    }
  }

  case class SpanInfo(
      name: String,
      attributes: Attributes,
      traceId: String,
      kind: SpanKind,
  )

  private def renderTree[A](tree: SpanTree[A]): String = {
    def loop(input: SpanTree[A], depth: Int): String = {
      val prefix = " " * depth
      val next =
        if (input.children.isEmpty) ""
        else " =>\n" + input.children.map(loop(_, depth + 2)).mkString("\n")

      s"$prefix${input.current}$next"
    }

    loop(tree, 0)
  }

  private def withFixture[A](
      opts: TestOptions,
      clientConfig: TraceClientAspect.Config = TraceClientAspect.Config.default,
      serviceConfig: TraceServiceAspect.Config = TraceServiceAspect.Config.default,
  )(f: Fix => IO[A])(implicit loc: Location): Unit =
    test(opts) {
      mkFixture(clientConfig, serviceConfig).use(f)
    }

  private def mkFixture(
      clientConfig: TraceClientAspect.Config,
      serviceConfig: TraceServiceAspect.Config,
  ): Resource[IO, Fix] =
    for {
      testkit <- OtelJavaTestkit.builder[IO].withTextMapPropagators(List(W3CTraceContextPropagator.getInstance())).build

      dispatcher <- Dispatcher.parallel[IO]

      tracerProvider = testkit.tracerProvider

      serviceAspect <- TraceServiceAspect.create[IO](serviceConfig)(Async[IO], tracerProvider).toResource
      clientAspect <- TraceClientAspect.create[IO](clientConfig)(Async[IO], tracerProvider).toResource

      tracer <- testkit.tracerProvider.get("service").toResource

      serviceDefinition = TestServiceFs2Grpc.serviceFull(
        dispatcher,
        new TestService()(tracer),
        serviceAspect,
        ServerOptions.default
      )

      id <- IO.randomUUID.map(_.toString).toResource

      _ <- startServices(id)(serviceDefinition)

      channel <- bindClientChannel(id)

      client = TestServiceFs2GrpcTrailers.mkClientFull(
        dispatcher,
        channel,
        clientAspect,
        ClientOptions.default
      )
    } yield new Fix(client, testkit, tracer)

  private final class Fix(
      val client: TestServiceFs2GrpcTrailers[IO, Metadata],
      val testkit: OtelJavaTestkit[IO],
      val tracer: Tracer[IO]
  ) {

    def collectSpanTree: IO[List[SpanTree[SpanInfo]]] =
      for {
        spans <- testkit.finishedSpans[SpanData]
        // _ <- IO.println(spans.map(_.toString).mkString("\n\n"))
      } yield SpanTree.of(spans).map { tree =>
        tree.map(s => SpanInfo(s.getName, s.getAttributes.toScala, s.getTraceId, s.getKind))
      }

  }

  sealed trait Op
  object Op {
    case object NoStreaming extends Op
    case object ClientStreaming extends Op
    case object ServerStreaming extends Op
    case object BothStreaming extends Op
  }

  case class ServerEvent(op: Op, ctx: Metadata)

  private class TestService(implicit T: Tracer[IO]) extends TestServiceFs2Grpc[IO, Metadata] {
    def noStreaming(request: TestRequest, ctx: Metadata): IO[TestResponse] =
      T.span("internal-handler:noStreaming").surround {
        IO.pure(TestResponse())
      }

    def clientStreaming(request: Stream[IO, TestRequest], ctx: Metadata): IO[TestResponse] =
      T.span("internal-handler:clientStreaming").surround {
        IO.pure(TestResponse())
      }

    def serverStreaming(request: TestRequest, ctx: Metadata): Stream[IO, TestResponse] =
      Stream.eval {
        T.span("internal-handler:noStreaming").surround {
          IO.pure(TestResponse())
        }
      }

    def bothStreaming(request: Stream[IO, TestRequest], ctx: Metadata): Stream[IO, TestResponse] =
      Stream.eval {
        T.span("internal-handler:noStreaming").surround {
          IO.pure(TestResponse())
        }
      }
  }

  private def startServices(id: String)(xs: ServerServiceDefinition): Resource[IO, Server] =
    InProcessServerBuilder
      .forName(id)
      .addService(xs)
      .resource[IO]
      .evalTap(s => IO.delay(s.start()))

  private def bindClientChannel(id: String): Resource[IO, Channel] =
    InProcessChannelBuilder.forName(id).usePlaintext().resource[IO]

}

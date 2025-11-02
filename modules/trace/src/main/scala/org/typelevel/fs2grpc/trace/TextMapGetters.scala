package org.typelevel.fs2grpc.trace

import io.grpc.Metadata
import org.typelevel.otel4s.context.propagation.TextMapGetter

import scala.jdk.CollectionConverters._

object TextMapGetters {

  val asciiStringMetadataTextMapGetter: TextMapGetter[Metadata] =
    new TextMapGetter[Metadata] {
      def get(carrier: Metadata, key: String): Option[String] =
        Option(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))

      def keys(carrier: Metadata): Iterable[String] =
        carrier.keys().asScala
    }

}

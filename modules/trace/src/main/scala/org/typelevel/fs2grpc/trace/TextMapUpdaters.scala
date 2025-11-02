package org.typelevel.fs2grpc.trace

import io.grpc.Metadata
import org.typelevel.otel4s.context.propagation.TextMapUpdater

object TextMapUpdaters {

  val asciiStringMetadataTextMapUpdater: TextMapUpdater[Metadata] =
    new TextMapUpdater[Metadata] {
      def updated(carrier: Metadata, key: String, value: String): Metadata = {
        carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
        carrier
      }
    }

}

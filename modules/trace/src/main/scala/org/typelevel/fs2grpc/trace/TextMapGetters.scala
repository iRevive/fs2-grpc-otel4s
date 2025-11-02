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

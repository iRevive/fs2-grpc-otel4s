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

import org.typelevel.otel4s.{Attribute, AttributeKey}

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans/]]
  */
private[trace] object CommonAttributes {

  private object Keys {
    val rpcMethod: AttributeKey[String] = AttributeKey.string("rpc.method")
    val rpcService: AttributeKey[String] = AttributeKey.string("rpc.service")
  }

  val rpcSystem: Attribute[String] =
    Attribute("rpc.system", "grpc")

  def rpcMethod(method: String): Attribute[String] =
    Keys.rpcMethod(method)

  def rpcService(service: String): Attribute[String] =
    Keys.rpcService(service)

}

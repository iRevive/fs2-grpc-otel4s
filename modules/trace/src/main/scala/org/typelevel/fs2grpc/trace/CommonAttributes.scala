package org.typelevel.fs2grpc.trace

import org.typelevel.otel4s.Attribute

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans/]]
  */
private[trace] object CommonAttributes {

  def rpcSystem =
    Attribute("rpc.system", "grpc")

  def rpcMethod(method: String) =
    Attribute("rpc.method", method)

  def rpcService(service: String) =
    Attribute("rpc.service", service)

}

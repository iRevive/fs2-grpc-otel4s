package org.typelevel.fs2grpc.trace

sealed trait AspectOperation

object AspectOperation {

  case object UnaryToUnaryCall extends AspectOperation
  case object UnaryToStreamingCall extends AspectOperation
  case object StreamingToUnaryCall extends AspectOperation
  case object StreamingToStreamingCall extends AspectOperation
  case object UnaryToUnaryCallTrailers extends AspectOperation
  case object StreamingToUnaryCallTrailers extends AspectOperation

}

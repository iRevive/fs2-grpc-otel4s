package org.typelevel.fs2grpc.trace

import cats.Functor
import cats.syntax.functor._
import org.typelevel.otel4s.sdk.trace.data.SpanData

/** A tree representation of a span.
  */
sealed trait SpanTree[A] {

  /** The current span.
    */
  def current: A

  /** Children of the current span.
    */
  def children: List[SpanTree[A]]

}

object SpanTree {

  def apply[A](span: A): SpanTree[A] =
    Impl(span, Nil)

  def apply[A](span: A, children: Iterable[SpanTree[A]]): SpanTree[A] =
    Impl(span, children.toList)

  /** Transforms the given spans into the tree-like structure.
    */
  def of(spans: Iterable[SpanData]): List[SpanTree[SpanData]] = {
    val byParent = spans.toList.groupBy(s => s.parentSpanContext.map(_.spanIdHex))
    val topNodes = byParent.getOrElse(None, Nil)
    val bottomToTop = sortNodesByDepth(0, topNodes, byParent, Nil)
    val maxDepth = bottomToTop.headOption.map(_.depth).getOrElse(0)
    buildFromBottom(maxDepth, bottomToTop, byParent, Map.empty)
  }

  implicit val spanTreeFunctor: Functor[SpanTree] =
    new Functor[SpanTree] {
      def map[A, B](fa: SpanTree[A])(f: A => B): SpanTree[B] =
        SpanTree(f(fa.current), fa.children.map(_.fmap(f)))
    }

  private case class EntryWithDepth(data: SpanData, depth: Int)

  @annotation.tailrec
  private def sortNodesByDepth(
      depth: Int,
      nodesInDepth: List[SpanData],
      nodesByParent: Map[Option[String], List[SpanData]],
      acc: List[EntryWithDepth]
  ): List[EntryWithDepth] = {
    val withDepth = nodesInDepth.map(n => EntryWithDepth(n, depth))
    val calculated = withDepth ++ acc

    val children = nodesInDepth.flatMap { n =>
      nodesByParent.getOrElse(Some(n.spanContext.spanIdHex), Nil)
    }

    children match {
      case Nil =>
        calculated

      case _ =>
        sortNodesByDepth(depth + 1, children, nodesByParent, calculated)
    }
  }

  @annotation.tailrec
  private def buildFromBottom(
      depth: Int,
      remaining: List[EntryWithDepth],
      nodesByParent: Map[Option[String], List[SpanData]],
      processedNodesById: Map[String, SpanTree[SpanData]]
  ): List[SpanTree[SpanData]] = {
    val (nodesOnCurrentDepth, rest) = remaining.span(_.depth == depth)
    val newProcessedNodes = nodesOnCurrentDepth.map { n =>
      val nodeId = n.data.spanContext.spanIdHex
      val children = nodesByParent
        .getOrElse(Some(nodeId), Nil)
        .flatMap(c => processedNodesById.get(c.spanContext.spanIdHex))
      val leaf = SpanTree(n.data, children)
      nodeId -> leaf
    }.toMap

    if (depth > 0) {
      buildFromBottom(
        depth - 1,
        rest,
        nodesByParent,
        processedNodesById ++ newProcessedNodes
      )
    } else {
      // top nodes
      newProcessedNodes.values.toList
    }
  }

  private final case class Impl[A](
      current: A,
      children: List[SpanTree[A]]
  ) extends SpanTree[A]

}

/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.cypher.internal.rewriting.rewriters

import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.QuantifiedPath
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.Foldable.FoldableAny
import org.neo4j.cypher.internal.util.Foldable.SkipChildren
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.Rewritable.RewritableAny
import org.neo4j.cypher.internal.util.Rewriter
import org.neo4j.cypher.internal.util.topDown

/**
 * A helper trait used by `normalizePredicates`.
 */
trait PredicateNormalizer {

  /**
   * Extract not normalized predicates from a pattern element.
   */
  val extract: PartialFunction[AnyRef, IndexedSeq[Expression]]

  /**
   * Replace a pattern element containing not normalized predicates by the same element with predicates removed.
   */
  val replace: PartialFunction[AnyRef, AnyRef]

  /**
   * Traverse into pattern and extract not normalized predicates from its elements.
   */
  final def extractAllFrom(pattern: AnyRef): Seq[Expression] =
    pattern.folder.treeFold(Vector.empty[Expression]) {
      case _: QuantifiedPath => acc => SkipChildren(acc)
      case patternElement: AnyRef if extract.isDefinedAt(patternElement) =>
        acc => TraverseChildren(acc ++ extract(patternElement))
      case _ => acc => TraverseChildren(acc)
    }

  final def replaceAllIn[T <: AnyRef](pattern: T): T =
    pattern.endoRewrite(
      topDown(
        Rewriter.lift(replace),
        stopper = {
          case _: QuantifiedPath => true
          case _                 => false
        }
      )
    )
}

object PredicateNormalizer {

  def defaultNormalizer(anonymousVariableNameGenerator: AnonymousVariableNameGenerator): PredicateNormalizer =
    PredicateNormalizerChain(
      PropertyPredicateNormalizer(anonymousVariableNameGenerator),
      LabelExpressionsInPatternsNormalizer,
      NodePatternPredicateNormalizer,
      RelationshipPatternPredicateNormalizer
    )
}

/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.ir.helpers

import org.neo4j.cypher.internal.expressions.And
import org.neo4j.cypher.internal.expressions.Ands
import org.neo4j.cypher.internal.expressions.Equals
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.HasLabels
import org.neo4j.cypher.internal.expressions.In
import org.neo4j.cypher.internal.expressions.IsNotNull
import org.neo4j.cypher.internal.expressions.IsNull
import org.neo4j.cypher.internal.expressions.Not
import org.neo4j.cypher.internal.expressions.NotEquals
import org.neo4j.cypher.internal.expressions.Or
import org.neo4j.cypher.internal.expressions.Ors
import org.neo4j.cypher.internal.expressions.Property
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.expressions.Xor
import org.neo4j.cypher.internal.ir.CreatesPropertyKeys

object LabelExpressionEvaluator {

  case class NodesToCheckOverlap(updatedNode: Option[String], matchedNode: String) {

    def contains(node: String): Boolean =
      updatedNode.contains(node) || matchedNode == node
  }

  /**
   * Evaluates a label expression given a set of nodes and labels for which "HasLabels" evaluates to true.
   *
   * E.g.
   * Given
   * labels: "A"
   * nodes:  "x", "y"
   *
   * Expression            Return value      Comment
   * (x:A)                 true
   * (x:A&B) AND (y:A)     false             (x:B) is evaluated to false, because "B" is not a given label
   * (x:A|B) AND (y:!B)    true
   * x.prop = 5            None              Expression is unknown
   * (x:A) AND (z:A)       None              z is in the given set of nodes
   *
   * @param expression - the expression to evaluate.
   * @param nodes - the nodes of interest, returns None if any other node is encountered.
   * @param labels - the labels evaluated to true, all other labels will be evaluated to false.
   * @param propertiesToCreate - the created node and property
   * @return - the evaluated expression value or None if the expression is unknown.
   */
  // Todo: Add lamda function for returning a boolean or a set conflicting labels
  def labelAndPropertyExpressionEvaluator(
    expression: Expression,
    nodes: NodesToCheckOverlap,
    labels: Set[String],
    propertiesToCreate: Option[CreatesPropertyKeys] = None
  ): TailRecOption[Boolean] = {
    expression match {
      case HasLabels(Variable(node), hasLabels) if nodes.contains(node) =>
        TailRecOption.some(labels.exists(hasLabels.map(_.name).contains))
      case Property(Variable(node), pkn @ PropertyKeyName(_)) if (nodes.matchedNode == node) =>
        TailRecOption.some(propertiesToCreate.exists(_.overlaps(pkn)))
      case IsNull(Property(Variable(node), pkn @ PropertyKeyName(_))) =>
        TailRecOption.some(nodes.matchedNode == node && !propertiesToCreate.exists(_.overlaps(pkn)))
      case IsNotNull(Property(Variable(node), pkn @ PropertyKeyName(_))) =>
        TailRecOption.some(nodes.matchedNode == node && propertiesToCreate.exists(_.overlaps(pkn)))
      case In(Property(Variable(node), pkn @ PropertyKeyName(_)), _) if (nodes.matchedNode == node) =>
        TailRecOption.some(propertiesToCreate.exists(_.overlaps(pkn)))
      case In(_, Property(Variable(node), pkn @ PropertyKeyName(_))) if (nodes.matchedNode == node) =>
        TailRecOption.some(propertiesToCreate.exists(_.overlaps(pkn)))
      case And(lhs, rhs) => TailRecOption.tailcall(evalBinFunc(nodes, lhs, rhs, labels, (lhs, rhs) => lhs && rhs))
      case Or(lhs, rhs)  => TailRecOption.tailcall(evalBinFunc(nodes, lhs, rhs, labels, (lhs, rhs) => lhs || rhs))
      case Not(expr) =>
        TailRecOption.tailcall(labelAndPropertyExpressionEvaluator(expr, nodes, labels, propertiesToCreate)).map(!_)
      case Ors(exprs) =>
        TailRecOption.traverse(exprs.toSeq)(expr =>
          labelAndPropertyExpressionEvaluator(expr, nodes, labels, propertiesToCreate)
        )
          .map(_.contains(true))
      case Ands(exprs) =>
        TailRecOption.traverse(exprs.toSeq)(expr =>
          labelAndPropertyExpressionEvaluator(expr, nodes, labels, propertiesToCreate)
        )
          .map(!_.contains(false))
      case Xor(lhs, rhs)       => evalBinFunc(nodes, lhs, rhs, labels, (lhs, rhs) => lhs ^ rhs)
      case Equals(lhs, rhs)    => evalBinFunc(nodes, lhs, rhs, labels, (lhs, rhs) => lhs == rhs)
      case NotEquals(lhs, rhs) => evalBinFunc(nodes, lhs, rhs, labels, (lhs, rhs) => lhs != rhs)
      case _                   => TailRecOption.none
    }
  }

  private def evalBinFunc(
    nodes: NodesToCheckOverlap,
    a: Expression,
    b: Expression,
    labels: Set[String],
    op: (Boolean, Boolean) => Boolean
  ): TailRecOption[Boolean] =
    for {
      lhs <- TailRecOption.tailcall(labelAndPropertyExpressionEvaluator(a, nodes, labels))
      rhs <- TailRecOption.tailcall(labelAndPropertyExpressionEvaluator(b, nodes, labels))
    } yield op(lhs, rhs)
}

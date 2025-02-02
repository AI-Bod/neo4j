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
package org.neo4j.cypher.internal.compiler.planner

import org.neo4j.cypher.internal.ast.ASTAnnotationMap
import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.ast.semantics.ExpressionTypeInfo
import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTRelationship
import org.neo4j.cypher.internal.util.symbols.CTString
import org.neo4j.cypher.internal.util.symbols.TypeSpec
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class SemanticTableTest extends CypherFunSuite with AstConstructionTestSupport {

  private val position123 = InputPosition(1, 2, 3)
  private val position000 = InputPosition(0, 0, 0)

  test("can add nodes to a SemanticTable") {
    val table = SemanticTable().addNode(varFor("x"))

    table.isNode("x") should be(true)
    table.isRelationship("x") should be(false)
  }

  test("can add rels to a SemanticTable") {
    val table = SemanticTable().addRelationship(varFor("r"))

    table.isRelationship("r") should be(true)
    table.isNode("r") should be(false)
  }

  test("doesn't share mutable references after being copied") {
    val table1 = SemanticTable()
    val table2 = table1.copy()

    (table1.resolvedLabelNames eq table2.resolvedLabelNames) should be(false)
    (table1.resolvedPropertyKeyNames eq table2.resolvedPropertyKeyNames) should be(false)
    (table1.resolvedRelTypeNames eq table2.resolvedRelTypeNames) should be(false)
  }

  test("should be able to tell the type of an variable") {
    val table = SemanticTable().addNode(varFor("a", position123)).addRelationship(varFor("b", position123))

    table.getTypeFor("a") should be(CTNode.invariant)
    table.getTypeFor("b") should be(CTRelationship.invariant)
  }

  test("should be able to tell the type of an variable if there is an unknown type involved") {
    val table = SemanticTable(ASTAnnotationMap.empty.updated(
      varFor("a", position000),
      ExpressionTypeInfo(TypeSpec.all, None)
    )).addNode(varFor("a", position123))

    table.getTypeFor("a") should be(CTNode.invariant)
  }

  test("should be able to tell the type of an variable if there is an unknown type involved other order") {
    val table = SemanticTable(ASTAnnotationMap.empty[Expression, ExpressionTypeInfo].updated(
      varFor("a", position123),
      ExpressionTypeInfo(CTNode.invariant, None)
    ).updated(varFor("a", position000), ExpressionTypeInfo(TypeSpec.all, None)))

    table.getTypeFor("a") should be(CTNode.invariant)
  }

  test("should fail when asking for an unknown variable") {
    val table = SemanticTable()

    intercept[IllegalStateException](table.getTypeFor("a"))
  }

  test("should fail if the semantic table is confusing") {
    val table = SemanticTable(ASTAnnotationMap.empty[Expression, ExpressionTypeInfo].updated(
      varFor("a", position123),
      ExpressionTypeInfo(CTNode.invariant, None)
    ).updated(varFor("a", position000), ExpressionTypeInfo(CTRelationship.invariant, None)))

    intercept[IllegalStateException](table.getTypeFor("a"))
  }

  test("isInteger should only be true if we are certain it is an integer") {
    val table = SemanticTable(ASTAnnotationMap.empty[Expression, ExpressionTypeInfo]
      .updated(varFor("a", position000), ExpressionTypeInfo(CTInteger.invariant, None))
      .updated(varFor("b", position000), ExpressionTypeInfo(TypeSpec.all, None))
      .updated(varFor("c", position000), ExpressionTypeInfo(CTInteger.invariant | CTString.invariant, None)))

    table.isInteger(varFor("a", position000)) should be(true)
    table.isInteger(varFor("b", position000)) should be(false)
    table.isInteger(varFor("c", position000)) should be(false)
  }
}

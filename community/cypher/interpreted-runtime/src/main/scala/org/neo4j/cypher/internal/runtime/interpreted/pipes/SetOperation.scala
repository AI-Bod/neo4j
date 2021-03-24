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
package org.neo4j.cypher.internal.runtime.interpreted.pipes

import org.neo4j.cypher.internal.runtime
import org.neo4j.cypher.internal.runtime.CastSupport
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.ExpressionCursors
import org.neo4j.cypher.internal.runtime.NodeOperations
import org.neo4j.cypher.internal.runtime.Operations
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.RelationshipOperations
import org.neo4j.cypher.internal.runtime.interpreted.IsMap
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.makeValueNeoSafe
import org.neo4j.exceptions.CypherTypeException
import org.neo4j.exceptions.InvalidArgumentException
import org.neo4j.internal.kernel.api.NodeCursor
import org.neo4j.internal.kernel.api.PropertyCursor
import org.neo4j.internal.kernel.api.RelationshipScanCursor
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.MapValue
import org.neo4j.values.virtual.NodeValue
import org.neo4j.values.virtual.RelationshipValue
import org.neo4j.values.virtual.VirtualNodeValue
import org.neo4j.values.virtual.VirtualRelationshipValue

import scala.collection.mutable.ArrayBuffer

trait SetOperation {

  def set(executionContext: CypherRow, state: QueryState): Long

  def name: String

  def needsExclusiveLock: Boolean
}

object SetOperation {

  def toMap(executionContext: CypherRow, state: QueryState, expression: Expression): MapValue = {
    /* Make the map expression look like a map */
    expression(executionContext, state) match {
      case IsMap(map) => map(state)
      case x => throw new CypherTypeException(s"Expected $expression to be a map, but it was :`$x`")
    }
  }
}

abstract class AbstractSetPropertyOperation extends SetOperation {

  protected def setProperty[T](context: CypherRow,
                               state: QueryState,
                               ops: Operations[T, _],
                               itemId: Long,
                               propertyKey: LazyPropertyKey,
                               expression: Expression): Boolean = {

    val queryContext = state.query
    val maybePropertyKey = propertyKey.id(queryContext) // if the key was already looked up
    val value = makeValueNeoSafe(expression(context, state))

    if (value eq Values.NO_VALUE) {
      val optPropertyKeyId = queryContext.getOptPropertyKeyId(propertyKey.name)
      if (optPropertyKeyId.isDefined) {
        ops.removeProperty(itemId, optPropertyKeyId.get)
      } else {
        false
      }
    } else {
      val propertyId = if (maybePropertyKey == LazyPropertyKey.UNKNOWN) {
        queryContext.getOrCreatePropertyKeyId(propertyKey.name)
      } else maybePropertyKey
      ops.setProperty(itemId, propertyId, value)
      true
    }
  }
}

abstract class SetEntityPropertyOperation[T](itemName: String,
                                             propertyKey: LazyPropertyKey,
                                             expression: Expression)
  extends AbstractSetPropertyOperation {

  override def set(executionContext: CypherRow, state: QueryState): Long = {
    val item = executionContext.getByName(itemName)
    if (!(item eq Values.NO_VALUE)) {
      val itemId = id(item)
      val ops = operations(state.query)
      if (needsExclusiveLock) ops.acquireExclusiveLock(itemId)

      invalidateCachedProperties(executionContext, itemId)

      val wasSet = try {
        setProperty[T](executionContext, state, ops, itemId, propertyKey, expression)
      } finally if (needsExclusiveLock) ops.releaseExclusiveLock(itemId)

      if (wasSet) 1L else 0L
    } else {
      0L
    }
  }

  protected def id(item: Any): Long

  protected def operations(qtx: QueryContext): Operations[T, _]

  protected def invalidateCachedProperties(executionContext: CypherRow, id: Long): Unit
}

case class SetNodePropertyOperation(nodeName: String,
                                    propertyKey: LazyPropertyKey,
                                    expression: Expression,
                                    needsExclusiveLock: Boolean = true)
  extends SetEntityPropertyOperation[NodeValue](nodeName, propertyKey, expression) {

  override def name = "SetNodeProperty"

  override protected def id(item: Any): Long = CastSupport.castOrFail[VirtualNodeValue](item).id()

  override protected def operations(qtx: QueryContext): NodeOperations = qtx.nodeOps

  override protected def invalidateCachedProperties(executionContext: CypherRow, id: Long): Unit =
    executionContext.invalidateCachedNodeProperties(id)
}

case class SetRelationshipPropertyOperation(relName: String,
                                            propertyKey: LazyPropertyKey,
                                            expression: Expression,
                                            needsExclusiveLock: Boolean = true)
  extends SetEntityPropertyOperation[RelationshipValue](relName, propertyKey, expression) {

  override def name = "SetRelationshipProperty"

  override protected def id(item: Any): Long = CastSupport.castOrFail[VirtualRelationshipValue](item).id()

  override protected def operations(qtx: QueryContext): RelationshipOperations = qtx.relationshipOps

  override protected def invalidateCachedProperties(executionContext: CypherRow, id: Long): Unit =
    executionContext.invalidateCachedRelationshipProperties(id)
}

case class SetPropertyOperation(entityExpr: Expression, propertyKey: LazyPropertyKey, expression: Expression)
  extends AbstractSetPropertyOperation {

  override def name: String = "SetProperty"

  override def set(executionContext: CypherRow, state: QueryState): Long = {
    val resolvedEntity = entityExpr(executionContext, state)
    if (!(resolvedEntity eq Values.NO_VALUE)) {
      def setIt[T](entityId: Long, ops: Operations[T, _], invalidation: Long => Unit): Boolean = {
        // better safe than sorry let's lock the entity
        ops.acquireExclusiveLock(entityId)

        invalidation(entityId)

        try {
          setProperty(executionContext, state, ops, entityId, propertyKey, expression)
        } finally ops.releaseExclusiveLock(entityId)
      }

      val wasSet = resolvedEntity match {
        case node: VirtualNodeValue => setIt(node.id(), state.query.nodeOps, (id:Long) => executionContext.invalidateCachedNodeProperties(id))
        case rel: VirtualRelationshipValue => setIt(rel.id(), state.query.relationshipOps,
          (id: Long) => executionContext.invalidateCachedRelationshipProperties(id))
        case _ => throw new InvalidArgumentException(
          s"The expression $entityExpr should have been a node or a relationship, but got $resolvedEntity")
      }

      if (wasSet) 1L else 0L
    } else {
      0L
    }
  }

  override def needsExclusiveLock = true
}

abstract class AbstractSetPropertyFromMapOperation() extends SetOperation {

  protected def setPropertiesFromMap[T, CURSOR]( propertyCursor: PropertyCursor,
                                                 qtx: QueryContext,
                                                 entityCursor: CURSOR,
                                                 ops: Operations[T, CURSOR],
                                                 itemId: Long,
                                                 map: MapValue,
                                                 removeOtherProps: Boolean): Long = {
    val setKeys = new ArrayBuffer[String]()
    val setValues = new ArrayBuffer[AnyValue]()
    var setCount = 0L

    /*Set all map values on the property container*/
    map.foreach((k: String, v: AnyValue) => {
      if (v eq Values.NO_VALUE) {
        val optPropertyKeyId = qtx.getOptPropertyKeyId(k)
        if (optPropertyKeyId.isDefined) {
          setCount += 1
          ops.removeProperty(itemId, optPropertyKeyId.get)
        }
      }
      else {
        setKeys += k
        setValues += v
      }
    })

    // Adding property keys is O(|totalPropertyKeyIds|^2) per call, so
    // batch creation is way faster is graphs with many propertyKeyIds
    val propertyIds = qtx.getOrCreatePropertyKeyIds(setKeys.toArray)
    for (i <- propertyIds.indices) {
      ops.setProperty(itemId, propertyIds(i), runtime.makeValueNeoSafe(setValues(i)))
      setCount += 1
    }

    /*Remove all other properties from the property container ( SET n = {prop1: ...})*/
    if (removeOtherProps) {
      val seen = propertyIds.toSet
      val properties = ops.propertyKeyIds(itemId, entityCursor, propertyCursor).filterNot(seen.contains).toSet
      for (propertyKeyId <- properties) {
        if (ops.removeProperty(itemId, propertyKeyId))
          setCount += 1
      }
    }

    setCount
  }
}

abstract class SetNodeOrRelPropertyFromMapOperation[T, CURSOR](itemName: String,
                                                               expression: Expression,
                                                               removeOtherProps: Boolean) extends AbstractSetPropertyFromMapOperation() {
  override def set(executionContext: CypherRow, state: QueryState): Long = {
    val item = executionContext.getByName(itemName)
    if (!(item eq Values.NO_VALUE)) {
      val ops = operations(state.query)
      val itemId = id(item)
      if (needsExclusiveLock) ops.acquireExclusiveLock(itemId)

      invalidateCachedProperties(executionContext, itemId)

      try {
        val map = SetOperation.toMap(executionContext, state, expression)

        setPropertiesFromMap(state.cursors.propertyCursor, state.query, entityCursor(state.cursors), ops, itemId, map, removeOtherProps)
      } finally if (needsExclusiveLock) ops.releaseExclusiveLock(itemId)
    } else {
      0L
    }
  }

  protected def id(item: Any): Long

  protected def operations(qtx: QueryContext): Operations[T, CURSOR]

  protected def invalidateCachedProperties(executionContext: CypherRow, id: Long): Unit

  protected def entityCursor(cursors: ExpressionCursors): CURSOR
}

case class SetNodePropertyFromMapOperation(nodeName: String, expression: Expression,
                                           removeOtherProps: Boolean, needsExclusiveLock: Boolean = true)
  extends SetNodeOrRelPropertyFromMapOperation[NodeValue, NodeCursor](nodeName, expression, removeOtherProps) {

  override def name = "SetNodePropertyFromMap"

  override protected def id(item: Any): Long = CastSupport.castOrFail[VirtualNodeValue](item).id()

  override protected def operations(qtx: QueryContext): NodeOperations = qtx.nodeOps

  override protected def entityCursor(cursors: ExpressionCursors): NodeCursor = cursors.nodeCursor

  override protected def invalidateCachedProperties(executionContext: CypherRow, id: Long): Unit = executionContext.invalidateCachedNodeProperties(id)
}


case class SetRelationshipPropertyFromMapOperation(relName: String,
                                                   expression: Expression,
                                                   removeOtherProps: Boolean,
                                                   needsExclusiveLock: Boolean = true)
  extends SetNodeOrRelPropertyFromMapOperation[RelationshipValue, RelationshipScanCursor](relName, expression, removeOtherProps) {

  override def name = "SetRelationshipPropertyFromMap"

  override protected def id(item: Any): Long = CastSupport.castOrFail[VirtualRelationshipValue](item).id()

  override protected def operations(qtx: QueryContext): RelationshipOperations = qtx.relationshipOps

  override protected def entityCursor(cursors: ExpressionCursors): RelationshipScanCursor = cursors.relationshipScanCursor

  override protected def invalidateCachedProperties(executionContext: CypherRow, id: Long): Unit = executionContext.invalidateCachedRelationshipProperties(id)
}

case class SetPropertyFromMapOperation(entityExpr: Expression,
                                       expression: Expression,
                                       removeOtherProps: Boolean)
  extends AbstractSetPropertyFromMapOperation() {

  override def name = "SetPropertyFromMap"

  override def set(executionContext: CypherRow, state: QueryState): Long = {
    val resolvedEntity = entityExpr(executionContext, state)
    if (resolvedEntity != Values.NO_VALUE) {
      def setIt[T, CURSOR](entityId: Long, ops: Operations[T, CURSOR], cursor: CURSOR, invalidation: Long => Unit): Long = {
        // better safe than sorry let's lock the entity
        ops.acquireExclusiveLock(entityId)

        invalidation(entityId)

        try {
          val map = SetOperation.toMap(executionContext, state, expression)

          setPropertiesFromMap(state.cursors.propertyCursor, state.query, cursor, ops, entityId, map, removeOtherProps)
        } finally ops.releaseExclusiveLock(entityId)
      }

      resolvedEntity match {
        case node: VirtualNodeValue => setIt(node.id(), state.query.nodeOps, state.cursors.nodeCursor,
          (id: Long) => executionContext.invalidateCachedNodeProperties(id))
        case rel: VirtualRelationshipValue => setIt(rel.id(), state.query.relationshipOps, state.cursors.relationshipScanCursor,
          (id: Long) => executionContext.invalidateCachedRelationshipProperties(id))
        case _ => throw new InvalidArgumentException(
          s"The expression $entityExpr should have been a node or a relationship, but got $resolvedEntity")
      }
    } else {
      0L
    }
  }

  override def needsExclusiveLock = true
}

case class SetLabelsOperation(nodeName: String, labels: Seq[LazyLabel]) extends SetOperation {

  override def set(executionContext: CypherRow, state: QueryState): Long = {
    val value: AnyValue = executionContext.getByName(nodeName)
    if (!(value eq Values.NO_VALUE)) {
      val nodeId = CastSupport.castOrFail[VirtualNodeValue](value).id()
      val labelIds = labels.map(_.getOrCreateId(state.query))
      state.query.setLabelsOnNode(nodeId, labelIds.iterator).toLong
    } else {
      0L
    }
  }

  override def name = "SetLabels"

  override def needsExclusiveLock = false
}

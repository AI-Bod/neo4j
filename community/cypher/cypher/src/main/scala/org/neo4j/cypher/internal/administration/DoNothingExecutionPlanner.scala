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
package org.neo4j.cypher.internal.administration

import org.neo4j.cypher.internal.AdministrationCommandRuntime.Show
import org.neo4j.cypher.internal.AdministrationCommandRuntime.Show.showDatabaseName
import org.neo4j.cypher.internal.AdministrationCommandRuntime.Show.showString
import org.neo4j.cypher.internal.AdministrationCommandRuntime.checkNamespaceExists
import org.neo4j.cypher.internal.AdministrationCommandRuntime.followerError
import org.neo4j.cypher.internal.AdministrationCommandRuntime.getDatabaseNameFields
import org.neo4j.cypher.internal.AdministrationCommandRuntime.getNameFields
import org.neo4j.cypher.internal.ExecutionEngine
import org.neo4j.cypher.internal.ExecutionPlan
import org.neo4j.cypher.internal.ast.DatabaseName
import org.neo4j.cypher.internal.expressions.Parameter
import org.neo4j.cypher.internal.logical.plans.DatabaseTypeFilter
import org.neo4j.cypher.internal.procs.QueryHandler
import org.neo4j.cypher.internal.procs.UpdatingSystemCommandExecutionPlan
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.COMPOSITE_DATABASE
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_NAME
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DEFAULT_NAMESPACE
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.NAMESPACE_PROPERTY
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.NAME_PROPERTY
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.PRIMARY_PROPERTY
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.REMOTE_DATABASE
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.TARGETS
import org.neo4j.exceptions.DatabaseAdministrationOnFollowerException
import org.neo4j.internal.kernel.api.security.SecurityAuthorizationHandler
import org.neo4j.kernel.api.exceptions.Status
import org.neo4j.kernel.api.exceptions.Status.HasStatus
import org.neo4j.values.virtual.MapValue
import org.neo4j.values.virtual.VirtualValues

case class DoNothingExecutionPlanner(
  normalExecutionEngine: ExecutionEngine,
  securityAuthorizationHandler: SecurityAuthorizationHandler
) {

  def planDoNothingIfNotExists(
    label: String,
    name: Either[String, Parameter],
    valueMapper: String => String,
    operation: String,
    sourcePlan: Option[ExecutionPlan]
  ): ExecutionPlan =
    planDoNothing(
      "DoNothingIfNotExists",
      label,
      name,
      valueMapper,
      QueryHandler
        .ignoreNoResult()
        .handleError(handleErrorFn(operation, label, name)),
      sourcePlan
    )

  def planDoNothingIfExists(
    label: String,
    name: Either[String, Parameter],
    valueMapper: String => String,
    sourcePlan: Option[ExecutionPlan]
  ): ExecutionPlan =
    planDoNothing(
      "DoNothingIfExists",
      label,
      name,
      valueMapper,
      QueryHandler
        .ignoreOnResult()
        .handleError(handleErrorFn("create", label, name)),
      sourcePlan
    )

  def planDoNothingIfDatabaseNotExists(
    name: DatabaseName,
    operation: String,
    sourcePlan: Option[ExecutionPlan],
    databaseTypeFilter: DatabaseTypeFilter
  ): ExecutionPlan = {
    planDoNothingDatabase(
      "DoNothingIfDatabaseNotExists",
      name,
      QueryHandler
        .ignoreNoResult()
        .handleError(handleErrorFn(operation, DATABASE, name)),
      sourcePlan,
      databaseTypeFilter
    )
  }

  def planDoNothingIfDatabaseExists(
    name: DatabaseName,
    sourcePlan: Option[ExecutionPlan],
    databaseTypeFilter: DatabaseTypeFilter
  ): ExecutionPlan =
    planDoNothingDatabase(
      "DoNothingIfDatabaseExists",
      name,
      QueryHandler
        .ignoreOnResult()
        .handleError(handleErrorFn("create", DATABASE, name)),
      sourcePlan,
      databaseTypeFilter
    )

  private def planDoNothing(
    planName: String,
    label: String,
    name: Either[String, Parameter],
    valueMapper: String => String,
    queryHandler: QueryHandler,
    sourcePlan: Option[ExecutionPlan]
  ): ExecutionPlan = {
    val nameFields = getNameFields("name", name, valueMapper = valueMapper)
    UpdatingSystemCommandExecutionPlan(
      planName,
      normalExecutionEngine,
      securityAuthorizationHandler,
      s"""
         |MATCH (node:$label {$NAME_PROPERTY: $$`${nameFields.nameKey}`})
         |RETURN node.$NAME_PROPERTY AS name
        """.stripMargin,
      VirtualValues.map(Array(nameFields.nameKey), Array(nameFields.nameValue)),
      queryHandler,
      sourcePlan,
      parameterConverter = nameFields.nameConverter
    )
  }

  private def planDoNothingDatabase(
    planName: String,
    name: DatabaseName,
    queryHandler: QueryHandler,
    sourcePlan: Option[ExecutionPlan],
    databaseTypeFilter: DatabaseTypeFilter
  ): ExecutionPlan = {
    val nameFields = getDatabaseNameFields("name", name)
    UpdatingSystemCommandExecutionPlan(
      planName,
      normalExecutionEngine,
      securityAuthorizationHandler,
      // Need to be backward compatible to 4.4 here because the upgrade to 5.0 uses CREATE DATABASE IF NOT EXISTS
      s"""
          CALL {
            MATCH (dn:$DATABASE_NAME ${nameFields.asNodeFilter}) RETURN dn
            UNION
            MATCH (dn:$DATABASE_NAME {$NAME_PROPERTY: $$`${nameFields.nameKey}`})
              WHERE dn.$NAMESPACE_PROPERTY IS NULL AND $$`${nameFields.namespaceKey}`='$DEFAULT_NAMESPACE' RETURN dn
          } WITH dn ${filterByDatabaseType(databaseTypeFilter)} RETURN dn.$NAME_PROPERTY
        """.stripMargin,
      VirtualValues.map(
        nameFields.keys,
        nameFields.values
      ),
      queryHandler,
      sourcePlan,
      parameterConverter = nameFields.nameConverter,
      parameterValidator = checkNamespaceExists(nameFields)
    )
  }

  private def handleErrorFn[T](
    operation: String,
    labelDescription: String,
    name: T
  )(implicit show: Show[T]): (Throwable, MapValue) => Throwable = {
    case (error: HasStatus, p) if error.status() == Status.Cluster.NotALeader =>
      new DatabaseAdministrationOnFollowerException(
        s"Failed to $operation the specified ${labelDescription.toLowerCase} '${show(name, p)}': $followerError",
        error
      )
    case (error, p) => new IllegalStateException(
        s"Failed to $operation the specified ${labelDescription.toLowerCase} '${show(name, p)}'.",
        error
      ) // should not get here but need a default case
  }

  private def filterByDatabaseType(databaseTypeFilter: DatabaseTypeFilter) = databaseTypeFilter match {
    case DatabaseTypeFilter.All                  => ""
    case DatabaseTypeFilter.DatabaseOrLocalAlias => s"""WHERE NOT dn:$REMOTE_DATABASE"""
    case DatabaseTypeFilter.CompositeDatabase    => s"""WHERE EXISTS { (dn)-[:$TARGETS]->(d:$COMPOSITE_DATABASE) }"""
    case DatabaseTypeFilter.StandardDatabase =>
      s"""WHERE EXISTS { (dn)-[:$TARGETS]->(d:$DATABASE) WHERE NOT d:$COMPOSITE_DATABASE }"""
    case DatabaseTypeFilter.Alias => s"""WHERE NOT dn.$PRIMARY_PROPERTY"""
  }
}

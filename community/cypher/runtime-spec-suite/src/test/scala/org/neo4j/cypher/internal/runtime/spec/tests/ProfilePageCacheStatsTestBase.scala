/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNode
import org.neo4j.cypher.internal.logical.plans.IndexOrderNone
import org.neo4j.cypher.internal.runtime.spec.Edition
import org.neo4j.cypher.internal.runtime.spec.LogicalQueryBuilder
import org.neo4j.cypher.internal.runtime.spec.RecordingRuntimeResult
import org.neo4j.cypher.internal.runtime.spec.RuntimeTestSuite
import org.neo4j.cypher.result.OperatorProfile

abstract class ProfilePageCacheStatsTestBase[CONTEXT <: RuntimeContext](canFuseOverPipelines: Boolean,
                                                                        edition: Edition[CONTEXT],
                                                                        runtime: CypherRuntime[CONTEXT]
                                                                       ) extends RuntimeTestSuite[CONTEXT](
  edition.copyWith(GraphDatabaseSettings.pagecache_memory -> "164480"), // 20 pages
  runtime) {

  // This needs to be big enough to trigger some page cache hits & misses
  protected val SIZE = 5000

  test("should profile page cache stats of linear plan") {
    given {
      nodePropertyGraph(SIZE, {
        case i => Map("prop" -> i)
      })
      () // This makes sure we don't reattach the nodes to the new transaction, since that would create additional page cache hits/misses
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("p")
      .projection("x.prop AS p")
      .filter("x.prop > 0")
      .allNodeScan("x")
      .build()

    val runtimeResult = profile(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    val expectedOperatorPageCacheStats: Map[Int, (Long, Long)] = if (canFuseOverPipelines) {
      Map(0 -> ((-1, -1)), // ProduceResults is part of a fused pipeline
          1 -> ((-1, -1)), // Projection is part of a fused pipeline
          2 -> ((-1, -1))) // Filer is part of a fused pipeline
    } else {
      Map(0 -> ((0, 0))) // Projection of a previous row should not access store
    }
    checkProfilerStatsMakeSense(runtimeResult, 4,
      expectedOperatorPageCacheStats
    )
  }

  test("should profile page cache stats of linear plan with breaks") {
    given {
      nodePropertyGraph(SIZE, {
        case i => Map("prop" -> i)
      })
      () // This makes sure we don't reattach the nodes to the new transaction, since that would create additional page cache hits/misses
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .aggregation(Seq("p AS p"), Seq("count(*) AS c"))
      .projection("x.prop AS p")
      .filter("x.prop > 0")
      .allNodeScan("x")
      .build()

    val runtimeResult = profile(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    val expectedOperatorPageCacheStats: Map[Int, (Long, Long)] = if (canFuseOverPipelines) {
      Map(1 -> ((-1, -1)), // Aggregation is part of a fused pipeline
          2 -> ((-1, -1)), // Projection is part of a fused pipeline
          3 -> ((-1, -1))  // Filter is part of a fused pipeline
      )
    } else {
      Map.empty
    }
    checkProfilerStatsMakeSense(runtimeResult, 5,
      expectedOperatorPageCacheStats
    )
  }

  test("should profile page cache stats of branched plan") {
    given {
      index("M", "prop")
      nodePropertyGraph(SIZE, {
        case i => Map("prop" -> i)
      }, "N", "M")
      () // This makes sure we don't reattach the nodes to the new transaction, since that would create additional page cache hits/misses
    }
    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("n") // Populates results, thus can have page cache hits & misses
      .filter("n.prop > 0")
      .nodeHashJoin("n")
      .|.apply()
      .|.|.aggregation(Seq("n AS n"), Seq("count(*) AS c"))
      .|.|.argument("n")
      .|.nodeIndexOperator("n:M(prop = 1)")
      .nodeByLabelScan("n", "N", IndexOrderNone)
      .build()

    val runtimeResult = profile(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    val expectedOperatorPageCacheStats: Map[Int, (Long, Long)] = if (canFuseOverPipelines) {
      Map(2 -> ((0, 0)), // A join should not access store
          3 -> ((0, 0)), // Apply does not do anything
          4 -> ((-1, -1)), // Aggregation is part of a fused pipeline
          5 -> ((0, 0)), // Argument does not do anything
      )
    } else {
      Map(2 -> ((0, 0)), // A join should not access store
          3 -> ((0, 0)), // Apply does not do anything
          4 -> ((0, 0)), // Aggregation should not access store
          5 -> ((0, 0)), // Argument does not do anything
      )
    }
    checkProfilerStatsMakeSense(runtimeResult, 8,
      expectedOperatorPageCacheStats
    )
  }

  test("should profile page cache stats of plan with apply over aggregation") {
    given {
      index("M", "prop")
      nodePropertyGraph(SIZE, {
        case i => Map("prop" -> i)
      }, "N", "M")
      () // This makes sure we don't reattach the nodes to the new transaction, since that would create additional page cache hits/misses
    }
    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("c")
      .apply()
      .|.aggregation(Seq.empty, Seq("count(*) AS c"))
      .|.expandAll("(a)-->(b)")
      .|.argument("a")
      .nodeByLabelScan("a", "A", IndexOrderNone)
      .build()

    val runtimeResult = profile(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    val expectedOperatorPageCacheStats: Map[Int, (Long, Long)] = if (canFuseOverPipelines) {
      Map(
        0 -> ((0, 0)), // Produce result should not access store
        1 -> ((0, 0)), // Apply does not do anything
        // TODO Shouldn't it be like this: 3 -> (-1, -1) // Expand all is part of a fused pipeline
      )
    } else {
      Map(
        0 -> ((0, 0)), // Produce result should not access store
        1 -> ((0, 0)) // Apply does not do anything
      )
    }
    checkProfilerStatsMakeSense(runtimeResult, 8,
      expectedOperatorPageCacheStats
    )
  }

  protected def checkProfilerStatsMakeSense(runtimeResult: RecordingRuntimeResult,
                                            numberOfOperators: Int,
                                            expectedOperatorPageCacheStats: Map[Int, (Long, Long)] = Map.empty): Unit = {
    val queryProfile = runtimeResult.runtimeResult.queryProfile()
    var accHits = 0L
    var accMisses = 0L
    for(i <- 0 until numberOfOperators) {
      val op = queryProfile.operatorProfile(i)
      val hits = op.pageCacheHits()
      val misses = op.pageCacheMisses()

      withClue(s"Incorrect page cache stats for operator $i.") {
        if (expectedOperatorPageCacheStats.contains(i)) {
          val (expectedHits, expectedMisses) = expectedOperatorPageCacheStats(i)
          hits should be(expectedHits)
          misses should be(expectedMisses)
        } else {
          hits should be >= 0L
          misses should be >= 0L
        }
        if (hits > 0) {
          accHits += hits
        }
        if (misses > 0) {
          accMisses += misses
        }
      }
    }

    val totalHits = runtimeResult.pageCacheHits
    val totalMisses = runtimeResult.pageCacheMisses

    accHits should be(totalHits)
    accMisses should be(totalMisses)
  }
}

trait UpdatingProfilePageCacheStatsTestBase [CONTEXT <: RuntimeContext] {
  self: ProfilePageCacheStatsTestBase[CONTEXT] =>

  test("should profile page cache stats of create with new label") {
    given {
      uniqueIndex("M", "prop")
      nodePropertyGraph(SIZE, {
        case i => Map("prop" -> i)
      }, "N", "M")
      () // This makes sure we don't reattach the nodes to the new transaction, since that would create additional page cache hits/misses
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("a") // Populates results, thus can have page cache hits & misses
      .create(createNode("a", "A"))
      .nodeIndexOperator("m:M(prop > 0)", argumentIds = Set("a"))
      .build()

    val runtimeResult: RecordingRuntimeResult = profile(logicalQuery, runtime)
    consume(runtimeResult)

    // then
    checkProfilerStatsMakeSense(runtimeResult, 3)
  }
}

#
# Copyright (c) 2002-2018 "Neo Technology,"
# Network Engine for Objects in Lund AB [http://neotechnology.com]
#
# This file is part of Neo4j.
#
# Neo4j is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program. If not, see <http://www.gnu.org/licenses/>.
#

Feature: AggregationAcceptance

  Background:
    Given an empty graph

  Scenario: Using a optional match after aggregation and before an aggregation
    And having executed:
      """
      CREATE (:Z{key:1})-[:IS_A]->(:A)
      """
    When executing query:
      """
      MATCH (a:A)
      WITH count(*) AS aCount
      OPTIONAL MATCH (z:Z)-[IS_A]->()
      RETURN aCount, count(distinct z.key) as zCount
      """
    Then the result should be:
      | aCount | zCount |
      | 1      | 1      |
    And no side effects

  Scenario: Multiple aggregations should work
    And having executed:
      """
      CREATE (zadie: AUTHOR {name: "Zadie Smith"})
      CREATE (zadie)-[:WROTE]->(:BOOK {book: "White teeth"})
      CREATE (zadie)-[:WROTE]->(:BOOK {book: "The Autograph Man"})
      CREATE (zadie)-[:WROTE]->(:BOOK {book: "On Beauty"})
      CREATE (zadie)-[:WROTE]->(:BOOK {book: "NW"})
      CREATE (zadie)-[:WROTE]->(:BOOK {book: "Swing Time"})
      """
    When executing query:
     """
     MATCH (a)-[r]->(b)
     RETURN b.book as book, count(r), count(distinct a)
     ORDER BY book
     """
    Then the result should be:
      | book                | count(r) | count(distinct a) |
      | 'NW'                | 1        | 1                 |
      | 'On Beauty'         | 1        | 1                 |
      | 'Swing Time'        | 1        | 1                 |
      | 'The Autograph Man' | 1        | 1                 |
      | 'White teeth'       | 1        | 1                 |
    And no side effects

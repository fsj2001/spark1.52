/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.expressions.Explode
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical.{Project, LocalRelation, Generate, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.types.StringType

class ColumnPruningSuite extends PlanTest {

  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches = Batch("Column pruning", FixedPoint(100),
      ColumnPruning) :: Nil
  }

  test("Column pruning for Generate when Generate.join = false") {
    val input = LocalRelation('a.int, 'b.array(StringType))

    val query = Generate(Explode('b), false, false, None, 's.string :: Nil, input).analyze
    val optimized = Optimize.execute(query)

    val correctAnswer =
      Generate(Explode('b), false, false, None, 's.string :: Nil,
        Project('b.attr :: Nil, input)).analyze

    comparePlans(optimized, correctAnswer)
  }
  //生成Generate.join = true时的列修剪
  test("Column pruning for Generate when Generate.join = true") {
    val input = LocalRelation('a.int, 'b.int, 'c.array(StringType))

    val query =
      Project(Seq('a, 's),
        Generate(Explode('c), true, false, None, 's.string :: Nil,
          input)).analyze
    val optimized = Optimize.execute(query)

    val correctAnswer =
      Project(Seq('a, 's),
        Generate(Explode('c), true, false, None, 's.string :: Nil,
          Project(Seq('a, 'c),
            input))).analyze

    comparePlans(optimized, correctAnswer)
  }
  //如果可能,将Generate.join转换为false
  test("Turn Generate.join to false if possible") {
    val input = LocalRelation('b.array(StringType))

    val query =
      Project(('s + 1).as("s+1") :: Nil,
        Generate(Explode('b), true, false, None, 's.string :: Nil,
          input)).analyze
    val optimized = Optimize.execute(query)

    val correctAnswer =
      Project(('s + 1).as("s+1") :: Nil,
        Generate(Explode('b), false, false, None, 's.string :: Nil,
          input)).analyze

    comparePlans(optimized, correctAnswer)
  }

  // todo: add more tests for column pruning
}

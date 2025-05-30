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
package org.apache.gluten.extension.columnar.enumerated.planner

import org.apache.gluten.config.GlutenConfig
import org.apache.gluten.extension.columnar.cost.{GlutenCost, GlutenCostModel, LegacyCoster, LongCostModel}
import org.apache.gluten.extension.columnar.enumerated.EnumeratedTransform
import org.apache.gluten.extension.columnar.enumerated.planner.property.Conv
import org.apache.gluten.extension.columnar.transition.{Convention, ConventionReq}
import org.apache.gluten.ras.Ras
import org.apache.gluten.ras.property.PropertySet
import org.apache.gluten.ras.rule.{RasRule, Shape, Shapes}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StringType

class VeloxRasSuite extends SharedSparkSession {
  import VeloxRasSuite._

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Convention.ensureSparkRowAndBatchTypesRegistered()
  }

  test("C2R, R2C - basic") {
    val in = RowUnary(RowLeaf(TRIVIAL_SCHEMA))
    val planner = newRas().newPlanner(in)
    val out = planner.plan()
    assert(out == RowUnary(RowLeaf(TRIVIAL_SCHEMA)))
  }

  test("C2R, R2C - explicitly requires any properties") {
    val in = RowUnary(RowLeaf(TRIVIAL_SCHEMA))
    val planner =
      newRas().newPlanner(in, PropertySet(List(Conv.any)))
    val out = planner.plan()
    assert(out == RowUnary(RowLeaf(TRIVIAL_SCHEMA)))
  }

  test("C2R, R2C - requires columnar output") {
    val in = RowUnary(RowLeaf(TRIVIAL_SCHEMA))
    val planner =
      newRas().newPlanner(in, PropertySet(List(Conv.req(ConventionReq.vanillaBatch))))
    val out = planner.plan()
    assert(out == RowToColumnarExec(RowUnary(RowLeaf(TRIVIAL_SCHEMA))))
  }

  test("C2R, R2C - insert c2rs / r2cs") {
    val in =
      ColumnarUnary(
        RowUnary(
          RowUnary(ColumnarUnary(RowUnary(RowUnary(ColumnarUnary(RowLeaf(TRIVIAL_SCHEMA))))))))
    val planner =
      newRas().newPlanner(in, PropertySet(List(Conv.req(ConventionReq.vanillaRow))))
    val out = planner.plan()
    assert(
      out == ColumnarToRowExec(
        ColumnarUnary(RowToColumnarExec(
          RowUnary(RowUnary(ColumnarToRowExec(ColumnarUnary(RowToColumnarExec(RowUnary(RowUnary(
            ColumnarToRowExec(ColumnarUnary(RowToColumnarExec(RowLeaf(TRIVIAL_SCHEMA)))))))))))))))
    val memoState = planner.newState().memoState()
    val numClusters = memoState.allClusters().size
    val numGroups = memoState.allGroups().size
    val numNodes = memoState.allClusters().flatMap(_.nodes()).size
    assert(numClusters == 8)
    assert(numGroups == 30)
    assert(numNodes == 39)
  }

  test("C2R, R2C - Row unary convertible to Columnar") {
    object ConvertRowUnaryToColumnar extends RasRule[SparkPlan] {
      override def shift(node: SparkPlan): Iterable[SparkPlan] = node match {
        case RowUnary(child) => List(ColumnarUnary(child))
        case other => List.empty
      }

      override def shape(): Shape[SparkPlan] = Shapes.fixedHeight(1)
    }

    val in =
      ColumnarUnary(
        RowUnary(
          RowUnary(ColumnarUnary(RowUnary(RowUnary(ColumnarUnary(RowLeaf(TRIVIAL_SCHEMA))))))))
    val planner =
      newRas(List(ConvertRowUnaryToColumnar))
        .newPlanner(in, PropertySet(List(Conv.req(ConventionReq.vanillaRow))))
    val out = planner.plan()
    assert(out == ColumnarToRowExec(ColumnarUnary(ColumnarUnary(ColumnarUnary(ColumnarUnary(
      ColumnarUnary(ColumnarUnary(ColumnarUnary(RowToColumnarExec(RowLeaf(TRIVIAL_SCHEMA)))))))))))
    val memoState = planner.newState().memoState()
    val numClusters = memoState.allClusters().size
    val numGroups = memoState.allGroups().size
    val numNodes = memoState.allClusters().flatMap(_.nodes()).size
    assert(numClusters == 8)
    assert(numGroups == 32)
    assert(numNodes == 55)
  }

  test("C2R, R2C - empty schema") {
    val in = RowUnary(RowLeaf(EMPTY_SCHEMA))

    val planner =
      newRas().newPlanner(in, PropertySet(List(Conv.any)))
    val out = planner.plan()
    assert(out == RowUnary(RowLeaf(EMPTY_SCHEMA)))

    val planner2 =
      newRas().newPlanner(in, PropertySet(List(Conv.req(ConventionReq.vanillaBatch))))
    val out2 = planner2.plan()
    assert(out2 == RowToColumnarExec(RowUnary(RowLeaf(EMPTY_SCHEMA))))
  }

  test("User cost model") {
    withSQLConf(GlutenConfig.RAS_COST_MODEL.key -> classOf[UserCostModel1].getName) {
      val in = RowUnary(RowLeaf(TRIVIAL_SCHEMA))
      val planner = newRas(List(RowUnaryToColumnarUnary)).newPlanner(in)
      val out = planner.plan()
      assert(out == ColumnarUnary(RowToColumnarExec(RowLeaf(TRIVIAL_SCHEMA))))
    }
    withSQLConf(GlutenConfig.RAS_COST_MODEL.key -> classOf[UserCostModel2].getName) {
      val in = RowUnary(RowLeaf(TRIVIAL_SCHEMA))
      val planner = newRas(List(RowUnaryToColumnarUnary)).newPlanner(in)
      val out = planner.plan()
      assert(out == RowUnary(RowLeaf(TRIVIAL_SCHEMA)))
    }
    withSQLConf(GlutenConfig.RAS_COST_MODEL.key -> "user.dummy.CostModel") {
      val in = RowUnary(RowLeaf(TRIVIAL_SCHEMA))
      assertThrows[ClassNotFoundException] {
        newRas().newPlanner(in)
      }
    }
  }
}

object VeloxRasSuite {
  def newRas(): Ras[SparkPlan] = {
    newRas(Nil)
  }

  def newRas(rasRules: Seq[RasRule[SparkPlan]]): Ras[SparkPlan] = {
    GlutenOptimization
      .builder()
      .costModel(EnumeratedTransform.asRasCostModel(sessionCostModel()))
      .addRules(rasRules)
      .create()
      .asInstanceOf[Ras[SparkPlan]]
  }

  private def legacyCostModel(): GlutenCostModel = {
    val registry = LongCostModel.registry()
    val coster = LegacyCoster
    registry.register(coster)
    registry.get(coster.kind())
  }

  private def sessionCostModel(): GlutenCostModel = {
    val transform = EnumeratedTransform.static()
    transform.costModel
  }

  val TRIVIAL_SCHEMA: Seq[AttributeReference] = List(AttributeReference("value", StringType)())

  val EMPTY_SCHEMA: Seq[AttributeReference] = List.empty

  case class RowLeaf(override val output: Seq[Attribute]) extends LeafExecNode {
    override def supportsColumnar: Boolean = false
    override protected def doExecute(): RDD[InternalRow] = throw new UnsupportedOperationException()
  }

  case class RowUnary(child: SparkPlan) extends UnaryExecNode {
    override def supportsColumnar: Boolean = false
    override protected def doExecute(): RDD[InternalRow] = throw new UnsupportedOperationException()
    override def output: Seq[Attribute] = child.output
    override protected def withNewChildInternal(newChild: SparkPlan): RowUnary =
      copy(child = newChild)
  }

  case class ColumnarUnary(child: SparkPlan) extends UnaryExecNode {
    override def supportsColumnar: Boolean = true
    override protected def doExecute(): RDD[InternalRow] = throw new UnsupportedOperationException()
    override def output: Seq[Attribute] = child.output
    override protected def withNewChildInternal(newChild: SparkPlan): ColumnarUnary =
      copy(child = newChild)
  }

  object RowUnaryToColumnarUnary extends RasRule[SparkPlan] {
    override def shift(node: SparkPlan): Iterable[SparkPlan] = node match {
      case RowUnary(child) => List(ColumnarUnary(child))
      case _ => List.empty
    }
    override def shape(): Shape[SparkPlan] = Shapes.fixedHeight(1)
  }

  class UserCostModel1 extends GlutenCostModel {
    private val base = legacyCostModel()
    override def costOf(node: SparkPlan): GlutenCost = node match {
      case _: RowUnary => base.makeInfCost()
      case other => base.costOf(other)
    }
    override def costComparator(): Ordering[GlutenCost] = base.costComparator()
    override def makeInfCost(): GlutenCost = base.makeInfCost()
    override def sum(one: GlutenCost, other: GlutenCost): GlutenCost = base.sum(one, other)
    override def diff(one: GlutenCost, other: GlutenCost): GlutenCost = base.diff(one, other)
    override def makeZeroCost(): GlutenCost = base.makeZeroCost()
  }

  class UserCostModel2 extends GlutenCostModel {
    private val base = legacyCostModel()
    override def costOf(node: SparkPlan): GlutenCost = node match {
      case _: ColumnarUnary => base.makeInfCost()
      case other => base.costOf(other)
    }
    override def costComparator(): Ordering[GlutenCost] = base.costComparator()
    override def makeInfCost(): GlutenCost = base.makeInfCost()
    override def sum(one: GlutenCost, other: GlutenCost): GlutenCost = base.sum(one, other)
    override def diff(one: GlutenCost, other: GlutenCost): GlutenCost = base.diff(one, other)
    override def makeZeroCost(): GlutenCost = base.makeZeroCost()
  }
}

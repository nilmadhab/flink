/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.runtime.stream.sql

import org.apache.flink.api.common.eventtime._
import org.apache.flink.core.testutils.EachCallbackWrapper
import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.api.config.ExecutionConfigOptions
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.runtime.utils._
import org.apache.flink.table.planner.runtime.utils.StreamingWithMiniBatchTestBase.{MiniBatchMode, MiniBatchOff, MiniBatchOn}
import org.apache.flink.table.planner.runtime.utils.StreamingWithStateTestBase.{HEAP_BACKEND, ROCKSDB_BACKEND, StateBackendMode}
import org.apache.flink.table.utils.LegacyRowExtension
import org.apache.flink.testutils.junit.extensions.parameterized.{ParameterizedTestExtension, Parameters}
import org.apache.flink.types.Row

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assumptions.assumeThat
import org.junit.jupiter.api.{BeforeEach, TestTemplate}
import org.junit.jupiter.api.extension.{ExtendWith, RegisterExtension}

import java.util

import scala.collection.JavaConversions._
import scala.collection.mutable

@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class DeduplicateITCase(miniBatch: MiniBatchMode, mode: StateBackendMode, enableAsyncState: Boolean)
  extends StreamingWithMiniBatchTestBase(miniBatch, mode) {

  @RegisterExtension private val _: EachCallbackWrapper[LegacyRowExtension] =
    new EachCallbackWrapper[LegacyRowExtension](new LegacyRowExtension)

  lazy val rowtimeTestData = new mutable.MutableList[(Int, Long, String)]
  rowtimeTestData.+=((1, 1L, "Hi"))
  rowtimeTestData.+=((1, 3L, "Hello"))
  rowtimeTestData.+=((1, 2L, "Hello world"))
  rowtimeTestData.+=((2, 3L, "I am fine."))
  rowtimeTestData.+=((2, 6L, "Comment#1"))
  rowtimeTestData.+=((3, 5L, "Comment#2"))
  rowtimeTestData.+=((3, 4L, "Comment#2"))
  rowtimeTestData.+=((4, 4L, "Comment#3"))

  @BeforeEach
  override def before(): Unit = {
    super.before()
    tEnv.getConfig.set(
      ExecutionConfigOptions.TABLE_EXEC_ASYNC_STATE_ENABLED,
      Boolean.box(enableAsyncState))
  }

  @TestTemplate
  def testFirstRowOnProctime(): Unit = {
    val t = failingDataSource(TestData.tupleData3)
      .toTable(tEnv, 'a, 'b, 'c, 'proctime.proctime)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT a, b, c
        |FROM (
        |  SELECT *,
        |    ROW_NUMBER() OVER (PARTITION BY b ORDER BY proctime) as rowNum
        |  FROM T
        |)
        |WHERE rowNum = 1
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "1,1,Hi",
      "2,2,Hello",
      "4,3,Hello world, how are you?",
      "7,4,Comment#1",
      "11,5,Comment#5",
      "16,6,Comment#10")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testFirstRowOnBuiltinProctime(): Unit = {
    val t = failingDataSource(TestData.tupleData3).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT a, b, c
        |FROM (
        |  SELECT *,
        |    ROW_NUMBER() OVER (PARTITION BY b ORDER BY proctime()) as rowNum
        |  FROM T
        |)
        |WHERE rowNum = 1
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "1,1,Hi",
      "2,2,Hello",
      "4,3,Hello world, how are you?",
      "7,4,Comment#1",
      "11,5,Comment#5",
      "16,6,Comment#10")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLastRowOnProctime(): Unit = {
    val t = failingDataSource(TestData.tupleData3)
      .toTable(tEnv, 'a, 'b, 'c, 'proctime.proctime)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT a, b, c
        |FROM (
        |  SELECT *,
        |    ROW_NUMBER() OVER (PARTITION BY b ORDER BY proctime DESC) as rowNum
        |  FROM T
        |)
        |WHERE rowNum = 1
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "1,1,Hi",
      "3,2,Hello world",
      "6,3,Luke Skywalker",
      "10,4,Comment#4",
      "15,5,Comment#9",
      "21,6,Comment#15")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLastRowOnBuiltinProctime(): Unit = {
    val t = failingDataSource(TestData.tupleData3).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT a, b, c
        |FROM (
        |  SELECT *,
        |    ROW_NUMBER() OVER (PARTITION BY b ORDER BY proctime() DESC) as rowNum
        |  FROM T
        |)
        |WHERE rowNum = 1
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "1,1,Hi",
      "3,2,Hello world",
      "6,3,Luke Skywalker",
      "10,4,Comment#4",
      "15,5,Comment#9",
      "21,6,Comment#15")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testFirstRowOnRowtime(): Unit = {
    val t = StreamingEnvUtil
      .fromCollection(env, rowtimeTestData)
      .assignTimestampsAndWatermarks(new RowtimeExtractor)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime())
    tEnv.createTemporaryView("T", t)
    createSinkTable("rowtime_sink")

    val sql =
      """
        |INSERT INTO rowtime_sink
        | SELECT a, b, c, rowtime
        | FROM (
        |   SELECT *,
        |     ROW_NUMBER() OVER (PARTITION BY a ORDER BY rowtime) as rowNum
        |   FROM T
        | )
        | WHERE rowNum = 1
      """.stripMargin

    tEnv.executeSql(sql).await()
    val rawResult = TestValuesTableFactory.getRawResultsAsStrings("rowtime_sink")

    val expected = if (miniBatch.equals(MiniBatchOff)) {
      List(
        "+I(1,1,Hi,1970-01-01T00:00:00.001)",
        "+I(2,3,I am fine.,1970-01-01T00:00:00.003)",
        "+I(3,4,Comment#2,1970-01-01T00:00:00.004)",
        "+I(4,4,Comment#3,1970-01-01T00:00:00.004)"
      )
    } else {
      List(
        "+I(1,1,Hi,1970-01-01T00:00:00.001)",
        "+I(2,3,I am fine.,1970-01-01T00:00:00.003)",
        "+I(3,5,Comment#2,1970-01-01T00:00:00.005)",
        "-U(3,5,Comment#2,1970-01-01T00:00:00.005)",
        "+U(3,4,Comment#2,1970-01-01T00:00:00.004)",
        "+I(4,4,Comment#3,1970-01-01T00:00:00.004)"
      )
    }

    assertThat(rawResult.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testFirstRowWithoutAllChangelogOnRowtime(): Unit = {
    assumeThat(miniBatch == MiniBatchOn).isTrue
    tEnv.getConfig.set(
      ExecutionConfigOptions.TABLE_EXEC_DEDUPLICATE_MINIBATCH_COMPACT_CHANGES_ENABLED,
      Boolean.box(true))
    val t = StreamingEnvUtil
      .fromCollection(env, rowtimeTestData)
      .assignTimestampsAndWatermarks(new RowtimeExtractor)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime())
    tEnv.createTemporaryView("T", t)
    createSinkTable("rowtime_sink")

    val sql =
      """
        |INSERT INTO rowtime_sink
        | SELECT a, b, c, rowtime
        | FROM (
        |   SELECT *,
        |     ROW_NUMBER() OVER (PARTITION BY a ORDER BY rowtime) as rowNum
        |   FROM T
        | )
        | WHERE rowNum = 1
      """.stripMargin

    tEnv.executeSql(sql).await()
    val rawResult = TestValuesTableFactory.getRawResultsAsStrings("rowtime_sink")

    val expected = List(
      "+I(1,1,Hi,1970-01-01T00:00:00.001)",
      "+I(2,3,I am fine.,1970-01-01T00:00:00.003)",
      "+I(3,4,Comment#2,1970-01-01T00:00:00.004)",
      "+I(4,4,Comment#3,1970-01-01T00:00:00.004)"
    )
    assertThat(rawResult.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testFirstRowOnRowTimeFollowedByUnboundedAgg(): Unit = {
    val t = StreamingEnvUtil
      .fromCollection(env, rowtimeTestData)
      .assignTimestampsAndWatermarks(new RowtimeExtractor)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime())
    tEnv.createTemporaryView("T", t)
    tEnv.executeSql(s"""
                       |CREATE TABLE rowtime_sink (
                       |    cnt BIGINT
                       |) WITH (
                       |  'connector' = 'values',
                       |  'sink-insert-only' = 'false',
                       |  'changelog-mode' = 'I,UA,D'
                       |)
                       |""".stripMargin)
    val sql =
      """
        |INSERT INTO rowtime_sink
        |SELECT COUNT(b) FROM (
        | SELECT a, b, c, rowtime
        | FROM (
        |   SELECT *,
        |     ROW_NUMBER() OVER (PARTITION BY b ORDER BY rowtime) as rowNum
        |   FROM T
        | )
        | WHERE rowNum = 1
        | )
    """.stripMargin

    tEnv.executeSql(sql).await()
    val rawResult = TestValuesTableFactory.getResultsAsStrings("rowtime_sink")
    val expected = List("6")
    assertThat(rawResult.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLastRowOnRowtime(): Unit = {
    val t = StreamingEnvUtil
      .fromCollection(env, rowtimeTestData)
      .assignTimestampsAndWatermarks(new RowtimeExtractor)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime())
    tEnv.createTemporaryView("T", t)
    createSinkTable("rowtime_sink")

    val sql =
      """
        |INSERT INTO rowtime_sink
        | SELECT a, b, c, rowtime
        | FROM (
        |   SELECT *,
        |     ROW_NUMBER() OVER (PARTITION BY b ORDER BY rowtime DESC) as rowNum
        |   FROM T
        | )
        | WHERE rowNum = 1
      """.stripMargin

    tEnv.executeSql(sql).await()
    val rawResult = TestValuesTableFactory.getRawResultsAsStrings("rowtime_sink")

    val expected = List(
      "+I(1,1,Hi,1970-01-01T00:00:00.001)",
      "+I(1,3,Hello,1970-01-01T00:00:00.003)",
      "+I(1,2,Hello world,1970-01-01T00:00:00.002)",
      "-U(1,3,Hello,1970-01-01T00:00:00.003)",
      "+U(2,3,I am fine.,1970-01-01T00:00:00.003)",
      "+I(2,6,Comment#1,1970-01-01T00:00:00.006)",
      "+I(3,5,Comment#2,1970-01-01T00:00:00.005)",
      "+I(3,4,Comment#2,1970-01-01T00:00:00.004)",
      "-U(3,4,Comment#2,1970-01-01T00:00:00.004)",
      "+U(4,4,Comment#3,1970-01-01T00:00:00.004)"
    )
    assertThat(rawResult.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLastRowWithoutAllChangelogOnRowtime(): Unit = {
    assumeThat(miniBatch == MiniBatchOn).isTrue
    tEnv.getConfig.set(
      ExecutionConfigOptions.TABLE_EXEC_DEDUPLICATE_MINIBATCH_COMPACT_CHANGES_ENABLED,
      Boolean.box(true))
    val t = StreamingEnvUtil
      .fromCollection(env, rowtimeTestData)
      .assignTimestampsAndWatermarks(new RowtimeExtractor)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime())
    tEnv.createTemporaryView("T", t)
    createSinkTable("rowtime_sink")

    val sql =
      """
        |INSERT INTO rowtime_sink
        | SELECT a, b, c, rowtime
        | FROM (
        |   SELECT *,
        |     ROW_NUMBER() OVER (PARTITION BY b ORDER BY rowtime DESC) as rowNum
        |   FROM T
        | )
        | WHERE rowNum = 1
      """.stripMargin

    tEnv.executeSql(sql).await()
    val rawResult = TestValuesTableFactory.getRawResultsAsStrings("rowtime_sink")

    val expected = List(
      "+I(1,1,Hi,1970-01-01T00:00:00.001)",
      "+I(1,2,Hello world,1970-01-01T00:00:00.002)",
      "+I(2,3,I am fine.,1970-01-01T00:00:00.003)",
      "+I(2,6,Comment#1,1970-01-01T00:00:00.006)",
      "+I(3,5,Comment#2,1970-01-01T00:00:00.005)",
      "+I(4,4,Comment#3,1970-01-01T00:00:00.004)"
    )
    assertThat(rawResult.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLastRowOnRowTimeFollowedByUnboundedAgg(): Unit = {
    val t = StreamingEnvUtil
      .fromCollection(env, rowtimeTestData)
      .assignTimestampsAndWatermarks(new RowtimeExtractor)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime())
    tEnv.createTemporaryView("T", t)
    tEnv.executeSql(s"""
                       |CREATE TABLE rowtime_sink (
                       |    cnt BIGINT
                       |) WITH (
                       |  'connector' = 'values',
                       |  'sink-insert-only' = 'false',
                       |  'changelog-mode' = 'I,UA,D'
                       |)
                       |""".stripMargin)
    val sql =
      """
        |INSERT INTO rowtime_sink
        |SELECT COUNT(b) FROM (
        | SELECT a, b, c, rowtime
        | FROM (
        |   SELECT *,
        |     ROW_NUMBER() OVER (PARTITION BY b ORDER BY rowtime DESC) as rowNum
        |   FROM T
        | )
        | WHERE rowNum = 1
        | )
    """.stripMargin

    tEnv.executeSql(sql).await()
    val rawResult = TestValuesTableFactory.getResultsAsStrings("rowtime_sink")
    val expected = List("6")
    assertThat(rawResult.sorted).isEqualTo(expected.sorted)
  }

  def createSinkTable(tableName: String): Unit = {
    tEnv.executeSql(s"""
                       |CREATE TABLE $tableName (
                       |    a INT,
                       |    b BIGINT,
                       |    c STRING,
                       |    rowtime TIMESTAMP(3)
                       |) WITH (
                       |  'connector' = 'values',
                       |  'sink-insert-only' = 'false',
                       |  'changelog-mode' = 'I,UA,D'
                       |)
                       |""".stripMargin)
  }
}

class RowtimeExtractor extends WatermarkStrategy[(Int, Long, String)] {
  override def createWatermarkGenerator(
      context: WatermarkGeneratorSupplier.Context): WatermarkGenerator[(Int, Long, String)] =
    new AscendingTimestampsWatermarks[(Int, Long, String)]

  override def createTimestampAssigner(
      context: TimestampAssignerSupplier.Context): TimestampAssigner[(Int, Long, String)] = {
    (e: (Int, Long, String), _: Long) => e._2
  }
}

object DeduplicateITCase {

  @Parameters(name = "{0}, StateBackend={1}, EnableAsyncState={2}")
  def parameters(): util.Collection[Array[java.lang.Object]] = {
    Seq[Array[AnyRef]](
      Array(MiniBatchOff, HEAP_BACKEND, Boolean.box(false)),
      Array(MiniBatchOff, ROCKSDB_BACKEND, Boolean.box(false)),
      Array(MiniBatchOn, HEAP_BACKEND, Boolean.box(false)),
      Array(MiniBatchOn, ROCKSDB_BACKEND, Boolean.box(false)),
      Array(MiniBatchOff, HEAP_BACKEND, Boolean.box(true))
    )
  }
}

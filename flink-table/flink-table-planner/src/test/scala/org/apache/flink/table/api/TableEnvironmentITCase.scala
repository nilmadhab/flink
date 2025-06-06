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
package org.apache.flink.table.api

import org.apache.flink.api.common.typeinfo.Types.STRING
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment
import org.apache.flink.table.api.bridge.scala.{dataStreamConversions, StreamTableEnvironment => ScalaStreamTableEnvironment}
import org.apache.flink.table.api.config.TableConfigOptions
import org.apache.flink.table.api.internal.TableEnvironmentImpl
import org.apache.flink.table.catalog._
import org.apache.flink.table.legacy.api.TableSchema
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.factories.utils.TestCollectionTableFactory
import org.apache.flink.table.planner.runtime.utils.{StreamingEnvUtil, TestingAppendSink}
import org.apache.flink.table.planner.runtime.utils.{TestingAppendSink, TestSinkUtil}
import org.apache.flink.table.planner.runtime.utils.BatchTestBase.{row => buildRow}
import org.apache.flink.table.planner.utils.{TableTestUtil, TestTableSourceSinks}
import org.apache.flink.table.planner.utils.TableTestUtil.{readFromResource, replaceStageId}
import org.apache.flink.testutils.junit.extensions.parameterized.{ParameterizedTestExtension, Parameters}
import org.apache.flink.testutils.junit.utils.TempDirUtils
import org.apache.flink.types.{Row, RowKind}
import org.apache.flink.util.CollectionUtil

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.{BeforeEach, TestTemplate}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir

import java.io.File
import java.lang.{Long => JLong}
import java.nio.file.Path
import java.util

import scala.collection.mutable

@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class TableEnvironmentITCase(tableEnvName: String, isStreaming: Boolean) {

  @TempDir
  var tempFolder: Path = _

  var tEnv: TableEnvironment = _

  private val settings = if (isStreaming) {
    EnvironmentSettings.newInstance().inStreamingMode().build()
  } else {
    EnvironmentSettings.newInstance().inBatchMode().build()
  }

  @BeforeEach
  def setup(): Unit = {
    tableEnvName match {
      case "TableEnvironment" =>
        tEnv = TableEnvironmentImpl.create(settings)
      case "StreamTableEnvironment" =>
        tEnv = StreamTableEnvironment.create(
          StreamExecutionEnvironment.getExecutionEnvironment,
          settings)
      case _ => throw new UnsupportedOperationException("unsupported tableEnvName: " + tableEnvName)
    }
    TestTableSourceSinks.createPersonCsvTemporaryTable(tEnv, "MyTable")
  }

  @TestTemplate
  def testExecuteTwiceUsingSameTableEnv(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink2")

    checkEmptyDir(sink1Path)
    checkEmptyDir(sink2Path)

    val table1 = tEnv.sqlQuery("select first from MyTable")
    table1.executeInsert("MySink1").await()
    assertFirstValues("MySink1")
    checkEmptyDir(sink2Path)

    // delete first csv file
    deleteDir(sink1Path)
    assertFalse(new File(sink1Path).exists())

    val table2 = tEnv.sqlQuery("select last from MyTable")
    table2.executeInsert("MySink2").await()
    assertFalse(new File(sink1Path).exists())
    assertLastValues("MySink2")
  }

  @TestTemplate
  def testExplainAndExecuteSingleSink(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val table1 = tEnv.sqlQuery("select first from MyTable")
    table1.executeInsert("MySink1").await()
    assertFirstValues("MySink1")
  }

  @TestTemplate
  def testExecuteSqlWithInsertInto(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyDir(sinkPath)
    val tableResult = tEnv.executeSql("insert into MySink1 select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink1")
    assertFirstValues("MySink1")
  }

  @TestTemplate
  def testExecuteSqlWithInsertOverwrite(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }

    val sinkPath = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sinkPath',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val tableResult1 = tEnv.executeSql("insert overwrite MySink select first from MyTable")
    checkInsertTableResult(tableResult1, "default_catalog.default_database.MySink")
    assertFirstValues("MySink")

    val tableResult2 = tEnv.executeSql("insert overwrite MySink select first from MyTable")
    checkInsertTableResult(tableResult2, "default_catalog.default_database.MySink")
    assertFirstValues("MySink")
  }

  @TestTemplate
  def testExecuteSqlAndExecuteInsert(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("last"), Array(STRING)),
      "MySink2")
    checkEmptyDir(sink1Path)
    checkEmptyDir(sink2Path)

    val tableResult = tEnv.executeSql("insert into MySink1 select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink1")

    assertFirstValues("MySink1")
    checkEmptyDir(sink2Path)

    // delete first csv file
    deleteDir(sink1Path)
    assertFalse(new File(sink1Path).exists())

    tEnv.sqlQuery("select last from MyTable").executeInsert("MySink2").await()
    assertFalse(new File(sink1Path).exists())
    assertLastValues("MySink2")
  }

  @TestTemplate
  def testExecuteSqlAndToDataStream(): Unit = {
    if (!tableEnvName.equals("StreamTableEnvironment")) {
      return
    }
    val streamEnv = StreamExecutionEnvironment.getExecutionEnvironment
    val streamTableEnv = StreamTableEnvironment.create(streamEnv, settings)
    TestTableSourceSinks.createPersonCsvTemporaryTable(streamTableEnv, "MyTable")
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      streamTableEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyDir(sink1Path)

    val table = streamTableEnv.sqlQuery("select last from MyTable where id > 0")
    val resultSet = streamTableEnv.toDataStream(table)
    val sink = new TestingAppendSink
    resultSet.addSink(sink)

    val tableResult = streamTableEnv.executeSql("insert into MySink1 select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink1")
    assertFirstValues(streamTableEnv, "MySink1")

    // the DataStream program is not executed
    assertFalse(sink.isInitialized)

    deleteDir(sink1Path)

    streamEnv.execute("test2")
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)
    // the table program is not executed again
    checkEmptyDir(sink1Path)
  }

  @TestTemplate
  def testToDataStreamAndExecuteSql(): Unit = {
    if (!tableEnvName.equals("StreamTableEnvironment")) {
      return
    }
    val streamEnv = StreamExecutionEnvironment.getExecutionEnvironment
    val streamTableEnv = StreamTableEnvironment.create(streamEnv, settings)
    TestTableSourceSinks.createPersonCsvTemporaryTable(streamTableEnv, "MyTable")
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      streamTableEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyDir(sink1Path)

    val table = streamTableEnv.sqlQuery("select last from MyTable where id > 0")
    val resultSet = streamTableEnv.toDataStream(table)
    val sink = new TestingAppendSink
    resultSet.addSink(sink)

    val insertStmt = "insert into MySink1 select first from MyTable"

    val explain = streamTableEnv.explainSql(insertStmt)
    assertEquals(
      replaceStageId(readFromResource("/explain/testSqlUpdateAndToDataStream.out")),
      replaceStageId(explain))

    assertEquals(
      replaceStageId(readFromResource("/explain/testSqlUpdateAndToDataStreamWithPlanAdvice.out")),
      replaceStageId(streamTableEnv.explainSql(insertStmt, ExplainDetail.PLAN_ADVICE))
    )

    streamEnv.execute("test2")
    // the table program is not executed
    checkEmptyDir(sink1Path)
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)

    streamTableEnv.executeSql(insertStmt).await()
    assertFirstValues(streamTableEnv, "MySink1")
    // the DataStream program is not executed again because the result in sink is not changed
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)
  }

  @TestTemplate
  def testFromToDataStreamAndExecuteSql(): Unit = {
    if (!tableEnvName.equals("StreamTableEnvironment")) {
      return
    }
    val streamEnv = StreamExecutionEnvironment.getExecutionEnvironment
    val streamTableEnv = ScalaStreamTableEnvironment.create(streamEnv, settings)
    val t = StreamingEnvUtil
      .fromCollection(streamEnv, getPersonData)
      .toTable(streamTableEnv, 'first, 'id, 'score, 'last)
    streamTableEnv.createTemporaryView("MyTable", t)
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      streamTableEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")
    checkEmptyDir(sink1Path)

    val table = streamTableEnv.sqlQuery("select last from MyTable where id > 0")
    val resultSet = streamTableEnv.toDataStream(table)
    val sink = new TestingAppendSink
    resultSet.addSink(sink)

    val insertStmt = "insert into MySink1 select first from MyTable"

    val explain = streamTableEnv.explainSql(insertStmt)
    assertEquals(
      replaceStageId(readFromResource("/explain/testFromToDataStreamAndSqlUpdate.out")),
      replaceStageId(explain))

    assertEquals(
      replaceStageId(
        readFromResource("/explain/testFromToDataStreamAndSqlUpdateWithPlanAdvice.out")),
      replaceStageId(streamTableEnv.explainSql(insertStmt, ExplainDetail.PLAN_ADVICE))
    )

    streamEnv.execute("test2")
    // the table program is not executed
    checkEmptyDir(sink1Path)
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)

    streamTableEnv.executeSql(insertStmt).await()
    assertFirstValues(streamTableEnv, "MySink1")
    // the DataStream program is not executed again because the result in sink is not changed
    assertEquals(getExpectedLastValues.sorted, sink.getAppendResults.sorted)
  }

  @TestTemplate
  def testExecuteInsert(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink")
    checkEmptyDir(sinkPath)
    val table = tEnv.sqlQuery("select first from MyTable")
    val tableResult = table.executeInsert("MySink")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink")
    assertFirstValues("MySink")
  }

  @TestTemplate
  def testExecuteInsert2(): Unit = {
    val sinkPath = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink")
    checkEmptyDir(sinkPath)
    val tableResult = tEnv.executeSql("execute insert into MySink select first from MyTable")
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink")
    assertFirstValues("MySink")
  }

  @TestTemplate
  def testExecuteInsertOverwrite(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }
    val sinkPath = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sinkPath',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )
    val tableResult1 = tEnv.sqlQuery("select first from MyTable").executeInsert("MySink", true)
    checkInsertTableResult(tableResult1, "default_catalog.default_database.MySink")
    assertFirstValues("MySink")

    val tableResult2 = tEnv.sqlQuery("select first from MyTable").executeInsert("MySink", true)
    checkInsertTableResult(tableResult2, "default_catalog.default_database.MySink")
    assertFirstValues("MySink")
  }

  @TestTemplate
  def testTableDMLSync(): Unit = {
    tEnv.getConfig.set(TableConfigOptions.TABLE_DML_SYNC, Boolean.box(true))
    val sink1Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink1 (
         |  first string,
         |  last string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink1Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val sink2Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink2 (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink2Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val sink3Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink3 (
         |  last string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink3Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val tableResult1 =
      tEnv.sqlQuery("select first, last from MyTable").executeInsert("MySink1", false)

    val stmtSet = tEnv.createStatementSet()
    stmtSet.addInsertSql("INSERT INTO MySink2 select first from MySink1")
    stmtSet.addInsertSql("INSERT INTO MySink3 select last from MySink1")
    val tableResult2 = stmtSet.execute()

    // checkInsertTableResult will wait the job finished,
    // we should assert file values first to verify job has been finished
    assertFirstValues("MySink2")
    assertLastValues("MySink3")

    // check TableResult after verifying file values
    checkInsertTableResult(
      tableResult2,
      "default_catalog.default_database.MySink2",
      "default_catalog.default_database.MySink3")

    // Verify it's no problem to invoke await twice
    tableResult1.await()
    tableResult2.await()
  }

  @TestTemplate
  def testStatementSet(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("last"), Array(STRING)),
      "MySink2")

    val stmtSet = tEnv.createStatementSet()
    stmtSet
      .addInsert("MySink1", tEnv.sqlQuery("select first from MyTable"))
      .addInsertSql("insert into MySink2 select last from MyTable")

    val actual = stmtSet.explain()
    val expected = TableTestUtil.readFromResource("/explain/testStatementSet.out")
    assertEquals(replaceStageId(expected), replaceStageId(actual))

    if (isStreaming) {
      assertEquals(
        replaceStageId(
          TableTestUtil.readFromResource("/explain/testStatementSetWithPlanAdvice.out")),
        replaceStageId(stmtSet.explain(ExplainDetail.PLAN_ADVICE))
      )
    } else {
      assertThatThrownBy(() => stmtSet.explain(ExplainDetail.PLAN_ADVICE))
        .hasMessageContaining("EXPLAIN PLAN_ADVICE is not supported under batch mode.")
        .isInstanceOf[UnsupportedOperationException]

    }

    val tableResult = stmtSet.execute()
    checkInsertTableResult(
      tableResult,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues("MySink1")
    assertLastValues("MySink2")
  }

  @TestTemplate
  def testExecuteStatementSet(): Unit = {
    val sink1Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("first"), Array(STRING)),
      "MySink1")

    val sink2Path = TestTableSourceSinks.createCsvTemporarySinkTable(
      tEnv,
      new TableSchema(Array("last"), Array(STRING)),
      "MySink2")

    val tableResult = tEnv.executeSql("""execute statement set begin
                                        |insert into MySink1 select first from MyTable;
                                        |insert into MySink2 select last from MyTable;
                                        |end""".stripMargin)
    checkInsertTableResult(
      tableResult,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues("MySink1")
    assertLastValues("MySink2")
  }

  @TestTemplate
  def testStatementSetWithOverwrite(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }
    val sink1Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink1 (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink1Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val sink2Path = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink2 (
         |  last string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sink2Path',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val stmtSet = tEnv.createStatementSet()
    stmtSet.addInsert("MySink1", tEnv.sqlQuery("select first from MyTable"), true)
    stmtSet.addInsertSql("insert overwrite MySink2 select last from MyTable")

    val tableResult1 = stmtSet.execute()
    checkInsertTableResult(
      tableResult1,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues("MySink1")
    assertLastValues("MySink2")

    // execute again using same StatementSet instance
    stmtSet
      .addInsert("MySink1", tEnv.sqlQuery("select first from MyTable"), true)
      .addInsertSql("insert overwrite MySink2 select last from MyTable")

    val tableResult2 = stmtSet.execute()
    checkInsertTableResult(
      tableResult2,
      "default_catalog.default_database.MySink1",
      "default_catalog.default_database.MySink2")

    assertFirstValues("MySink1")
    assertLastValues("MySink2")
  }

  @TestTemplate
  def testStatementSetWithSameSinkTableNames(): Unit = {
    if (isStreaming) {
      // Streaming mode not support overwrite for FileSystemTableSink.
      return
    }
    val sinkPath = TempDirUtils.newFolder(tempFolder).toString
    tEnv.executeSql(
      s"""
         |create table MySink (
         |  first string
         |) with (
         |  'connector' = 'filesystem',
         |  'path' = '$sinkPath',
         |  'format' = 'testcsv'
         |)
       """.stripMargin
    )

    val stmtSet = tEnv.createStatementSet()
    stmtSet.addInsert("MySink", tEnv.sqlQuery("select first from MyTable"), true)
    stmtSet.addInsertSql("insert overwrite MySink select last from MyTable")

    val tableResult = stmtSet.execute()
    // only check the schema
    checkInsertTableResult(tableResult, "default_catalog.default_database.MySink_1")
  }

  @TestTemplate
  def testExecuteSelect(): Unit = {
    val query = {
      """
        |select id, concat(concat(`first`, ' '), `last`) as `full name`
        |from MyTable where mod(id, 2) = 0
      """.stripMargin
    }
    testExecuteSelectInternal(query)
    val query2 = {
      """
        |execute select id, concat(concat(`first`, ' '), `last`) as `full name`
        |from MyTable where mod(id, 2) = 0
      """.stripMargin
    }
    testExecuteSelectInternal(query2)
  }

  def testExecuteSelectInternal(query: String): Unit = {
    val tableResult = tEnv.executeSql(query)
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(
      ResolvedSchema.of(
        Column.physical("id", DataTypes.INT()),
        Column.physical("full name", DataTypes.STRING())),
      tableResult.getResolvedSchema)
    val expected = util.Arrays.asList(
      Row.of(Integer.valueOf(2), "Bob Taylor"),
      Row.of(Integer.valueOf(4), "Peter Smith"),
      Row.of(Integer.valueOf(6), "Sally Miller"),
      Row.of(Integer.valueOf(8), "Kelly Williams")
    )
    val actual = CollectionUtil.iteratorToList(tableResult.collect())
    actual.sort(new util.Comparator[Row]() {
      override def compare(o1: Row, o2: Row): Int = {
        o1.getField(0).asInstanceOf[Int].compareTo(o2.getField(0).asInstanceOf[Int])
      }
    })
    assertEquals(expected, actual)
  }

  @TestTemplate
  def testExecuteSelectWithUpdateChanges(): Unit = {
    val tableResult = tEnv.sqlQuery("select count(*) as c from MyTable").execute()
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(
      ResolvedSchema.of(Column.physical("c", DataTypes.BIGINT().notNull())),
      tableResult.getResolvedSchema)
    val expected = if (isStreaming) {
      util.Arrays.asList(
        Row.ofKind(RowKind.INSERT, JLong.valueOf(1)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(1)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(2)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(2)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(3)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(3)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(4)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(4)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(5)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(5)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(6)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(6)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(7)),
        Row.ofKind(RowKind.UPDATE_BEFORE, JLong.valueOf(7)),
        Row.ofKind(RowKind.UPDATE_AFTER, JLong.valueOf(8))
      )
    } else {
      util.Arrays.asList(Row.of(JLong.valueOf(8)))
    }
    val actual = CollectionUtil.iteratorToList(tableResult.collect())
    assertEquals(expected, actual)
  }

  @TestTemplate
  def testExecuteSelectWithTimeAttribute(): Unit = {
    val dataId = TestValuesTableFactory.registerData(Seq(buildRow("Mary")))
    tEnv.executeSql(s"""
                       |create table T (
                       |  name string,
                       |  pt as proctime()
                       |) with (
                       |  'connector' = 'values',
                       |  'bounded' = 'true',
                       |  'data-id' = '$dataId'
                       |)
                       |""".stripMargin)

    val tableResult = tEnv.executeSql("select * from T")
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(
      ResolvedSchema.of(
        Column.physical("name", DataTypes.STRING()),
        Column.physical("pt", DataTypes.TIMESTAMP_LTZ(3).notNull())),
      tableResult.getResolvedSchema
    )
    val it = tableResult.collect()
    assertTrue(it.hasNext)
    val row = it.next()
    assertEquals(2, row.getArity)
    assertEquals("Mary", row.getField(0))
    assertFalse(it.hasNext)
  }

  @TestTemplate
  def testClearOperation(): Unit = {
    TestCollectionTableFactory.reset()
    val tableEnv = TableEnvironmentImpl.create(settings)
    tableEnv.executeSql("create table dest1(x map<int,bigint>) with('connector' = 'COLLECTION')")
    tableEnv.executeSql("create table dest2(x int) with('connector' = 'COLLECTION')")
    tableEnv.executeSql("create table src(x int) with('connector' = 'COLLECTION')")

    try {
      // it would fail due to query and sink type mismatch
      tableEnv.executeSql("insert into dest1 select count(*) from src")
      fail("insert is expected to fail due to type mismatch")
    } catch {
      case _: Exception => // expected
    }

    tableEnv.executeSql("drop table dest1")
    tableEnv.executeSql("insert into dest2 select x from src").await()
  }

  def getPersonData: List[(String, Int, Double, String)] = {
    val data = new mutable.MutableList[(String, Int, Double, String)]
    data.+=(("Mike", 1, 12.3, "Smith"))
    data.+=(("Bob", 2, 45.6, "Taylor"))
    data.+=(("Sam", 3, 7.89, "Miller"))
    data.+=(("Peter", 4, 0.12, "Smith"))
    data.+=(("Liz", 5, 34.5, "Williams"))
    data.+=(("Sally", 6, 6.78, "Miller"))
    data.+=(("Alice", 7, 90.1, "Smith"))
    data.+=(("Kelly", 8, 2.34, "Williams"))
    data.toList
  }

  private def assertFirstValues(tableName: String): Unit = {
    assertFirstValues(tEnv, tableName)
  }

  private def assertFirstValues(tEnv: TableEnvironment, tableName: String): Unit = {
    val expected = List("Mike", "Bob", "Sam", "Peter", "Liz", "Sally", "Alice", "Kelly")
    val actual = getTableData(tEnv, tableName)
    assertEquals(expected.sorted, actual.sorted)
  }

  private def assertLastValues(tableName: String): Unit = {
    val actual = getTableData(tEnv, tableName)
    assertEquals(getExpectedLastValues.sorted, actual.sorted)
  }

  private def getExpectedLastValues: List[String] = {
    List("Smith", "Taylor", "Miller", "Smith", "Williams", "Miller", "Smith", "Williams")
  }

  private def getTableData(tEnv: TableEnvironment, tableName: String): List[String] = {
    val iterator = tEnv.executeSql(s"select * from $tableName").collect()
    val result = new mutable.MutableList[String]
    try {
      while (iterator.hasNext) {
        result.+=(TestSinkUtil.rowToString(iterator.next()))
      }
    } finally {
      iterator.close()
    }
    result.toList
  }

  private def checkEmptyDir(dirPath: String): Unit = {
    val dir = new File(dirPath)
    assertTrue(!dir.exists() || dir.listFiles().isEmpty)
  }

  private def deleteDir(dirPath: String): Unit = {
    val dir = new File(dirPath)
    if (!dir.exists()) {
      return
    }
    if (dir.isFile) {
      deleteFile(dir.getAbsolutePath)
      return
    }
    dir.listFiles().foreach(file => deleteDir(file.getAbsolutePath))
    dir.delete()
    assertFalse(new File(dirPath).exists())
  }

  private def deleteFile(path: String): Unit = {
    new File(path).delete()
    assertFalse(new File(path).exists())
  }

  private def checkInsertTableResult(tableResult: TableResult, fieldNames: String*): Unit = {
    assertTrue(tableResult.getJobClient.isPresent)
    assertEquals(ResultKind.SUCCESS_WITH_CONTENT, tableResult.getResultKind)
    assertEquals(util.Arrays.asList(fieldNames: _*), tableResult.getResolvedSchema.getColumnNames)
    // return the result until the job is finished
    val it = tableResult.collect()
    assertTrue(it.hasNext)
    val affectedRowCounts = fieldNames.map(_ => JLong.valueOf(-1L))
    assertEquals(Row.of(affectedRowCounts: _*), it.next())
    assertFalse(it.hasNext)
  }

}

object TableEnvironmentITCase {
  @Parameters(name = "{0}:isStream={1}")
  def parameters(): util.Collection[Array[_]] = {
    util.Arrays.asList(
      Array("TableEnvironment", true),
      Array("TableEnvironment", false),
      Array("StreamTableEnvironment", true)
    )
  }
}

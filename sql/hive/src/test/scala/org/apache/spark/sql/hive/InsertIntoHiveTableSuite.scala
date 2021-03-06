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

package org.apache.spark.sql.hive

import java.io.File

import org.apache.hadoop.hive.conf.HiveConf
import org.scalatest.BeforeAndAfter

import org.apache.spark.sql.execution.QueryExecutionException
import org.apache.spark.sql.{QueryTest, _}
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

/* Implicits */
import org.apache.spark.sql.hive.test.TestHive._

case class TestData(key: Int, value: String)

case class ThreeCloumntable(key: Int, value: String, key1: String)

/**
  * 插入数据到Hive表
  */
class InsertIntoHiveTableSuite extends QueryTest with BeforeAndAfter {
  import org.apache.spark.sql.hive.test.TestHive.implicits._


  val testData = TestHive.sparkContext.parallelize(
    (1 to 100).map(i => TestData(i, i.toString))).toDF()

  before {
    // Since every we are doing tests for DDL statements,
    // it is better to reset before every test.
    //由于我们正在为DDL语句进行测试,最好在每次测试之前进行重置。
    TestHive.reset()
    // Register the testData, which will be used in every test.
    //注册testData,将在每次测试中使用。
    testData.registerTempTable("testData")
  }
  //插入Into（）HiveTable
  test("insertInto() HiveTable") {
    sql("CREATE TABLE createAndInsertTest (key int, value string)")

    // Add some data.
    //添加一些数据
    testData.write.mode(SaveMode.Append).insertInto("createAndInsertTest")

    // Make sure the table has also been updated.
    //确保表格也已更新
    checkAnswer(
      sql("SELECT * FROM createAndInsertTest"),
      testData.collect().toSeq
    )

    // Add more data.
    //添加更多数据
    testData.write.mode(SaveMode.Append).insertInto("createAndInsertTest")

    // Make sure the table has been updated.
    //确保表已更新
    checkAnswer(
      sql("SELECT * FROM createAndInsertTest"),
      testData.toDF().collect().toSeq ++ testData.toDF().collect().toSeq
    )

    // Now overwrite.
    //现在覆盖
    testData.write.mode(SaveMode.Overwrite).insertInto("createAndInsertTest")

    // Make sure the registered table has also been updated.
    //确保已注册的表格也已更新
    checkAnswer(
      sql("SELECT * FROM createAndInsertTest"),
      testData.collect().toSeq
    )
  }
  //当allowExisting = false时,创建表二次失败
  test("Double create fails when allowExisting = false") {
    sql("CREATE TABLE doubleCreateAndInsertTest (key int, value string)")

    intercept[QueryExecutionException] {
      sql("CREATE TABLE doubleCreateAndInsertTest (key int, value string)")
    }
  }
  //当allowExisting = true时,创建二次不会失败
  test("Double create does not fail when allowExisting = true") {
    sql("CREATE TABLE doubleCreateAndInsertTest (key int, value string)")
    sql("CREATE TABLE IF NOT EXISTS doubleCreateAndInsertTest (key int, value string)")
  }
  //scala.collection.Map作为MapType的值类型
  test("SPARK-4052: scala.collection.Map as value type of MapType") {
    val schema = StructType(StructField("m", MapType(StringType, StringType), true) :: Nil)
    val rowRDD = TestHive.sparkContext.parallelize(
      (1 to 100).map(i => Row(scala.collection.mutable.HashMap(s"key$i" -> s"value$i"))))
    val df = TestHive.createDataFrame(rowRDD, schema)
    df.registerTempTable("tableWithMapValue")
    sql("CREATE TABLE hiveTableWithMapValue(m MAP <STRING, STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithMapValue SELECT m FROM tableWithMapValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithMapValue"),
      rowRDD.collect().toSeq
    )

    sql("DROP TABLE hiveTableWithMapValue")
  }
  //随机分区目录顺序
  test("SPARK-4203:random partition directory order") {
    sql("CREATE TABLE tmp_table (key int, value string)")
    val tmpDir = Utils.createTempDir()
    val stagingDir = new HiveConf().getVar(HiveConf.ConfVars.STAGINGDIR)

    sql(
      s"""
         |CREATE TABLE table_with_partition(c1 string)
         |PARTITIONED by (p1 string,p2 string,p3 string,p4 string,p5 string)
         |location '${tmpDir.toURI.toString}'
        """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='1')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='2')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='3')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='4')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    def listFolders(path: File, acc: List[String]): List[List[String]] = {
      val dir = path.listFiles()
      val folders = dir.filter { e => e.isDirectory && !e.getName().startsWith(stagingDir) }.toList
      if (folders.isEmpty) {
        List(acc.reverse)
      } else {
        folders.flatMap(x => listFolders(x, x.getName :: acc))
      }
    }
    val expected = List(
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=2"::Nil,
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=3"::Nil ,
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=1"::Nil ,
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=4"::Nil
    )
    assert(listFolders(tmpDir, List()).sortBy(_.toString()) === expected.sortBy(_.toString))
    sql("DROP TABLE table_with_partition")
    sql("DROP TABLE tmp_table")
  }
  //插入ArrayType.containsNull == false
  test("Insert ArrayType.containsNull == false") {
    val schema = StructType(Seq(
      StructField("a", ArrayType(StringType, containsNull = false))))
    val rowRDD = TestHive.sparkContext.parallelize((1 to 100).map(i => Row(Seq(s"value$i"))))
    val df = TestHive.createDataFrame(rowRDD, schema)
    df.registerTempTable("tableWithArrayValue")
    sql("CREATE TABLE hiveTableWithArrayValue(a Array <STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithArrayValue SELECT a FROM tableWithArrayValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithArrayValue"),
      rowRDD.collect().toSeq)

    sql("DROP TABLE hiveTableWithArrayValue")
  }
  //插入MapType.valueContainsNull == false
  test("Insert MapType.valueContainsNull == false") {
    val schema = StructType(Seq(
      StructField("m", MapType(StringType, StringType, valueContainsNull = false))))
    val rowRDD = TestHive.sparkContext.parallelize(
      (1 to 100).map(i => Row(Map(s"key$i" -> s"value$i"))))
    val df = TestHive.createDataFrame(rowRDD, schema)
    df.registerTempTable("tableWithMapValue")
    sql("CREATE TABLE hiveTableWithMapValue(m Map <STRING, STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithMapValue SELECT m FROM tableWithMapValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithMapValue"),
      rowRDD.collect().toSeq)

    sql("DROP TABLE hiveTableWithMapValue")
  }
  //插入StructType.fields.exists（_。nullable == false）
  test("Insert StructType.fields.exists(_.nullable == false)") {
    val schema = StructType(Seq(
      StructField("s", StructType(Seq(StructField("f", StringType, nullable = false))))))
    val rowRDD = TestHive.sparkContext.parallelize(
      (1 to 100).map(i => Row(Row(s"value$i"))))
    val df = TestHive.createDataFrame(rowRDD, schema)
    df.registerTempTable("tableWithStructValue")
    sql("CREATE TABLE hiveTableWithStructValue(s Struct <f: STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithStructValue SELECT s FROM tableWithStructValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithStructValue"),
      rowRDD.collect().toSeq)

    sql("DROP TABLE hiveTableWithStructValue")
  }
  //分区模式与表模式不匹配
  test("SPARK-5498:partition schema does not match table schema") {
    val testData = TestHive.sparkContext.parallelize(
      (1 to 10).map(i => TestData(i, i.toString))).toDF()
    testData.registerTempTable("testData")

    val testDatawithNull = TestHive.sparkContext.parallelize(
      (1 to 10).map(i => ThreeCloumntable(i, i.toString, null))).toDF()

    val tmpDir = Utils.createTempDir()
    sql(
      s"""
         |CREATE TABLE table_with_partition(key int,value string)
         |PARTITIONED by (ds string) location '${tmpDir.toURI.toString}'
       """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (ds='1') SELECT key,value FROM testData
      """.stripMargin)

    // test schema the same between partition and table
    //分区和表之间的测试模式相同
    sql("ALTER TABLE table_with_partition CHANGE COLUMN key key BIGINT")
    checkAnswer(sql("select key,value from table_with_partition where ds='1' "),
      testData.collect().toSeq
    )

    // test difference type of field
    //测试差异类型的字段
    sql("ALTER TABLE table_with_partition CHANGE COLUMN key key BIGINT")
    checkAnswer(sql("select key,value from table_with_partition where ds='1' "),
      testData.collect().toSeq
    )

    // add column to table
    //添加列到表
    sql("ALTER TABLE table_with_partition ADD COLUMNS(key1 string)")
    checkAnswer(sql("select key,value,key1 from table_with_partition where ds='1' "),
      testDatawithNull.collect().toSeq
    )

    // change column name to table
    //将列名更改为表
    sql("ALTER TABLE table_with_partition CHANGE COLUMN key keynew BIGINT")
    checkAnswer(sql("select keynew,value from table_with_partition where ds='1' "),
      testData.collect().toSeq
    )

    sql("DROP TABLE table_with_partition")
  }
}

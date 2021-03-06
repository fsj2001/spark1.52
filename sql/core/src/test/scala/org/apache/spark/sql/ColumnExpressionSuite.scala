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

package org.apache.spark.sql

import org.apache.spark.sql.catalyst.expressions.NamedExpression
import org.scalatest.Matchers._

import org.apache.spark.sql.execution.{Project, TungstenProject}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types._
//列表达式测试套件
class ColumnExpressionSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  private lazy val booleanData = {
    ctx.createDataFrame(ctx.sparkContext.parallelize(
      Row(false, false) ::
      Row(false, true) ::
      Row(true, false) ::
      Row(true, true) :: Nil),
      //StructType代表一张表,StructField代表一个字段
      StructType(Seq(StructField("a", BooleanType), StructField("b", BooleanType))))
  }

  test("column names with space") {//空格的列名称
    val df = Seq((1, "a")).toDF("name with space", "name.with.dot")

    checkAnswer(
      df.select(df("name with space")),
      Row(1) :: Nil)

    checkAnswer(
      df.select($"name with space"),
      Row(1) :: Nil)

    checkAnswer(
      df.select(col("name with space")),
      Row(1) :: Nil)

    checkAnswer(
      df.select("name with space"),
      Row(1) :: Nil)

    checkAnswer(
      df.select(expr("`name with space`")),
      Row(1) :: Nil)
  }

  test("column names with dot") {//列名的点号
    val df = Seq((1, "a")).toDF("name with space", "name.with.dot").as("a")

    checkAnswer(
      df.select(df("`name.with.dot`")),
      Row("a") :: Nil)

    checkAnswer(
      df.select($"`name.with.dot`"),
      Row("a") :: Nil)

    checkAnswer(
      df.select(col("`name.with.dot`")),
      Row("a") :: Nil)

    checkAnswer(
      df.select("`name.with.dot`"),
      Row("a") :: Nil)

    checkAnswer(
      df.select(expr("`name.with.dot`")),
      Row("a") :: Nil)

    checkAnswer(
      df.select(df("a.`name.with.dot`")),
      Row("a") :: Nil)

    checkAnswer(
      df.select($"a.`name.with.dot`"),
      Row("a") :: Nil)

    checkAnswer(
      df.select(col("a.`name.with.dot`")),
      Row("a") :: Nil)

    checkAnswer(
      df.select("a.`name.with.dot`"),
      Row("a") :: Nil)

    checkAnswer(
      df.select(expr("a.`name.with.dot`")),
      Row("a") :: Nil)
  }

  test("alias") {//别名
    val df = Seq((1, Seq(1, 2, 3))).toDF("a", "intList")
    assert(df.select(df("a").as("b")).columns.head === "b")
    assert(df.select(df("a").alias("b")).columns.head === "b")
  }

  test("as propagates metadata") {//作为传播元数据
    val metadata = new MetadataBuilder
    metadata.putString("key", "value")
    val origCol = $"a".as("b", metadata.build())
    val newCol = origCol.as("c")
    assert(newCol.expr.asInstanceOf[NamedExpression].metadata.getString("key") === "value")
  }

  test("single explode") {//函数把字符串分割为数组
    val df = Seq((1, Seq(1, 2, 3))).toDF("a", "intList")
    /**
     *+---+---------+
      |  a|  intList|
      +---+---------+
      |  1|[1, 2, 3]|
      +---+---------+*/
    df.show()
    checkAnswer(
      //默认使用逗号分割另一个字符串,并返回由字符串组成的数组
      df.select(explode('intList)),
      Row(1) :: Row(2) :: Row(3) :: Nil)
  }
  //把行转换成列
  test("explode and other columns") {//其他列函数把字符串分割为数组
    val df = Seq((1, Seq(1, 2, 3))).toDF("a", "intList")
    /**
     *+---+---------+
      |  a|  intList|
      +---+---------+
      |  1|[1, 2, 3]|
      +---+---------+ */
    df.show()
    /**
     *+---+---+
      |  a|_c0|
      +---+---+
      |  1|  1|
      |  1|  2|
      |  1|  3|
      +---+---+*/
    df.select($"a", explode('intList)).show()
    checkAnswer(
      df.select($"a", explode('intList)),
      Row(1, 1) ::
      Row(1, 2) ::
      Row(1, 3) :: Nil)

    checkAnswer(
      //*分隔之后行
      df.select($"*", explode('intList)),
      Row(1, Seq(1, 2, 3), 1) ::
      Row(1, Seq(1, 2, 3), 2) ::
      Row(1, Seq(1, 2, 3), 3) :: Nil)
  }

  test("aliased explode") {//别名函数把字符串分割为数组
    val df = Seq((1, Seq(1, 2, 3))).toDF("a", "intList")

    checkAnswer(//分隔后使用别名,查询
      df.select(explode('intList).as('int)).select('int),
      Row(1) :: Row(2) :: Row(3) :: Nil)

    checkAnswer(//分隔后使用别名,查询合计
      df.select(explode('intList).as('int)).select(sum('int)),
      Row(6) :: Nil)
  }
  //Map转换成行
  test("explode on map") {//Map函数把字符串分割为数组
    val df = Seq((1, Map("a" -> "b"))).toDF("a", "map")
    //map形式分隔
    checkAnswer(
      df.select(explode('map)),
      Row("a", "b"))
  }

  test("explode on map with aliases") {//在Map上的别名函数把字符串分割为数组
    val df = Seq((1, Map("a" -> "b"))).toDF("a", "map")

    checkAnswer(
        //在Map上的别名函数把字符串分割为数组
      df.select(explode('map).as("key1" :: "value1" :: Nil)).select("key1", "value1"),
      Row("a", "b"))
  }

  test("self join explode") {//自加入函数把字符串分割为数组
    val df = Seq((1, Seq(1, 2, 3))).toDF("a", "intList")
    val exploded = df.select(explode('intList).as('i))

    checkAnswer(//df.agg() 求聚合用的相关函数
      exploded.join(exploded, exploded("i") === exploded("i")).agg(count("*")),
      Row(3) :: Nil)
  }

  test("collect on column produced by a binary operator") {//按二元运算符生成的列
    val df = Seq((1, 2, 3)).toDF("a", "b", "c")
    checkAnswer(df.select(df("a") + df("b")), Seq(Row(3)))
    checkAnswer(df.select(df("a") + df("b").as("c")), Seq(Row(3)))
  }

  test("star") {//星号
    checkAnswer(testData.select($"*"), testData.collect().toSeq)
  }

  test("star qualified by data frame object") {//星号等于来自数据对象
    val df = testData.toDF
    val goldAnswer = df.collect().toSeq
    checkAnswer(df.select(df("*")), goldAnswer)

    val df1 = df.select(df("*"), lit("abcd").as("litCol"))
    checkAnswer(df1.select(df("*")), goldAnswer)
  }

  test("star qualified by table name") {//星号等于表的名称
    checkAnswer(testData.as("testData").select($"testData.*"), testData.collect().toSeq)
  }

  test("+") {//加号操作
    checkAnswer(
      testData2.select($"a" + 1),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) + 1)))

    checkAnswer(
      testData2.select($"a" + $"b" + 2),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) + r.getInt(1) + 2)))
  }

  test("-") {//减号操作
    checkAnswer(
      testData2.select($"a" - 1),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) - 1)))

    checkAnswer(
      testData2.select($"a" - $"b" - 2),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) - r.getInt(1) - 2)))
  }

  test("*") {//乘法操作
    checkAnswer(
      testData2.select($"a" * 10),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) * 10)))

    checkAnswer(
      testData2.select($"a" * $"b"),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) * r.getInt(1))))
  }

  test("/") {
    checkAnswer(//取余操作
      testData2.select($"a" / 2),
      testData2.collect().toSeq.map(r => Row(r.getInt(0).toDouble / 2)))

    checkAnswer(
      testData2.select($"a" / $"b"),
      testData2.collect().toSeq.map(r => Row(r.getInt(0).toDouble / r.getInt(1))))
  }


  test("%") {
    checkAnswer(
      testData2.select($"a" % 2),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) % 2)))

    checkAnswer(
      testData2.select($"a" % $"b"),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) % r.getInt(1))))
  }

  test("unary -") {//二元-号
    checkAnswer(
      testData2.select(-$"a"),//负数
      testData2.collect().toSeq.map(r => Row(-r.getInt(0))))
  }

  test("unary !") {
    checkAnswer(//非操作
      complexData.select(!$"b"),
      complexData.collect().toSeq.map(r => Row(!r.getBoolean(3))))
  }

  test("isNull") {//是否null
    checkAnswer(//判断是否为null
      nullStrings.toDF.where($"s".isNull),
      nullStrings.collect().toSeq.filter(r => r.getString(1) eq null))

    checkAnswer(
      sql("select isnull(null), isnull(1)"),
      Row(true, false))
  }

  test("isNotNull") {//参数不为Null时返回true
    checkAnswer(
      nullStrings.toDF.where($"s".isNotNull),
      nullStrings.collect().toSeq.filter(r => r.getString(1) ne null))

    checkAnswer(
      sql("select isnotnull(null), isnotnull('a')"),
      Row(false, true))
  }
   //NaN 是Not-a-Number的缩写,某些float或double类型不符合标准浮点数语义
   //NaN == NaN,即：NaN和NaN总是相等
   //在聚合函数中,所有NaN分到同一组
   //NaN在join操作中可以当做一个普通的join key
   //NaN在升序排序中排到最后,比任何其他数值都大
  test("isNaN") {//指明提供的值是否是保留值 NaN(不是数字)
    val testData = ctx.createDataFrame(ctx.sparkContext.parallelize(
      Row(Double.NaN, Float.NaN) ::
      Row(math.log(-1), math.log(-3).toFloat) ::
      Row(null, null) ::
      Row(Double.MaxValue, Float.MinValue):: Nil),
      //StructType代表一张表,StructField代表一个字段
      StructType(Seq(StructField("a", DoubleType), StructField("b", FloatType))))

    checkAnswer(
      testData.select($"a".isNaN, $"b".isNaN),
      Row(true, true) :: Row(true, true) :: Row(false, false) :: Row(false, false) :: Nil)

    checkAnswer(
      testData.select(isNaN($"a"), isNaN($"b")),
      Row(true, true) :: Row(true, true) :: Row(false, false) :: Row(false, false) :: Nil)

    checkAnswer(
      sql("select isnan(15), isnan('invalid')"),
      Row(false, false))
  }

  test("nanvl") {//如果n2为NaN(不是数值),返回n1,否则返回n2.
    val testData = ctx.createDataFrame(ctx.sparkContext.parallelize(
      Row(null, 3.0, Double.NaN, Double.PositiveInfinity, 1.0f, 4) :: Nil),
      //StructType代表一张表,StructField代表一个字段
      StructType(Seq(StructField("a", DoubleType), StructField("b", DoubleType),
        StructField("c", DoubleType), StructField("d", DoubleType),
        StructField("e", FloatType), StructField("f", IntegerType))))

    checkAnswer(
      testData.select(
          //判断是否为数字类型
        nanvl($"a", lit(5)), nanvl($"b", lit(10)), nanvl(lit(10), $"b"),
        nanvl($"c", lit(null).cast(DoubleType)), nanvl($"d", lit(10)),
        nanvl($"b", $"e"), nanvl($"e", $"f")),
      Row(null, 3.0, 10.0, null, Double.PositiveInfinity, 3.0, 1.0)
    )
    testData.registerTempTable("t")
    checkAnswer(
      sql(
        "select nanvl(a, 5), nanvl(b, 10), nanvl(10, b), nanvl(c, null), nanvl(d, 10), " +
          " nanvl(b, e), nanvl(e, f) from t"),
      Row(null, 3.0, 10.0, null, Double.PositiveInfinity, 3.0, 1.0)
    )
  }

  test("===") {
    checkAnswer(
      testData2.filter($"a" === 1),
      testData2.collect().toSeq.filter(r => r.getInt(0) == 1))

    checkAnswer(
      testData2.filter($"a" === $"b"),
      testData2.collect().toSeq.filter(r => r.getInt(0) == r.getInt(1)))
  }

  test("<=>") {
    checkAnswer(
      testData2.filter($"a" === 1),
      testData2.collect().toSeq.filter(r => r.getInt(0) == 1))

    checkAnswer(
      testData2.filter($"a" === $"b"),
      testData2.collect().toSeq.filter(r => r.getInt(0) == r.getInt(1)))
  }

  test("!==") {
    val nullData = ctx.createDataFrame(ctx.sparkContext.parallelize(
      Row(1, 1) ::
      Row(1, 2) ::
      Row(1, null) ::
      Row(null, null) :: Nil),
      //StructType代表一张表,StructField代表一个字段
      StructType(Seq(StructField("a", IntegerType), StructField("b", IntegerType))))

    checkAnswer(
      nullData.filter($"b" <=> 1),
      Row(1, 1) :: Nil)

    checkAnswer(
      nullData.filter($"b" <=> null),
      Row(1, null) :: Row(null, null) :: Nil)

    checkAnswer(
      nullData.filter($"a" <=> $"b"),
      Row(1, 1) :: Row(null, null) :: Nil)
  }

  test(">") {
    checkAnswer(
      testData2.filter($"a" > 1),
      testData2.collect().toSeq.filter(r => r.getInt(0) > 1))

    checkAnswer(
      testData2.filter($"a" > $"b"),
      testData2.collect().toSeq.filter(r => r.getInt(0) > r.getInt(1)))
  }

  test(">=") {
    checkAnswer(
      testData2.filter($"a" >= 1),
      testData2.collect().toSeq.filter(r => r.getInt(0) >= 1))

    checkAnswer(
      testData2.filter($"a" >= $"b"),
      testData2.collect().toSeq.filter(r => r.getInt(0) >= r.getInt(1)))
  }

  test("<") {
    checkAnswer(
      testData2.filter($"a" < 2),
      testData2.collect().toSeq.filter(r => r.getInt(0) < 2))

    checkAnswer(
      testData2.filter($"a" < $"b"),
      testData2.collect().toSeq.filter(r => r.getInt(0) < r.getInt(1)))
  }

  test("<=") {
    checkAnswer(
      testData2.filter($"a" <= 2),
      testData2.collect().toSeq.filter(r => r.getInt(0) <= 2))

    checkAnswer(
      testData2.filter($"a" <= $"b"),
      testData2.collect().toSeq.filter(r => r.getInt(0) <= r.getInt(1)))
  }

  test("between") {
    val testData = ctx.sparkContext.parallelize(
      (0, 1, 2) ::
      (1, 2, 3) ::
      (2, 1, 0) ::
      (2, 2, 4) ::
      (3, 1, 6) ::
      (3, 2, 0) :: Nil).toDF("a", "b", "c")
    val expectAnswer = testData.collect().toSeq.
      filter(r => r.getInt(0) >= r.getInt(1) && r.getInt(0) <= r.getInt(2))

    checkAnswer(testData.filter($"a".between($"b", $"c")), expectAnswer)
  }
  //字段访问方式
  test("in") {
    val df = Seq((1, "x"), (2, "y"), (3, "z")).toDF("a", "b")
    checkAnswer(df.filter($"a".isin(1, 2)),
      df.collect().toSeq.filter(r => r.getInt(0) == 1 || r.getInt(0) == 2))
    checkAnswer(df.filter($"a".isin(3, 2)),
      df.collect().toSeq.filter(r => r.getInt(0) == 3 || r.getInt(0) == 2))
    checkAnswer(df.filter($"a".isin(3, 1)),
      df.collect().toSeq.filter(r => r.getInt(0) == 3 || r.getInt(0) == 1))
    checkAnswer(df.filter($"b".isin("y", "x")),
      df.collect().toSeq.filter(r => r.getString(1) == "y" || r.getString(1) == "x"))
    checkAnswer(df.filter($"b".isin("z", "x")),
      df.collect().toSeq.filter(r => r.getString(1) == "z" || r.getString(1) == "x"))
    checkAnswer(df.filter($"b".isin("z", "y")),
      df.collect().toSeq.filter(r => r.getString(1) == "z" || r.getString(1) == "y"))

    val df2 = Seq((1, Seq(1)), (2, Seq(2)), (3, Seq(3))).toDF("a", "b")

    intercept[AnalysisException] {
      df2.filter($"a".isin($"b"))
    }
  }

  test("&&") {
    checkAnswer(
      booleanData.filter($"a" && true),
      Row(true, false) :: Row(true, true) :: Nil)

    checkAnswer(
      booleanData.filter($"a" && false),
      Nil)

    checkAnswer(
      booleanData.filter($"a" && $"b"),
      Row(true, true) :: Nil)
  }

  test("||") {
    checkAnswer(
      booleanData.filter($"a" || true),
      booleanData.collect())

    checkAnswer(
      booleanData.filter($"a" || false),
      Row(true, false) :: Row(true, true) :: Nil)

    checkAnswer(
      booleanData.filter($"a" || $"b"),
      Row(false, true) :: Row(true, false) :: Row(true, true) :: Nil)
  }

  test("SPARK-7321 when conditional statements") {//当条件声明
    val testData = (1 to 3).map(i => (i, i.toString)).toDF("key", "value")

    checkAnswer(
      testData.select(when($"key" === 1, -1).when($"key" === 2, -2).otherwise(0)),
      Seq(Row(-1), Row(-2), Row(0))
    )

    // Without the ending otherwise, return null for unmatched conditions.
    // Also test putting a non-literal value in the expression.
    //如果没有结束，否则返回不匹配条件的null。
    //也测试在表达式中放置非文字值。
    checkAnswer(
      testData.select(when($"key" === 1, lit(0) - $"key").when($"key" === 2, -2)),
      Seq(Row(-1), Row(-2), Row(null))
    )

    // Test error handling for invalid expressions.
    //测试无效表达式的错误处理
    intercept[IllegalArgumentException] { $"key".when($"key" === 1, -1) }
    intercept[IllegalArgumentException] { $"key".otherwise(-1) }
    intercept[IllegalArgumentException] { when($"key" === 1, -1).otherwise(-1).otherwise(-1) }
  }

  test("sqrt") {//返回n的平方根
    checkAnswer(
      testData.select(sqrt('key)).orderBy('key.asc),
      (1 to 100).map(n => Row(math.sqrt(n)))
    )

    checkAnswer(
      testData.select(sqrt('value), 'key).orderBy('key.asc, 'value.asc),
      (1 to 100).map(n => Row(math.sqrt(n), n))
    )

    checkAnswer(
      testData.select(sqrt(lit(null))),
      (1 to 100).map(_ => Row(null))
    )
  }

  test("upper") {//将char中字符串转化为大写字母
    checkAnswer(
      lowerCaseData.select(upper('l)),
      ('a' to 'd').map(c => Row(c.toString.toUpperCase))
    )

    checkAnswer(
      testData.select(upper('value), 'key),
      (1 to 100).map(n => Row(n.toString, n))
    )

    checkAnswer(
      testData.select(upper(lit(null))),
      (1 to 100).map(n => Row(null))
    )

    checkAnswer(
      sql("SELECT upper('aB'), ucase('cDe')"),
      Row("AB", "CDE"))
  }

  test("lower") {//将char中字符串转化为小写字母
    checkAnswer(
      upperCaseData.select(lower('L)),
      ('A' to 'F').map(c => Row(c.toString.toLowerCase))
    )

    checkAnswer(
      testData.select(lower('value), 'key),
      (1 to 100).map(n => Row(n.toString, n))
    )

    checkAnswer(
      testData.select(lower(lit(null))),
      (1 to 100).map(n => Row(null))
    )

    checkAnswer(
      sql("SELECT lower('aB'), lcase('cDe')"),
      Row("ab", "cde"))
  }

  test("monotonicallyIncreasingId") {
    // Make sure we have 2 partitions, each with 2 records.
    //确保我们有2个分区,每个分区有2个记录,
    val df = ctx.sparkContext.parallelize(Seq[Int](), 2).mapPartitions { _ =>
      Iterator(Tuple1(1), Tuple1(2))
    }.toDF("a")
    checkAnswer(
      df.select(monotonicallyIncreasingId()),
      Row(0L) :: Row(1L) :: Row((1L << 33) + 0L) :: Row((1L << 33) + 1L) :: Nil
    )
  }

  test("sparkPartitionId") {
    // Make sure we have 2 partitions, each with 2 records.
    //确保我们有2个分区,每个分区有2个记录。
    val df = ctx.sparkContext.parallelize(Seq[Int](), 2).mapPartitions { _ =>
      Iterator(Tuple1(1), Tuple1(2))
    }.toDF("a")
     df.select(sparkPartitionId()).show()
    checkAnswer(
      df.select(sparkPartitionId()),
      Row(0) :: Row(0) :: Row(1) :: Row(1) :: Nil
    )
  }

  test("InputFileName") {
    withTempPath { dir =>
      val data = sqlContext.sparkContext.parallelize(0 to 10).toDF("id")
      data.write.parquet(dir.getCanonicalPath)
      val answer = sqlContext.read.parquet(dir.getCanonicalPath).select(inputFileName())
        .head.getString(0)
      assert(answer.contains(dir.getCanonicalPath))

      checkAnswer(data.select(inputFileName()).limit(1), Row(""))
    }
  }
  //cast将一种内置的数据类型或集合类型转化成另一种内置的数据类型或集合类型
  test("lift alias out of cast") {
    compareExpressions(
      col("1234").as("name").cast("int").expr,
      col("1234").cast("int").as("name").expr)
  }

  test("columns can be compared") {//可以比较列
    assert('key.desc == 'key.desc)
    assert('key.desc != 'key.asc)
  }

  test("alias with metadata") {//别名元数据
    val metadata = new MetadataBuilder()
      .putString("originName", "value")
      .build()
    val schema = testData
      .select($"*", col("value").as("abc", metadata))
      .schema
    assert(schema("value").metadata === Metadata.empty)
    assert(schema("abc").metadata === metadata)
  }

  test("rand") {//rand函数功能为获取一个伪随机数
    val randCol = testData.select($"key", rand(5L).as("rand"))
    randCol.columns.length should be (2)
    val rows = randCol.collect()
    rows.foreach { row =>
      assert(row.getDouble(1) <= 1.0)
      assert(row.getDouble(1) >= 0.0)
    }

    def checkNumProjects(df: DataFrame, expectedNumProjects: Int): Unit = {
      val projects = df.queryExecution.executedPlan.collect {
        case project: Project => project
        case tungstenProject: TungstenProject => tungstenProject
      }
      assert(projects.size === expectedNumProjects)
    }

    // We first create a plan with two Projects.
    //我们首先创建一个有两个项目的计划。
    // Project [rand + 1 AS rand1, rand - 1 AS rand2]
    //   Project [key, (Rand 5 + 1) AS rand]
    //     LogicalRDD [key, value]
    // Because Rand function is not deterministic, the column rand is not deterministic.
    // So, in the optimizer, we will not collapse Project [rand + 1 AS rand1, rand - 1 AS rand2]
    // and Project [key, Rand 5 AS rand]. The final plan still has two Projects.
    val dfWithTwoProjects =
      testData
        .select($"key", (rand(5L) + 1).as("rand"))
        .select(($"rand" + 1).as("rand1"), ($"rand" - 1).as("rand2"))
    checkNumProjects(dfWithTwoProjects, 2)

    // Now, we add one more project rand1 - rand2 on top of the query plan.
    // Since rand1 and rand2 are deterministic (they basically apply +/- to the generated
    // rand value), we can collapse rand1 - rand2 to the Project generating rand1 and rand2.
    // So, the plan will be optimized from ...
    // Project [(rand1 - rand2) AS (rand1 - rand2)]
    //   Project [rand + 1 AS rand1, rand - 1 AS rand2]
    //     Project [key, (Rand 5 + 1) AS rand]
    //       LogicalRDD [key, value]
    // to ...
    // Project [((rand + 1 AS rand1) - (rand - 1 AS rand2)) AS (rand1 - rand2)]
    //   Project [key, Rand 5 AS rand]
    //     LogicalRDD [key, value]
    val dfWithThreeProjects = dfWithTwoProjects.select($"rand1" - $"rand2")
    checkNumProjects(dfWithThreeProjects, 2)
    dfWithThreeProjects.collect().foreach { row =>
      assert(row.getDouble(0) === 2.0 +- 0.0001)
    }
  }

  test("randn") {//randn函数功能为获取一个随机数
    val randCol = testData.select('key, randn(5L).as("rand"))
    randCol.columns.length should be (2)
    val rows = randCol.collect()
    rows.foreach { row =>
      assert(row.getDouble(1) <= 4.0)
      assert(row.getDouble(1) >= -4.0)
    }
  }

  test("bitwiseAND") {
    checkAnswer(
      testData2.select($"a".bitwiseAND(75)),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) & 75)))

    checkAnswer(
      testData2.select($"a".bitwiseAND($"b").bitwiseAND(22)),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) & r.getInt(1) & 22)))
  }

  test("bitwiseOR") {
    checkAnswer(
      testData2.select($"a".bitwiseOR(170)),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) | 170)))

    checkAnswer(
      testData2.select($"a".bitwiseOR($"b").bitwiseOR(42)),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) | r.getInt(1) | 42)))
  }

  test("bitwiseXOR") {
    checkAnswer(
      testData2.select($"a".bitwiseXOR(112)),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) ^ 112)))

    checkAnswer(
      testData2.select($"a".bitwiseXOR($"b").bitwiseXOR(39)),
      testData2.collect().toSeq.map(r => Row(r.getInt(0) ^ r.getInt(1) ^ 39)))
  }

}

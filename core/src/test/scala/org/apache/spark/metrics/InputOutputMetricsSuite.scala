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

package org.apache.spark.metrics

import java.io.{File, FileWriter, PrintWriter}

import scala.collection.mutable.ArrayBuffer

import org.apache.commons.lang3.RandomUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapred.lib.{CombineFileInputFormat => OldCombineFileInputFormat,
  CombineFileRecordReader => OldCombineFileRecordReader, CombineFileSplit => OldCombineFileSplit}
import org.apache.hadoop.mapred.{JobConf, Reporter, FileSplit => OldFileSplit,
  InputSplit => OldInputSplit, LineRecordReader => OldLineRecordReader,
  RecordReader => OldRecordReader, TextInputFormat => OldTextInputFormat}
import org.apache.hadoop.mapreduce.lib.input.{CombineFileInputFormat => NewCombineFileInputFormat,
  CombineFileRecordReader => NewCombineFileRecordReader, CombineFileSplit => NewCombineFileSplit,
  FileSplit => NewFileSplit, TextInputFormat => NewTextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{TextOutputFormat => NewTextOutputFormat}
import org.apache.hadoop.mapreduce.{TaskAttemptContext, InputSplit => NewInputSplit,
  RecordReader => NewRecordReader}
import org.scalatest.BeforeAndAfter

import org.apache.spark.{SharedSparkContext, SparkFunSuite}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.scheduler.{SparkListener, SparkListenerTaskEnd}
import org.apache.spark.util.Utils
/**
 * Metrics 度量
 */
class InputOutputMetricsSuite extends SparkFunSuite with SharedSparkContext
  with BeforeAndAfter {

  @transient var tmpDir: File = _
  @transient var tmpFile: File = _
  @transient var tmpFilePath: String = _
  @transient val numRecords: Int = 100000
  @transient val numBuckets: Int = 10

  before {
    tmpDir = Utils.createTempDir()
    val testTempDir = new File(tmpDir, "test")
    testTempDir.mkdir()

    tmpFile = new File(testTempDir, getClass.getSimpleName + ".txt")
    val pw = new PrintWriter(new FileWriter(tmpFile))
    for (x <- 1 to numRecords) {
      // scalastyle:off println
      pw.println(RandomUtils.nextInt(0, numBuckets))
      // scalastyle:on println
    }
    pw.close()

    // Path to tmpFile
    tmpFilePath = "file://" + tmpFile.getAbsolutePath
  }

  after {
    Utils.deleteRecursively(tmpDir)
  }
  //输入标准为旧的hadoop与合并
  test("input metrics for old hadoop with coalesce") {//合并
    val bytesRead = runAndReturnBytesRead {
      sc.textFile(tmpFilePath, 4).count()
    }
    val bytesRead2 = runAndReturnBytesRead {
      sc.textFile(tmpFilePath, 4).coalesce(2).count()
    }
    assert(bytesRead != 0)
    assert(bytesRead == bytesRead2)
    assert(bytesRead2 >= tmpFile.length())
  }
  //输入数据缓存和合并
  test("input metrics with cache and coalesce") {
    // prime the cache manager
    //引导缓存管理器
    val rdd = sc.textFile(tmpFilePath, 4).cache() //缓存
    rdd.collect()

    val bytesRead = runAndReturnBytesRead {
      rdd.count()
    }
    val bytesRead2 = runAndReturnBytesRead {
      rdd.coalesce(4).count()
    }

    // for count and coelesce, the same bytes should be read.
    //对于计数和合并,应该读取相同的字节。
    assert(bytesRead != 0)
    assert(bytesRead2 == bytesRead)
  }

  /**
   * This checks the situation where we have interleaved reads from
   * different sources. Currently, we only accumulate fron the first
   * read method we find in the task. This test uses cartesian to create
   * the interleaved reads.
    * 这检查了我们从不同来源交错读取的情况,目前,我们只积累了我们在任务中找到的第一个读取方法,
    * 此测试使用笛卡尔创建交错读取
   *
   * Once https://issues.apache.org/jira/browse/SPARK-5225 is fixed
   * this test should break.
   */
  test("input metrics with mixed read method") {//输入度量混合读取方法
    // prime the cache manager
    //主要缓存管理器
    val numPartitions = 2
    val rdd = sc.parallelize(1 to 100, numPartitions).cache()
    rdd.collect()

    val rdd2 = sc.textFile(tmpFilePath, numPartitions)

    val bytesRead = runAndReturnBytesRead {
      rdd.count()
    }
    val bytesRead2 = runAndReturnBytesRead {
      rdd2.count()
    }

    val cartRead = runAndReturnBytesRead {
      rdd.cartesian(rdd2).count()
    }

    assert(cartRead != 0)
    assert(bytesRead != 0)
    // We read from the first rdd of the cartesian once per partition.
    //我们读了从笛卡尔第一RDD每分区
    assert(cartRead == bytesRead * numPartitions)
  }

  test("input metrics for new Hadoop API with coalesce") {//新的Hadoop的输入指标API与合并
    val bytesRead = runAndReturnBytesRead {
      sc.newAPIHadoopFile(tmpFilePath, classOf[NewTextInputFormat], classOf[LongWritable],
        classOf[Text]).count()
    }
    val bytesRead2 = runAndReturnBytesRead {
      sc.newAPIHadoopFile(tmpFilePath, classOf[NewTextInputFormat], classOf[LongWritable],
        classOf[Text]).coalesce(5).count()
    }
    assert(bytesRead != 0)
    assert(bytesRead2 == bytesRead)
    assert(bytesRead >= tmpFile.length())
  }

  test("input metrics when reading text file") {//读取文本文件时的输入度量
    val bytesRead = runAndReturnBytesRead {
      sc.textFile(tmpFilePath, 2).count()
    }
    assert(bytesRead >= tmpFile.length())
  }

  test("input metrics on records read - simple") {//记录读取的输入度量-简单
    val records = runAndReturnRecordsRead {
      sc.textFile(tmpFilePath, 4).count()
    }
    assert(records == numRecords)
  }

  test("input metrics on records read - more stages") {//记录读取的输入度量-多个阶段
    val records = runAndReturnRecordsRead {
      sc.textFile(tmpFilePath, 4)
        .map(key => (key.length, 1))
        .reduceByKey(_ + _)
        .count()
    }
    assert(records == numRecords)
  }

  test("input metrics on records - New Hadoop API") {//记录的输入度量-新的Hadoop API
    val records = runAndReturnRecordsRead {
      sc.newAPIHadoopFile(tmpFilePath, classOf[NewTextInputFormat], classOf[LongWritable],
        classOf[Text]).count()
    }
    assert(records == numRecords)
  }

  test("input metrics on recordsd read with cache") {//在记录读取缓存的输入指标
    // prime the cache manager
    val rdd = sc.textFile(tmpFilePath, 4).cache()
    rdd.collect()

    val records = runAndReturnRecordsRead {
      rdd.count()
    }

    assert(records == numRecords)
  }

  /**
   * Tests the metrics from end to end.
   * 测试从端到端的度量
   * 1) reading a hadoop file 读取一个hadoop文件
   * 2) shuffle and writing to a hadoop file.
   * 		shuffle和写到一个hadoop文件
   * 3) writing to hadoop file. 写Hadoop文件
   */
  //输入读/写和shuffle读/写度量所有排队
  test("input read/write and shuffle read/write metrics all line up") {
    var inputRead = 0L
    var outputWritten = 0L
    var shuffleRead = 0L
    var shuffleWritten = 0L
    sc.addSparkListener(new SparkListener() {
      override def onTaskEnd(taskEnd: SparkListenerTaskEnd) {
        val metrics = taskEnd.taskMetrics
        metrics.inputMetrics.foreach(inputRead += _.recordsRead)
        metrics.outputMetrics.foreach(outputWritten += _.recordsWritten)
        metrics.shuffleReadMetrics.foreach(shuffleRead += _.recordsRead)
        metrics.shuffleWriteMetrics.foreach(shuffleWritten += _.shuffleRecordsWritten)
      }
    })

    val tmpFile = new File(tmpDir, getClass.getSimpleName)

    sc.textFile(tmpFilePath, 4)
      .map(key => (key, 1))
      .reduceByKey(_ + _)
      .saveAsTextFile("file://" + tmpFile.getAbsolutePath)

    sc.listenerBus.waitUntilEmpty(500)
    assert(inputRead == numRecords)

    // Only supported on newer Hadoop 只有在新的Hadoop的支持
    if (SparkHadoopUtil.get.getFSBytesWrittenOnThreadCallback().isDefined) {
      assert(outputWritten == numBuckets)
    }
    assert(shuffleRead == shuffleWritten)
  }

  test("input metrics with interleaved reads") {//交叉读取输入度量
    val numPartitions = 2
    val cartVector = 0 to 9
    val cartFile = new File(tmpDir, getClass.getSimpleName + "_cart.txt")
    val cartFilePath = "file://" + cartFile.getAbsolutePath

    // write files to disk so we can read them later.
    //将文件写入磁盘,以便稍后读它们
    sc.parallelize(cartVector).saveAsTextFile(cartFilePath)
    val aRdd = sc.textFile(cartFilePath, numPartitions)

    val tmpRdd = sc.textFile(tmpFilePath, numPartitions)

    val firstSize = runAndReturnBytesRead {
      aRdd.count()
    }
    val secondSize = runAndReturnBytesRead {
      tmpRdd.count()
    }

    val cartesianBytes = runAndReturnBytesRead {
      aRdd.cartesian(tmpRdd).count()
    }

    // Computing the amount of bytes read for a cartesian operation is a little involved.
    //计算一个笛卡尔运算所读取的字节数
    // Cartesian interleaves reads between two partitions eg. p1 and p2.
    //笛卡尔交织在两个分区之间读取,例如: p1和p2
    // Here are the steps:
    //  1) First it creates an iterator for p1 首先创建一个迭代器P1
    //  2) Creates an iterator for p2 创建一个迭代器为P2
    //  3) Reads the first element of p1 and then all the elements of p2
    //    读取P1的第一个元素的所有元素,然后P2
    //  4) proceeds to the next element of p1 从P1的下一个元素
    //  5) Creates a new iterator for p2 创建一个新的迭代器P2
    //  6) rinse and repeat. 冲洗和重复
    // As a result we read from the second partition n times where n is the number of keys in
    // p1. Thus the math below for the test.
    assert(cartesianBytes != 0)
    assert(cartesianBytes == firstSize * numPartitions + (cartVector.length  * secondSize))
  }
  //没有参数函数,没有还回类型
  private def runAndReturnBytesRead(job: => Unit): Long = {
    runAndReturnMetrics(job, _.taskMetrics.inputMetrics.map(_.bytesRead))
  }

  private def runAndReturnRecordsRead(job: => Unit): Long = {
    runAndReturnMetrics(job, _.taskMetrics.inputMetrics.map(_.recordsRead))
  }

  private def runAndReturnRecordsWritten(job: => Unit): Long = {
    runAndReturnMetrics(job, _.taskMetrics.outputMetrics.map(_.recordsWritten))
  }

  private def runAndReturnMetrics(job: => Unit,
      collector: (SparkListenerTaskEnd) => Option[Long]): Long = {
    val taskMetrics = new ArrayBuffer[Long]()

    // Avoid receiving earlier taskEnd events
    sc.listenerBus.waitUntilEmpty(500)

    sc.addSparkListener(new SparkListener() {
      override def onTaskEnd(taskEnd: SparkListenerTaskEnd) {
        collector(taskEnd).foreach(taskMetrics += _)
      }
    })

    job

    sc.listenerBus.waitUntilEmpty(500)
    taskMetrics.sum
  }

  test("output metrics on records written") {//写入记录的输出度量
    // Only supported on newer Hadoop 只支持新Hadoop
    if (SparkHadoopUtil.get.getFSBytesWrittenOnThreadCallback().isDefined) {
      val file = new File(tmpDir, getClass.getSimpleName)
      val filePath = "file://" + file.getAbsolutePath

      val records = runAndReturnRecordsWritten {
        sc.parallelize(1 to numRecords).saveAsTextFile(filePath)
      }
      assert(records == numRecords)
    }
  }
  //写入记录的输出度量-新Hadoop API
  test("output metrics on records written - new Hadoop API") {
    // Only supported on newer Hadoop
    if (SparkHadoopUtil.get.getFSBytesWrittenOnThreadCallback().isDefined) {
      val file = new File(tmpDir, getClass.getSimpleName)
      val filePath = "file://" + file.getAbsolutePath

      val records = runAndReturnRecordsWritten {
        sc.parallelize(1 to numRecords).map(key => (key.toString, key.toString))
          .saveAsNewAPIHadoopFile[NewTextOutputFormat[String, String]](filePath)
      }
      assert(records == numRecords)
    }
  }
  //写入文本文件时的输出度量
  test("output metrics when writing text file") {
    val fs = FileSystem.getLocal(new Configuration())
    val outPath = new Path(fs.getWorkingDirectory, "outdir")

    if (SparkHadoopUtil.get.getFSBytesWrittenOnThreadCallback().isDefined) {
      val taskBytesWritten = new ArrayBuffer[Long]()
      sc.addSparkListener(new SparkListener() {
        override def onTaskEnd(taskEnd: SparkListenerTaskEnd) {
          taskBytesWritten += taskEnd.taskMetrics.outputMetrics.get.bytesWritten
        }
      })

      val rdd = sc.parallelize(Array("a", "b", "c", "d"), 2)

      try {
        rdd.saveAsTextFile(outPath.toString)
        sc.listenerBus.waitUntilEmpty(500)
        assert(taskBytesWritten.length == 2)
        val outFiles = fs.listStatus(outPath).filter(_.getPath.getName != "_SUCCESS")
        taskBytesWritten.zip(outFiles).foreach { case (bytes, fileStatus) =>
          assert(bytes >= fileStatus.getLen)
        }
      } finally {
        fs.delete(outPath, true)
      }
    }
  }
  //输入度量old CombineFileInputFormat
  test("input metrics with old CombineFileInputFormat") {
    val bytesRead = runAndReturnBytesRead {
      sc.hadoopFile(tmpFilePath, classOf[OldCombineTextInputFormat], classOf[LongWritable],
        classOf[Text], 2).count()
    }
    assert(bytesRead >= tmpFile.length())
  }
  //输入度量新CombineFileInputFormat
  test("input metrics with new CombineFileInputFormat") {
    val bytesRead = runAndReturnBytesRead {
      sc.newAPIHadoopFile(tmpFilePath, classOf[NewCombineTextInputFormat], classOf[LongWritable],
        classOf[Text], new Configuration()).count()
    }
    assert(bytesRead >= tmpFile.length())
  }
}

/**
 * Hadoop 2 has a version of this, but we can't use it for backwards compatibility
 */
class OldCombineTextInputFormat extends OldCombineFileInputFormat[LongWritable, Text] {
  override def getRecordReader(split: OldInputSplit, conf: JobConf, reporter: Reporter)
  : OldRecordReader[LongWritable, Text] = {
    new OldCombineFileRecordReader[LongWritable, Text](conf,
      split.asInstanceOf[OldCombineFileSplit], reporter, classOf[OldCombineTextRecordReaderWrapper]
        .asInstanceOf[Class[OldRecordReader[LongWritable, Text]]])
  }
}

class OldCombineTextRecordReaderWrapper(
    split: OldCombineFileSplit,
    conf: Configuration,
    reporter: Reporter,
    idx: Integer) extends OldRecordReader[LongWritable, Text] {

  val fileSplit = new OldFileSplit(split.getPath(idx),
    split.getOffset(idx),
    split.getLength(idx),
    split.getLocations())

  val delegate: OldLineRecordReader = new OldTextInputFormat().getRecordReader(fileSplit,
    conf.asInstanceOf[JobConf], reporter).asInstanceOf[OldLineRecordReader]

  override def next(key: LongWritable, value: Text): Boolean = delegate.next(key, value)
  override def createKey(): LongWritable = delegate.createKey()
  override def createValue(): Text = delegate.createValue()
  override def getPos(): Long = delegate.getPos
  override def close(): Unit = delegate.close()
  override def getProgress(): Float = delegate.getProgress
}

/**
 * Hadoop 2 has a version of this, but we can't use it for backwards compatibility
  *Hadoop 2有一个版本,但是我们不能使用它来向后兼容
 */
class NewCombineTextInputFormat extends NewCombineFileInputFormat[LongWritable, Text] {
  def createRecordReader(split: NewInputSplit, context: TaskAttemptContext)
  : NewRecordReader[LongWritable, Text] = {
    new NewCombineFileRecordReader[LongWritable, Text](split.asInstanceOf[NewCombineFileSplit],
      context, classOf[NewCombineTextRecordReaderWrapper])
  }
}

class NewCombineTextRecordReaderWrapper(
    split: NewCombineFileSplit,
    context: TaskAttemptContext,
    idx: Integer) extends NewRecordReader[LongWritable, Text] {

  val fileSplit = new NewFileSplit(split.getPath(idx),
    split.getOffset(idx),
    split.getLength(idx),
    split.getLocations())

  val delegate = new NewTextInputFormat().createRecordReader(fileSplit, context)

  override def initialize(split: NewInputSplit, context: TaskAttemptContext): Unit = {
    delegate.initialize(fileSplit, context)
  }

  override def nextKeyValue(): Boolean = delegate.nextKeyValue()
  override def getCurrentKey(): LongWritable = delegate.getCurrentKey
  override def getCurrentValue(): Text = delegate.getCurrentValue
  override def getProgress(): Float = delegate.getProgress
  override def close(): Unit = delegate.close()
}

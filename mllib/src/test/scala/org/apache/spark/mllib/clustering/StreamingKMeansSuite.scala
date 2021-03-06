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

package org.apache.spark.mllib.clustering

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.streaming.{StreamingContext, TestSuiteBase}
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.util.random.XORShiftRandom
/**
 * 流式k-means:当数据到达,我们可能想要动态地估算cluster,并更新他们,该算法采用了小批量的k-means更新规则,对每一次
 * 数据,将所有的点分配到最近的cluster,并计算最新cluster中心,然后更新每个cluster
 */
class StreamingKMeansSuite extends SparkFunSuite with TestSuiteBase {

  override def maxWaitTimeMillis: Int = 30000

  var ssc: StreamingContext = _

  override def afterFunction() {
    super.afterFunction()
    if (ssc != null) {
      ssc.stop()
    }
  }
  //单中心和等价于大平均的准确性
  test("accuracy for single center and equivalence to grand average") {
    // set parameters 设置参数
    val numBatches = 10
    val numPoints = 50
    val k = 1
    val d = 5
    val r = 0.1

    // create model with one cluster
    //创建一个聚类模型
    val model = new StreamingKMeans()
      .setK(1)//聚类的个数
      .setDecayFactor(1.0)
      .setInitialCenters(Array(Vectors.dense(0.0, 0.0, 0.0, 0.0, 0.0)), Array(0.0))

    // generate random data for k-means
    //为k-均值生成随机数据
    val (input, centers) = StreamingKMeansDataGenerator(numPoints, numBatches, k, d, r, 42)

    // setup and run the model training
    //建立和运行训练模型
    ssc = setupStreams(input, (inputDStream: DStream[Vector]) => {
      model.trainOn(inputDStream)
      inputDStream.count()
    })
    runStreams(ssc, numBatches, numBatches)

    // estimated center should be close to true center
    //估计值中心接近的中心值
    assert(centers(0) ~== model.latestModel().clusterCenters(0) absTol 1E-1)

    // estimated center from streaming should exactly match the arithmetic mean of all data points
    // because the decay factor is set to 1.0
    //估计中心流应该完全匹配的所有数据点算术平均值,因为衰减因子被设置为1
    val grandMean =
      input.flatten.map(x => x.toBreeze).reduce(_ + _) / (numBatches * numPoints).toDouble
       //clusterCenters聚类中心点
    assert(model.latestModel().clusterCenters(0) ~== Vectors.dense(grandMean.toArray) absTol 1E-5)
  }

  test("accuracy for two centers") { //两个中心的精度
    val numBatches = 10
    val numPoints = 5
    val k = 2
    val d = 5
    val r = 0.1

    // create model with two clusters
    //创建两个集群的模型
    val kMeans = new StreamingKMeans()
      .setK(2)//聚类的个数
      .setHalfLife(2, "batches")
      .setInitialCenters(
        Array(Vectors.dense(-0.1, 0.1, -0.2, -0.3, -0.1),
          Vectors.dense(0.1, -0.2, 0.0, 0.2, 0.1)),
        Array(5.0, 5.0))

    // generate random data for k-means
    //为k-均值生成随机数据
    val (input, centers) = StreamingKMeansDataGenerator(numPoints, numBatches, k, d, r, 42)

    // setup and run the model training
    //建立和运行训练模型
    ssc = setupStreams(input, (inputDStream: DStream[Vector]) => {
      kMeans.trainOn(inputDStream)
      inputDStream.count()
    })
    runStreams(ssc, numBatches, numBatches)

    // check that estimated centers are close to true centers
    //检查估计的中心是接近真正的中心
    // NOTE exact assignment depends on the initialization!
    //注意精确分配取决于初始化  clusterCenters聚类中心点
    assert(centers(0) ~== kMeans.latestModel().clusterCenters(0) absTol 1E-1)
    assert(centers(1) ~== kMeans.latestModel().clusterCenters(1) absTol 1E-1)
  }

  test("detecting dying clusters") {//检测死的聚类检测
    val numBatches = 10
    val numPoints = 5
    val k = 1
    val d = 1
    val r = 1.0

    // create model with two clusters
    //创建两个集群的模型
    val kMeans = new StreamingKMeans()
      .setK(2)//聚类的个数
      .setHalfLife(0.5, "points")
      .setInitialCenters(
        Array(Vectors.dense(0.0), Vectors.dense(1000.0)),
        Array(1.0, 1.0))

    // new data are all around the first cluster 0.0
    //新的数据都围绕第一个群集0
    val (input, _) =
      StreamingKMeansDataGenerator(numPoints, numBatches, k, d, r, 42, Array(Vectors.dense(0.0)))

    // setup and run the model training
    //建立和运行训练模型
    ssc = setupStreams(input, (inputDStream: DStream[Vector]) => {
      kMeans.trainOn(inputDStream)
      inputDStream.count()
    })
    runStreams(ssc, numBatches, numBatches)

    // check that estimated centers are close to true centers
    //检查估计中心靠近真正的中心
    // NOTE exact assignment depends on the initialization!
    //注意精确分配取决于初始化
    val model = kMeans.latestModel()
     //clusterCenters聚类中心点
    val c0 = model.clusterCenters(0)(0)
    val c1 = model.clusterCenters(1)(0)
    //应该有一个正的中心和一个负的中心
    assert(c0 * c1 < 0.0, "should have one positive center and one negative center")
    // 0.8 is the mean of half-normal distribution
    //0.8是半正态分布的平均值,math.abs返回数的绝对值
    assert(math.abs(c0) ~== 0.8 absTol 0.6)
    assert(math.abs(c1) ~== 0.8 absTol 0.6)
  }

  test("SPARK-7946 setDecayFactor") {//设置衰减因子
    val kMeans = new StreamingKMeans()
    assert(kMeans.decayFactor === 1.0)
    kMeans.setDecayFactor(2.0)
    assert(kMeans.decayFactor === 2.0)
  }

  def StreamingKMeansDataGenerator(
      numPoints: Int,
      numBatches: Int,
      k: Int,
      d: Int,
      r: Double,
      seed: Int,
      initCenters: Array[Vector] = null): (IndexedSeq[IndexedSeq[Vector]], Array[Vector]) = {
    val rand = new XORShiftRandom(seed)
    val centers = initCenters match {
      case null => Array.fill(k)(Vectors.dense(Array.fill(d)(rand.nextGaussian())))
      case _ => initCenters
    }
    val data = (0 until numBatches).map { i =>
      (0 until numPoints).map { idx =>
        val center = centers(idx % k)
        Vectors.dense(Array.tabulate(d)(x => center(x) + rand.nextGaussian() * r))
      }
    }
    (data, centers)
  }
}

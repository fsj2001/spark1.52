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

// scalastyle:off println
package org.apache.spark.examples

import java.util.Random

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._

/**
  * Usage: GroupByTest [numMappers] [numKVPairs] [KeySize] [numReducers]
  * 用法:
  */
object GroupByTest {
  def main(args: Array[String]) {
    val sparkConf = new SparkConf().setAppName("GroupBy Test").setMaster("local")
    var numMappers = if (args.length > 0) args(0).toInt else 2
    var numKVPairs = if (args.length > 1) args(1).toInt else 1000
    var valSize = if (args.length > 2) args(2).toInt else 1000
    var numReducers = if (args.length > 3) args(3).toInt else numMappers

    val sc = new SparkContext(sparkConf)
    //parallelize() 产生最初的 ParrallelCollectionRDD，每个 partition 包含一个整数 i
    //执行 RDD 上的 transformation 操作（这里是 flatMap）以后，生成 FlatMappedRDD，其中每个 partition 包含一个 Array[(Int, Array[Byte])]。
    val pairs1 = sc.parallelize(0 until numMappers, numMappers).flatMap { p =>
      val ranGen = new Random
      var arr1 = new Array[(Int, Array[Byte])](numKVPairs)
      for (i <- 0 until numKVPairs) {
        val byteArr = new Array[Byte](valSize)
        ranGen.nextBytes(byteArr)//用于生成随机字节并将其置于用户提供的字节数组
        //nextInt用于获取一个伪随机,在0(包括)和指定值(不包括),从此随机数生成器的序列中取出均匀分布的int值
        arr1(i) = (ranGen.nextInt(Int.MaxValue), byteArr)
      }
      arr1
      //由于 FlatMappedRDD 被 cache 到内存
    }.cache()
    // Enforce that everything has been calculated and in cache
    //执行所有的计算和缓存
    //第一个count()执行时,先在每个partition上执行count,然后执行结果被发送到driver,最后在driver端进行sum
    //产生了两个 job,第一个job由第一个action(也就是pairs1.count)触发产生
    pairs1.count()
    //groupByKey 产生了后面两个 RDD
    //在一个由(K,V)对组成的数据集上调用,返回一个(K,Seq[V])对的数据集
    //第二个 job 由 pairs1.groupByKey(numReducers).count 触发产生
    println(pairs1.groupByKey(numReducers).count())

    sc.stop()
  }
}
// scalastyle:on println
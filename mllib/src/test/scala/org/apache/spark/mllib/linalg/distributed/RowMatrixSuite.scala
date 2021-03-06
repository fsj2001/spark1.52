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

package org.apache.spark.mllib.linalg.distributed

import scala.util.Random

import breeze.numerics.abs
import breeze.linalg.{ DenseVector => BDV, DenseMatrix => BDM, norm => brzNorm, svd => brzSvd }

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.linalg.{ Matrices, Vectors, Vector }
import org.apache.spark.mllib.util.{ LocalClusterSparkContext, MLlibTestSparkContext }
/**
 * 行矩阵(RowMatrix)按行分布式存储,无行索引,底层支撑结构是多行数据组成的RDD,每行是一个局部向量.
 */
class RowMatrixSuite extends SparkFunSuite with MLlibTestSparkContext {
  /**
   * 行矩阵(RowMatrix)按行分布式存储,无行索引,由多行数据组成的RDD,每一行是一个特征向量
   */
  val m = 4
  val n = 3
  val arr = Array(0.0, 3.0, 6.0, 9.0, 1.0, 4.0, 7.0, 0.0, 2.0, 5.0, 8.0, 1.0)
  //稠密矩阵:非0元素数目占大多数
  val denseData = Seq(
    Vectors.dense(0.0, 1.0, 2.0),
    Vectors.dense(3.0, 4.0, 5.0),
    Vectors.dense(6.0, 7.0, 8.0),
    Vectors.dense(9.0, 0.0, 1.0))
  //稀疏矩阵:数值为0的元素数目远远多于非0元素的数目时
  val sparseData = Seq(
    Vectors.sparse(3, Seq((1, 1.0), (2, 2.0))),
    Vectors.sparse(3, Seq((0, 3.0), (1, 4.0), (2, 5.0))),
    Vectors.sparse(3, Seq((0, 6.0), (1, 7.0), (2, 8.0))),
    Vectors.sparse(3, Seq((0, 9.0), (2, 1.0))))
 //主成分分析
  val principalComponents = BDM(//DenseMatrix
    (0.0, 1.0, 0.0),
    //math.sqrt返回数字的平方根
    (math.sqrt(2.0) / 2.0, 0.0, math.sqrt(2.0) / 2.0),
    (math.sqrt(2.0) / 2.0, 0.0, -math.sqrt(2.0) / 2.0))
   //行矩阵(RowMatrix)按行分布式存储,无行索引,底层支撑结构是多行数据组成的RDD,每行是一个局部向量
  var denseMat: RowMatrix = _
  var sparseMat: RowMatrix = _

  override def beforeAll() {
    super.beforeAll()
    //行矩阵按行分布式存储,这个时候行号没有意义,
    //特征向量集就可以表示为行矩阵,通过RDD来支撑矩阵的部分行,每行是一个局部向量
    denseMat = new RowMatrix(sc.parallelize(denseData, 2))
    //稀疏矩阵
    sparseMat = new RowMatrix(sc.parallelize(sparseData, 2))
  }

  test("size") {
    assert(denseMat.numRows() === m)//3行
    assert(denseMat.numCols() === n)//4列
    assert(sparseMat.numRows() === m)
    assert(sparseMat.numCols() === n)
  }

  test("empty rows") {//空行
    val rows = sc.parallelize(Seq[Vector](), 1)
    //行矩阵(RowMatrix)按行分布式存储,无行索引,底层支撑结构是多行数据组成的RDD,每行是一个局部向量
    val emptyMat = new RowMatrix(rows)
    intercept[RuntimeException] {
      emptyMat.numCols()
    }
    intercept[RuntimeException] {
      emptyMat.numRows()
    }
  }

  test("toBreeze") {
    val expected = BDM(//DenseMatrix
      (0.0, 1.0, 2.0),
      (3.0, 4.0, 5.0),
      (6.0, 7.0, 8.0),
      (9.0, 0.0, 1.0))
    for (mat <- Seq(denseMat, sparseMat)) {
      assert(mat.toBreeze() === expected)
    }
  }

  test("gram") {//
    val expected =
      Matrices.dense(n, n, Array(126.0, 54.0, 72.0, 54.0, 66.0, 78.0, 72.0, 78.0, 94.0))
    for (mat <- Seq(denseMat, sparseMat)) {
      val G = mat.computeGramianMatrix()//
      assert(G.toBreeze === expected.toBreeze)
    }
  }

  test("similar columns") {//相似的列
    //sqrt 平方根函数
    val colMags = Vectors.dense(math.sqrt(126), math.sqrt(66), math.sqrt(94))
    val expected = BDM(//DenseMatrix
      (0.0, 54.0, 72.0),
      (0.0, 0.0, 78.0),
      (0.0, 0.0, 0.0))

    for (i <- 0 until n; j <- 0 until n) {
      expected(i, j) /= (colMags(i) * colMags(j))
    }

    for (mat <- Seq(denseMat, sparseMat)) {
     //columnSimilarities计算矩阵中每两列之间的余弦相似度,
     //参数:使用近似算法的阈值,值越大则运算速度越快而误差越大
      val G = mat.columnSimilarities(0.11).toBreeze()
      for (i <- 0 until n; j <- 0 until n) {
        if (expected(i, j) > 0) {
          val actual = expected(i, j)
          val estimate = G(i, j)
	       //math.abs返回数的绝对值
          assert(math.abs(actual - estimate) / actual < 0.2,
            s"Similarities not close enough: $actual vs $estimate")
        }
      }
    }

    for (mat <- Seq(denseMat, sparseMat)) {
     //columnSimilarities计算矩阵中每两列之间的余弦相似度
     //参数:使用近似算法的阈值,值越大则运算速度越快而误差越大
      val G = mat.columnSimilarities()
      assert(closeToZero(G.toBreeze() - expected))
    }

    for (mat <- Seq(denseMat, sparseMat)) {
      val G = mat.columnSimilaritiesDIMSUM(colMags.toArray, 150.0)
      assert(closeToZero(G.toBreeze() - expected))
    }
  }

  test("svd of a full-rank matrix") {//一个秩矩阵的奇异值分解
    for (mat <- Seq(denseMat, sparseMat)) {
      for (mode <- Seq("auto", "local-svd", "local-eigs", "dist-eigs")) {
        val localMat = mat.toBreeze()//svd
               val brzSvd.SVD(localU, localSigma, localVt) = brzSvd(localMat)
        val localV: BDM[Double] = localVt.t.toDenseMatrix
        for (k <- 1 to n) {
          val skip = (mode == "local-eigs" || mode == "dist-eigs") && k == n
          if (!skip) {
            val svd = mat.computeSVD(k, computeU = true, 1e-9, 300, 1e-10, mode)
            val U = svd.U
            val s = svd.s
            val V = svd.V
            assert(U.numRows() === m)
            assert(U.numCols() === k)
            assert(s.size === k)
            assert(V.numRows === n)
            assert(V.numCols === k)
            assertColumnEqualUpToSign(U.toBreeze(), localU, k)
            assertColumnEqualUpToSign(V.toBreeze.asInstanceOf[BDM[Double]], localV, k)
            assert(closeToZero(s.toBreeze.asInstanceOf[BDV[Double]] - localSigma(0 until k)))
          }
        }
        val svdWithoutU = mat.computeSVD(1, computeU = false, 1e-9, 300, 1e-10, mode)
        assert(svdWithoutU.U === null)
      }
    }
  }

  test("svd of a low-rank matrix") {//低秩矩阵的SVD
    val rows = sc.parallelize(Array.fill(4)(Vectors.dense(1.0, 1.0, 1.0)), 2)
    //行矩阵(RowMatrix)按行分布式存储,无行索引,底层支撑结构是多行数据组成的RDD,每行是一个局部向量
    val mat = new RowMatrix(rows, 4, 3)
    for (mode <- Seq("auto", "local-svd", "local-eigs", "dist-eigs")) {
     //第一个参数3意味着取top 2个奇异值,第二个参数true意味着计算矩阵U,第三个参数意味小于1e-6d的奇异值将被抛弃
      val svd = mat.computeSVD(2, computeU = true, 1e-6, 300, 1e-10, mode)
      assert(svd.s.size === 1, s"should not return zero singular values but got ${svd.s}")
      assert(svd.U.numRows() === 4)
      assert(svd.U.numCols() === 1)
      assert(svd.V.numRows === 3)
      assert(svd.V.numCols === 1)
    }
  }

  test("validate k in svd") {
    for (mat <- Seq(denseMat, sparseMat)) {
      intercept[IllegalArgumentException] {
        mat.computeSVD(-1) //计算svd值
      }
    }
  }

  def closeToZero(G: BDM[Double]): Boolean = {
   //math.abs返回数的绝对值
    G.valuesIterator.map(math.abs).sum < 1e-6
  }

  def closeToZero(v: BDV[Double]): Boolean = {
    brzNorm(v, 1.0) < 1e-6
  }

  def assertColumnEqualUpToSign(A: BDM[Double], B: BDM[Double], k: Int) {
    assert(A.rows === B.rows)
    for (j <- 0 until k) {
      val aj = A(::, j)
      val bj = B(::, j)
      assert(closeToZero(aj - bj) || closeToZero(aj + bj),
        s"The $j-th columns mismatch: $aj and $bj")
    }
  }

  test("pca") {//主成分析
    for (mat <- Seq(denseMat, sparseMat); k <- 1 to n) {
      val pc = denseMat.computePrincipalComponents(k)
      assert(pc.numRows === n)
      assert(pc.numCols === k)
      assertColumnEqualUpToSign(pc.toBreeze.asInstanceOf[BDM[Double]], principalComponents, k)
    }
  }

  test("multiply a local matrix") {//乘一个局部矩阵
    val B = Matrices.dense(n, 2, Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
    for (mat <- Seq(denseMat, sparseMat)) {
      val AB = mat.multiply(B)
      assert(AB.numRows() === m)
      assert(AB.numCols() === 2)
      assert(AB.rows.collect().toSeq === Seq(
        Vectors.dense(5.0, 14.0),
        Vectors.dense(14.0, 50.0),
        Vectors.dense(23.0, 86.0),
        Vectors.dense(2.0, 32.0)))
    }
  }

  test("compute column summary statistics") {//计算列汇总统计
    for (mat <- Seq(denseMat, sparseMat)) {
      val summary = mat.computeColumnSummaryStatistics()
      // Run twice to make sure no internal states are changed.
      //运行两次,以确保没有内部状态被更改
      for (k <- 0 to 1) {
        assert(summary.mean === Vectors.dense(4.5, 3.0, 4.0), "mean mismatch")
        assert(summary.variance === Vectors.dense(15.0, 10.0, 10.0), "variance mismatch")
        assert(summary.count === m, "count mismatch.")
        assert(summary.numNonzeros === Vectors.dense(3.0, 3.0, 4.0), "nnz mismatch")
        assert(summary.max === Vectors.dense(9.0, 7.0, 8.0), "max mismatch")
        assert(summary.min === Vectors.dense(0.0, 0.0, 1.0), "column mismatch.")
	      //math.sqrt返回数字的平方根
        assert(summary.normL2 === Vectors.dense(math.sqrt(126), math.sqrt(66), math.sqrt(94)),
          "magnitude mismatch.")
        assert(summary.normL1 === Vectors.dense(18.0, 12.0, 16.0), "L1 norm mismatch")
      }
    }
  }

  test("QR Decomposition") {//QR分解
    for (mat <- Seq(denseMat, sparseMat)) {
      val result = mat.tallSkinnyQR(true)
      val expected = breeze.linalg.qr.reduced(mat.toBreeze())
      val calcQ = result.Q
      val calcR = result.R
      assert(closeToZero(abs(expected.q) - abs(calcQ.toBreeze())))
      assert(closeToZero(abs(expected.r) - abs(calcR.toBreeze.asInstanceOf[BDM[Double]])))
      assert(closeToZero(calcQ.multiply(calcR).toBreeze - mat.toBreeze()))
      // Decomposition without computing Q 没有计算的分解
      val rOnly = mat.tallSkinnyQR(computeQ = false)
      assert(rOnly.Q == null)
      assert(closeToZero(abs(expected.r) - abs(rOnly.R.toBreeze.asInstanceOf[BDM[Double]])))
    }
  }
}

class RowMatrixClusterSuite extends SparkFunSuite with LocalClusterSparkContext {
//行矩阵(RowMatrix)按行分布式存储,无行索引,底层支撑结构是多行数据组成的RDD,每行是一个局部向量
  var mat: RowMatrix = _

  override def beforeAll() {
    super.beforeAll()
    val m = 4
    val n = 200000
    //mapPartitionsWithIndex的func接受两个参数,第一个参数是分区的索引,第二个是一个数据集分区的迭代器
    val rows = sc.parallelize(0 until m, 2).mapPartitionsWithIndex { (idx, iter) =>
      val random = new Random(idx)
      iter.map(i => Vectors.dense(Array.fill(n)(random.nextDouble())))
    }
    //行矩阵(RowMatrix)按行分布式存储,无行索引,底层支撑结构是多行数据组成的RDD,每行是一个局部向量
    mat = new RowMatrix(rows)
  }

 /* test("task size should be small in svd") {//任务应该是小规模的奇异值分解
    //第一个参数1意味着取top 1个奇异值,第二个参数true意味着计算矩阵U
    val svd = mat.computeSVD(1, computeU = true)
  }*/

  /*test("task size should be small in summarize") {//任务的大小应该是小的总结
    val summary = mat.computeColumnSummaryStatistics()
  }*/
}

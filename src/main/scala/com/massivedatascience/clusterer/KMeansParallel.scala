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
 *
 * This code is a modified version of the original Spark 1.0.2 implementation.
 */

package com.massivedatascience.clusterer

import com.massivedatascience.clusterer.util.{SparkHelper, XORShiftRandom}

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ArrayBuffer


class KMeansParallel(
  numClusters: Int,
  r: Int,
  initializationSteps: Int,
  seedx: Int,
  initial: Option[Array[Array[BregmanCenter]]] = None)
  extends KMeansInitializer with SparkHelper with WeightedSelector {

  def init(pointOps: BregmanPointOps, data: RDD[BregmanPoint]): Array[Array[BregmanCenter]] = {

    implicit val sc = data.sparkContext

    /**
     * Initialize `runs` sets of cluster centers using the k-means|| algorithm by Bahmani et al.
     * (Bahmani et al., Scalable K-Means++, VLDB 2012). This is a variant of k-means++ that tries
     * to find  dissimilar cluster centers by starting with a random center and then doing
     * passes where more centers are chosen with probability proportional to their squared distance
     * to the current cluster set. It results in a provable approximation to an optimal clustering.
     *
     * The original paper can be found at http://theory.stanford.edu/~sergei/papers/vldb12-kmpar.pdf.
     *
     * @return
     */

    val runs = r

    // Initialize empty centers and point costs.
    val centers = Array.tabulate(runs)(r => ArrayBuffer.empty[BregmanCenter])
    var costs = sync("pre-costs", data.map(_ => Vectors.dense(Array.fill(runs)(Double.PositiveInfinity))))

    val seed = new XORShiftRandom(seedx).nextInt()
    val newCenters: Array[ArrayBuffer[BregmanCenter]] = initial.map(_.map(new ArrayBuffer[BregmanCenter] ++= _)).getOrElse(initialCenters(seed, pointOps, data, runs))

    /** Merges new centers to centers. */
    def mergeNewCenters(): Unit = {
      var r = 0
      while (r < runs) {
        centers(r) ++= newCenters(r)
        newCenters(r).clear()
        r += 1
      }
    }

    // On each step, sample 2 * k points on average for each run with probability proportional
    // to their squared distance from that run's centers. Note that only distances between points
    // and new centers are computed in each iteration.
    var step = 0

    while (step < initializationSteps) {
      logInfo(s"starting step $step")
      assert(data.getStorageLevel.useMemory)

      val (newCosts, additionalCenters) = select(pointOps, data, 2 * numClusters, seed ^ (step << 16), runs, costs, newCenters)
      costs.unpersist(blocking = false)
      costs = newCosts
      costs.persist()

      logInfo(s"merging centers")
      mergeNewCenters()
      additionalCenters.foreach { case (index, center) =>
        newCenters(index) += center
      }
      step += 1
    }

    mergeNewCenters()
    costs.unpersist(blocking = false)
    logInfo("creating final centers")

    val centerArrays = centers.map(_.toArray)

    val weightMap = withBroadcast(centerArrays) { bcCenters =>
      // for each (run, cluster) compute the sum of the weights of the points in the cluster
      data.flatMap { point =>
        val centers = bcCenters.value
        Array.tabulate(centers.length)(r => ((r, pointOps.findClosestCluster(centers(r), point)), point.weight))
      }.reduceByKeyLocally(_ + _)
    }

    val kMeansPlusPlus = new KMeansPlusPlus(pointOps)

    Array.tabulate(centerArrays.length) { r =>
      val myCenters = centerArrays(r)
      logInfo(s"run $r has ${myCenters.length} centers")
      val weights = Array.tabulate(myCenters.length)(i => weightMap.getOrElse((r, i), 0.0))
      val kx = if (numClusters > myCenters.length) myCenters.length else numClusters
      kMeansPlusPlus.getCenters(seed, myCenters, weights, kx, 1)
    }
  }

  def initialCenters(seed: Int, pointOps: BregmanPointOps, data: RDD[BregmanPoint], runs: Int): Array[ArrayBuffer[BregmanCenter]] = {
    // Initialize each run's first center to a random point.
    val sample = data.takeSample(withReplacement = true, runs, seed).map(pointOps.toCenter)
    Array.tabulate(runs)(r => ArrayBuffer(sample(r)))
  }
}

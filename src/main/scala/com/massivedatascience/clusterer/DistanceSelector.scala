package com.massivedatascience.clusterer

import com.massivedatascience.clusterer.util.SparkHelper
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import scala.collection.Map


/**
 * Select k points randomly, weighted by their distance to the closest cluster center.
 * @param k number of points to select
 * @param seed  random number seed
 * @return  array of sets of cluster centers
 */
class DistanceSelector(k: Int, seed: Int) extends SparkHelper {

  /**
   * @param pointOps  distance function
   * @param data  original points
   * @param centerArrays arrays of centers
   * @return  array of sets of cluster centers
   */
  def cluster(
    pointOps: BregmanPointOps,
    data: RDD[BregmanPoint],
    centerArrays: Array[Array[BregmanCenter]]): Array[Array[BregmanCenter]] = {

    implicit val sc = data.sparkContext

    val weightMap: Map[(Int, Int), Double] = withBroadcast(centerArrays) { bcCenters =>
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
      val kx = if (k > myCenters.length) myCenters.length else k
      kMeansPlusPlus.getCenters(seed, myCenters, weights, kx, 1)
    }
  }
}
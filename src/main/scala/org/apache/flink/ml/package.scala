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

package org.apache.flink

import org.apache.flink.api.common.functions.{RichFlatMapFunction, RichFilterFunction, RichMapFunction}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.operators.DataSink
import org.apache.flink.api.scala.{DataSet, ExecutionEnvironment}
import org.apache.flink.configuration.Configuration
import org.apache.flink.ml.common.LabeledVector
import org.apache.flink.ml.statistics.ContinuousHistogram
import org.apache.flink.util.Collector

import scala.reflect.ClassTag
import scala.collection.JavaConverters._

package object ml {

  /** Pimp my [[ExecutionEnvironment]] to directly support `readLibSVM`
    *
    * @param executionEnvironment
    */
  implicit class RichExecutionEnvironment(executionEnvironment: ExecutionEnvironment) {
    def readLibSVM(path: String): DataSet[LabeledVector] = {
      MLUtils.readLibSVM(executionEnvironment, path)
    }
  }

  /** Pimp my [[DataSet]] to directly support `writeAsLibSVM`
    *
    * @param dataSet
    */
  implicit class RichLabeledDataSet(dataSet: DataSet[LabeledVector]) {
    def writeAsLibSVM(path: String): DataSink[String] = {
      MLUtils.writeLibSVM(path, dataSet)
    }
  }

  /** Pimp my [[DataSet]] to support histogram creation
    *
    * @param dataSet
    */
  implicit class RichDoubleDataSet(dataSet: DataSet[Double]) {

    /** Creates a [[ContinuousHistogram]] from a [[DataSet]]
      *
      * @param bins Number of bins required
      */
    def createHistogram(bins: Int): DataSet[ContinuousHistogram] = {
      require(bins > 0, "Number of bins must be positive")
      MLUtils.createContinuousHistogram(dataSet, bins)
    }
  }

  implicit class RichDataSet[T](dataSet: DataSet[T]) {
    def mapWithBcVariable[B, O: TypeInformation: ClassTag](
        broadcastVariable: DataSet[B])(
        fun: (T, B) => O)
      : DataSet[O] = {
      dataSet.map(new BroadcastSingleElementMapper[T, B, O](dataSet.clean(fun)))
        .withBroadcastSet(broadcastVariable, "broadcastVariable")
    }

    def flatMapWithBcVariable[B, O: TypeInformation: ClassTag](
        broadcastVariable: DataSet[B])(
        fun: (T, B) => List[O])
      : DataSet[O] = {
      dataSet.flatMap(new BroadcastSingleElementFlatMapper[T, B, O](dataSet.clean(fun)))
        .withBroadcastSet(broadcastVariable, "broadcastVariable")
    }

    def mapWithBcSet[B, O: TypeInformation: ClassTag](
        broadcastVariable: DataSet[B])(
        fun: (T, Seq[B]) => O)
      : DataSet[O] = {
      dataSet.map(new BroadcastSingleSetMapper[T, B, O](dataSet.clean(fun)))
        .withBroadcastSet(broadcastVariable, "broadcastVariable")
    }

    def filterWithBcVariable[B, O](broadcastVariable: DataSet[B])(fun: (T, B) => Boolean)
      : DataSet[T] = {
      dataSet.filter(new BroadcastSingleElementFilter[T, B](dataSet.clean(fun)))
        .withBroadcastSet(broadcastVariable, "broadcastVariable")
    }

    def mapWithBcVariableIteration[B, O: TypeInformation: ClassTag](
        broadcastVariable: DataSet[B])(fun: (T, B, Int) => O)
      : DataSet[O] = {
      dataSet.map(new BroadcastSingleElementMapperWithIteration[T, B, O](dataSet.clean(fun)))
        .withBroadcastSet(broadcastVariable, "broadcastVariable")
    }
  }

  private class BroadcastSingleElementMapper[T, B, O](
      fun: (T, B) => O)
    extends RichMapFunction[T, O] {
    var broadcastVariable: B = _

    @throws(classOf[Exception])
    override def open(configuration: Configuration): Unit = {
      broadcastVariable = getRuntimeContext.getBroadcastVariable[B]("broadcastVariable").get(0)
    }

    override def map(value: T): O = {
      fun(value, broadcastVariable)
    }
  }

  private class BroadcastSingleElementFlatMapper[T, B, O](
      fun: (T, B) => List[O])
    extends RichFlatMapFunction[T, O] {
    var broadcastVariable: B = _

    @throws(classOf[Exception])
    override def open(configuration: Configuration): Unit = {
      broadcastVariable = getRuntimeContext.getBroadcastVariable[B]("broadcastVariable").get(0)
    }

    override def flatMap(value: T, out: Collector[O]): Unit = {
      fun(value, broadcastVariable).iterator.foreach(output => out.collect(output))
    }
  }

  private class BroadcastSingleSetMapper[T, B, O](
      fun: (T, Seq[B]) => O)
    extends RichMapFunction[T, O] {
    var broadcastVariable: Seq[B] = _

    @throws(classOf[Exception])
    override def open(configuration: Configuration): Unit = {
      broadcastVariable = getRuntimeContext.getBroadcastVariable[B]("broadcastVariable").asScala
    }

    override def map(value: T): O = {
      fun(value, broadcastVariable)
    }
  }

  private class BroadcastSingleElementMapperWithIteration[T, B, O](
      fun: (T, B, Int) => O)
    extends RichMapFunction[T, O] {
    var broadcastVariable: B = _

    @throws(classOf[Exception])
    override def open(configuration: Configuration): Unit = {
      broadcastVariable = getRuntimeContext.getBroadcastVariable[B]("broadcastVariable").get(0)
    }

    override def map(value: T): O = {
      fun(value, broadcastVariable, getIterationRuntimeContext.getSuperstepNumber)
    }
  }

  private class BroadcastSingleElementFilter[T, B](
      fun: (T, B) => Boolean)
    extends RichFilterFunction[T] {
    var broadcastVariable: B = _

    @throws(classOf[Exception])
    override def open(configuration: Configuration): Unit = {
      broadcastVariable = getRuntimeContext.getBroadcastVariable[B]("broadcastVariable").get(0)
    }

    override def filter(value: T): Boolean = {
      fun(value, broadcastVariable)
    }
  }
}

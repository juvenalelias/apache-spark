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

import java.io.IOException

import com.google.common.base.Objects
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.hive.common.StatsSetupConst
import org.apache.hadoop.hive.ql.metadata.{Partition, Table => HiveTable}
import org.apache.hadoop.hive.ql.plan.TableDesc

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.CatalystConf
import org.apache.spark.sql.catalyst.analysis.MultiInstanceRelation
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.{AttributeMap, AttributeReference, Expression}
import org.apache.spark.sql.catalyst.plans.logical.{LeafNode, LogicalPlan, Statistics}
import org.apache.spark.sql.execution.FileRelation
import org.apache.spark.sql.hive.client.HiveClientImpl
import org.apache.spark.sql.types.StructField


private[hive] case class MetastoreRelation(
    databaseName: String,
    tableName: String)
    (val catalogTable: CatalogTable,
     @transient private val sparkSession: SparkSession)
  extends LeafNode with MultiInstanceRelation with FileRelation with CatalogRelation {

  override def equals(other: Any): Boolean = other match {
    case relation: MetastoreRelation =>
      databaseName == relation.databaseName &&
        tableName == relation.tableName &&
        output == relation.output
    case _ => false
  }

  override def hashCode(): Int = {
    Objects.hashCode(databaseName, tableName, output)
  }

  override protected def otherCopyArgs: Seq[AnyRef] = catalogTable :: sparkSession :: Nil

  @transient val hiveQlTable: HiveTable = HiveClientImpl.toHiveTable(catalogTable)

  @transient override def computeStats(conf: CatalystConf): Statistics = {
    catalogTable.stats.map(_.toPlanStats(output)).getOrElse(Statistics(
      sizeInBytes = {
        val totalSize = hiveQlTable.getParameters.get(StatsSetupConst.TOTAL_SIZE)
        val rawDataSize = hiveQlTable.getParameters.get(StatsSetupConst.RAW_DATA_SIZE)
        // TODO: check if this estimate is valid for tables after partition pruning.
        // NOTE: getting `totalSize` directly from params is kind of hacky, but this should be
        // relatively cheap if parameters for the table are populated into the metastore.
        // Besides `totalSize`, there are also `numFiles`, `numRows`, `rawDataSize` keys
        // (see StatsSetupConst in Hive) that we can look at in the future.
        BigInt(
          // When table is external,`totalSize` is always zero, which will influence join strategy
          // so when `totalSize` is zero, use `rawDataSize` instead
          // when `rawDataSize` is also zero, use `HiveExternalCatalog.STATISTICS_TOTAL_SIZE`,
          // which is generated by analyze command.
          if (totalSize != null && totalSize.toLong > 0L) {
            totalSize.toLong
          } else if (rawDataSize != null && rawDataSize.toLong > 0) {
            rawDataSize.toLong
          } else if (sparkSession.sessionState.conf.fallBackToHdfsForStatsEnabled) {
            try {
              val hadoopConf = sparkSession.sessionState.newHadoopConf()
              val fs: FileSystem = hiveQlTable.getPath.getFileSystem(hadoopConf)
              fs.getContentSummary(hiveQlTable.getPath).getLength
            } catch {
              case e: IOException =>
                logWarning("Failed to get table size from hdfs.", e)
                sparkSession.sessionState.conf.defaultSizeInBytes
            }
          } else {
            sparkSession.sessionState.conf.defaultSizeInBytes
          })
      }
    ))
  }

  // When metastore partition pruning is turned off, we cache the list of all partitions to
  // mimic the behavior of Spark < 1.5
  private lazy val allPartitions: Seq[CatalogTablePartition] = {
    sparkSession.sharedState.externalCatalog.listPartitions(
      catalogTable.database,
      catalogTable.identifier.table)
  }

  def getHiveQlPartitions(predicates: Seq[Expression] = Nil): Seq[Partition] = {
    val rawPartitions = if (sparkSession.sessionState.conf.metastorePartitionPruning) {
      sparkSession.sharedState.externalCatalog.listPartitionsByFilter(
        catalogTable.database,
        catalogTable.identifier.table,
        predicates,
        sparkSession.sessionState.conf.sessionLocalTimeZone)
    } else {
      allPartitions
    }

    rawPartitions.map(HiveClientImpl.toHivePartition(_, hiveQlTable))
  }

  /** Only compare database and tablename, not alias. */
  override def sameResult(plan: LogicalPlan): Boolean = {
    plan.canonicalized match {
      case mr: MetastoreRelation =>
        mr.databaseName == databaseName && mr.tableName == tableName
      case _ => false
    }
  }

  val tableDesc = new TableDesc(
    hiveQlTable.getInputFormatClass,
    // The class of table should be org.apache.hadoop.hive.ql.metadata.Table because
    // getOutputFormatClass will use HiveFileFormatUtils.getOutputFormatSubstitute to
    // substitute some output formats, e.g. substituting SequenceFileOutputFormat to
    // HiveSequenceFileOutputFormat.
    hiveQlTable.getOutputFormatClass,
    hiveQlTable.getMetadata
  )

  implicit class SchemaAttribute(f: StructField) {
    def toAttribute: AttributeReference = AttributeReference(
      f.name,
      f.dataType,
      // Since data can be dumped in randomly with no validation, everything is nullable.
      nullable = true
    )(qualifier = Some(tableName))
  }

  /** PartitionKey attributes */
  val partitionKeys = catalogTable.partitionSchema.map(_.toAttribute)

  /** Non-partitionKey attributes */
  val dataColKeys = catalogTable.schema
    .filter { c => !catalogTable.partitionColumnNames.contains(c.name) }
    .map(_.toAttribute)

  val output = dataColKeys ++ partitionKeys

  /** An attribute map that can be used to lookup original attributes based on expression id. */
  val attributeMap = AttributeMap(output.map(o => (o, o)))

  /** An attribute map for determining the ordinal for non-partition columns. */
  val columnOrdinals = AttributeMap(dataColKeys.zipWithIndex)

  override def inputFiles: Array[String] = {
    val partLocations = allPartitions
      .flatMap(_.storage.locationUri)
      .toArray
    if (partLocations.nonEmpty) {
      partLocations
    } else {
      Array(
        catalogTable.storage.locationUri.getOrElse(
          sys.error(s"Could not get the location of ${catalogTable.qualifiedName}.")))
    }
  }

  override def newInstance(): MetastoreRelation = {
    MetastoreRelation(databaseName, tableName)(catalogTable, sparkSession)
  }
}

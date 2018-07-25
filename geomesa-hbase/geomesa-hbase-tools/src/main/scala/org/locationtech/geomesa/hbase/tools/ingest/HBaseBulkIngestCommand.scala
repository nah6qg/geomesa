/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.tools.ingest

import com.beust.jcommander.Parameters
import com.typesafe.config.Config
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.Job
import org.locationtech.geomesa.hbase.data.HBaseDataStore
import org.locationtech.geomesa.hbase.jobs.HBaseIndexFileMapper
import org.locationtech.geomesa.hbase.tools.ingest.HBaseBulkIngestCommand.HBaseBulkIngestParams
import org.locationtech.geomesa.hbase.tools.ingest.HBaseIngestCommand.HBaseIngestParams
import org.locationtech.geomesa.tools.ingest.AbstractIngest.StatusCallback
import org.locationtech.geomesa.tools.ingest.{ConverterIngest, ConverterIngestJob}
import org.locationtech.geomesa.tools.{Command, OutputPathParam, RequiredIndexParam}
import org.locationtech.geomesa.utils.index.IndexMode
import org.opengis.feature.simple.SimpleFeatureType

class HBaseBulkIngestCommand extends HBaseIngestCommand {

  override val name = "bulk-ingest"
  override val params = new HBaseBulkIngestParams()

  override protected def createConverterIngest(sft: SimpleFeatureType, converterConfig: Config): Runnable = {
    import scala.collection.JavaConverters._

    new ConverterIngest(sft, connection, converterConfig, params.files.asScala, Option(params.mode),
      libjarsFile, libjarsPaths, params.threads) {

      override def run(): Unit = {
        super.run()
        Command.user.info("To load files, run:\n\tgeomesa-hbase bulk-load " +
            s"-c ${params.catalog} -f ${sft.getTypeName} --index ${params.index} --input ${params.outputPath}")
      }

      override def runDistributedJob(statusCallback: StatusCallback): (Long, Long) = {
        // validate index param now that we have a datastore and the sft has been created
        val index = params.loadRequiredIndex(ds.asInstanceOf[HBaseDataStore], IndexMode.Write).identifier
        val job = new ConverterIngestJob(dsParams, sft, converterConfig, inputs, libjarsFile, libjarsPaths) {
          override def configureJob(job: Job): Unit = {
            super.configureJob(job)
            HBaseIndexFileMapper.configure(job, connection, sft.getTypeName, index, new Path(params.outputPath))
          }
        }
        job.run(statusCallback)
      }

      override protected def runLocal(): Unit =
        throw new NotImplementedError("Bulk ingest not implemented for local mode")
    }
  }
}

object HBaseBulkIngestCommand {
  @Parameters(commandDescription = "Convert various file formats into HBase HFiles suitable for incremental load")
  class HBaseBulkIngestParams extends HBaseIngestParams with RequiredIndexParam with OutputPathParam
}

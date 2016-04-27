/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.genomics.dataflow.writers.bam;

import com.google.api.services.storage.Storage;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Max;
import com.google.cloud.dataflow.sdk.transforms.Min;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.util.GcsUtil;
import com.google.cloud.dataflow.sdk.util.Transport;
import com.google.cloud.dataflow.sdk.util.gcsfs.GcsPath;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.cloud.genomics.dataflow.readers.bam.BAMIO;
import com.google.cloud.genomics.dataflow.readers.bam.HeaderInfo;
import com.google.cloud.genomics.dataflow.utils.GCSOptions;
import com.google.cloud.genomics.dataflow.utils.GCSOutputOptions;
import com.google.common.base.Stopwatch;

import htsjdk.samtools.BAMShardIndexer;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.ValidationStringency;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Writes a shard of BAM index (BAI file).
 * The input is a reference name to process and the output is the name of the file written.
 * A side output under the tag NO_COORD_READS_COUNT_TAG is a single value of the number of
 * no-coordinate reads in this shard. This is needed since the final index has to include a
 * total number summed up from the shards.
 */
public class WriteBAIFn extends DoFn<String, String> {
  private static final Logger LOG = Logger.getLogger(WriteBAIFn.class.getName());

  public static interface Options extends GCSOutputOptions {}


  public static TupleTag<Long> NO_COORD_READS_COUNT_TAG = new TupleTag<Long>(){};
  public static TupleTag<String> WRITTEN_BAI_NAMES_TAG = new TupleTag<String>(){};

  PCollectionView<String> writtenBAMFilerView;
  PCollectionView<HeaderInfo> headerView;
  PCollectionView<Iterable<KV<Integer, Long>>> sequenceShardSizesView;

  Aggregator<Long, Long> readCountAggregator;
  Aggregator<Long, Long> noCoordReadCountAggregator;
  Aggregator<Integer, Integer> initializedShardCount;
  Aggregator<Integer, Integer> finishedShardCount;
  Aggregator<Long, Long> shardTimeMaxSec;
  Aggregator<Long, Long> shardTimeMinSec;
  Aggregator<Long, Long> shardReadCountMax;
  Aggregator<Long, Long> shardReadCountMin;

  public WriteBAIFn(PCollectionView<HeaderInfo> headerView,
      PCollectionView<String> writtenBAMFilerView,
      PCollectionView<Iterable<KV<Integer, Long>>> sequenceShardSizesView) {
    this.writtenBAMFilerView = writtenBAMFilerView;
    this.headerView = headerView;
    this.sequenceShardSizesView = sequenceShardSizesView;

    readCountAggregator = createAggregator("Indexed reads", new Sum.SumLongFn());
    noCoordReadCountAggregator = createAggregator("Indexed no coordinate reads", new Sum.SumLongFn());
    initializedShardCount = createAggregator("Initialized Indexing Shard Count", new Sum.SumIntegerFn());
    finishedShardCount = createAggregator("Finished Indexing Shard Count", new Sum.SumIntegerFn());
    shardTimeMaxSec = createAggregator("Maximum Indexing Shard Processing Time (sec)", new Max.MaxLongFn());
    shardTimeMinSec = createAggregator("Minimum Indexing Shard Processing Time (sec)", new Min.MinLongFn());
    shardReadCountMax = createAggregator("Maximum Reads Per Indexing Shard", new Max.MaxLongFn());
    shardReadCountMin = createAggregator("Minimum Reads Per Indexing Shard", new Min.MinLongFn());
  }

  @Override
  public void processElement(DoFn<String, String>.ProcessContext c) throws Exception {
    initializedShardCount.addValue(1);
    Stopwatch stopWatch = Stopwatch.createStarted();

    final HeaderInfo header = c.sideInput(headerView);
    final String bamFilePath = c.sideInput(writtenBAMFilerView);
    final Iterable<KV<Integer, Long>> sequenceShardSizes = c.sideInput(sequenceShardSizesView);

    final String sequenceName = c.element();
    final int sequenceIndex = header.header.getSequence(sequenceName).getSequenceIndex();
    final String baiFilePath = bamFilePath + "-" +
        String.format("%02d",sequenceIndex) + "-" + sequenceName + ".bai";

    long offset = 0;
    int skippedReferences  = 0;
    long bytesToProcess = 0;

    for (KV<Integer, Long> sequenceShardSize : sequenceShardSizes) {
      if (sequenceShardSize.getKey() < sequenceIndex) {
        offset += sequenceShardSize.getValue();
        skippedReferences++;
      } else if (sequenceShardSize.getKey() == sequenceIndex) {
        bytesToProcess = sequenceShardSize.getValue();
      }
    }
    LOG.info("Generating BAI index: " + baiFilePath);
    LOG.info("Reading BAM file: " + bamFilePath + " for reference " + sequenceName +
        ", skipping " + skippedReferences + " references at offset " + offset +
        ", expecting to process " + bytesToProcess + " bytes");

    Options options = c.getPipelineOptions().as(Options.class);
    final Storage.Objects storage = Transport.newStorageClient(
        options
          .as(GCSOptions.class))
          .build()
          .objects();
    final SamReader reader = BAMIO.openBAM(storage, bamFilePath, ValidationStringency.SILENT, true,
        offset);
    final OutputStream outputStream =
        Channels.newOutputStream(
            new GcsUtil.GcsUtilFactory().create(options)
              .create(GcsPath.fromUri(baiFilePath),
                  BAMIO.BAM_INDEX_FILE_MIME_TYPE));
    final BAMShardIndexer indexer = new BAMShardIndexer(outputStream, reader.getFileHeader(), sequenceIndex);

    long processedReads = 0;
    long skippedReads = 0;

    // create and write the content
    if (bytesToProcess > 0) {
      SAMRecordIterator it = reader.iterator();
      boolean foundRecords = false;
      while (it.hasNext()) {
        SAMRecord r = it.next();
        if (!r.getReferenceName().equals(sequenceName)) {
          if (foundRecords) {
            LOG.info("Finishing index building for " + sequenceName + " after processing " + processedReads);
            break;
          }
          skippedReads++;
          continue;
        } else if (!foundRecords) {
          LOG.info("Found records for refrence " + sequenceName + " after skipping " + skippedReads);
          foundRecords = true;
        }
        indexer.processAlignment(r);
        processedReads++;
      }
      it.close();
    } else {
      LOG.info("No records for refrence " + sequenceName + ": writing empty index ");
    }
    long noCoordinateReads = indexer.finish();
    c.output(baiFilePath);
    c.sideOutput(NO_COORD_READS_COUNT_TAG, noCoordinateReads);
    LOG.info("Generated " + baiFilePath + ", " + processedReads + " reads, " +
        noCoordinateReads + " no coordinate reads, " + skippedReads + ", skipped reads");
    stopWatch.stop();
    shardTimeMaxSec.addValue(stopWatch.elapsed(TimeUnit.SECONDS));
    shardTimeMinSec.addValue(stopWatch.elapsed(TimeUnit.SECONDS));
    finishedShardCount.addValue(1);
    readCountAggregator.addValue(processedReads);
    noCoordReadCountAggregator.addValue(noCoordinateReads);
    shardReadCountMax.addValue(processedReads);
    shardReadCountMin.addValue(processedReads);
  }
}


/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.metadata;

import org.apache.hudi.avro.model.HoodieMetadataColumnStats;
import org.apache.hudi.common.bloom.BloomFilter;
import org.apache.hudi.common.config.SerializableConfiguration;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodiePartitionMetadata;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordGlobalLocation;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieMetadataException;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation of {@link HoodieTableMetadata} based file-system-backed table metadata.
 */
public class FileSystemBackedTableMetadata implements HoodieTableMetadata {

  private static final int DEFAULT_LISTING_PARALLELISM = 1500;

  private final transient HoodieEngineContext engineContext;
  private final SerializableConfiguration hadoopConf;
  private final String datasetBasePath;
  private final boolean assumeDatePartitioning;

  public FileSystemBackedTableMetadata(HoodieEngineContext engineContext, SerializableConfiguration conf, String datasetBasePath,
                                       boolean assumeDatePartitioning) {
    this.engineContext = engineContext;
    this.hadoopConf = conf;
    this.datasetBasePath = datasetBasePath;
    this.assumeDatePartitioning = assumeDatePartitioning;
  }

  @Override
  public FileStatus[] getAllFilesInPartition(Path partitionPath) throws IOException {
    FileSystem fs = partitionPath.getFileSystem(hadoopConf.get());
    return FSUtils.getAllDataFilesInPartition(fs, partitionPath);
  }

  @Override
  public List<String> getAllPartitionPaths() throws IOException {
    Path basePath = new Path(datasetBasePath);
    if (assumeDatePartitioning) {
      FileSystem fs = basePath.getFileSystem(hadoopConf.get());
      return FSUtils.getAllPartitionFoldersThreeLevelsDown(fs, datasetBasePath);
    }

    return getPartitionPathWithPathPrefixes(Collections.singletonList(""));
  }

  @Override
  public List<String> getPartitionPathWithPathPrefixes(List<String> relativePathPrefixes) {
    return relativePathPrefixes.stream().flatMap(relativePathPrefix -> {
      try {
        return getPartitionPathWithPathPrefix(relativePathPrefix).stream();
      } catch (IOException e) {
        throw new HoodieIOException("Error fetching partition paths with relative path: " + relativePathPrefix, e);
      }
    }).collect(Collectors.toList());
  }

  private List<String> getPartitionPathWithPathPrefix(String relativePathPrefix) throws IOException {
    List<Path> pathsToList = new CopyOnWriteArrayList<>();
    pathsToList.add(StringUtils.isNullOrEmpty(relativePathPrefix)
        ? new Path(datasetBasePath) : new Path(datasetBasePath, relativePathPrefix));
    List<String> partitionPaths = new CopyOnWriteArrayList<>();

    while (!pathsToList.isEmpty()) {
      // TODO: Get the parallelism from HoodieWriteConfig
      int listingParallelism = Math.min(DEFAULT_LISTING_PARALLELISM, pathsToList.size());

      // List all directories in parallel:
      // if current dictionary contains PartitionMetadata, add it to result
      // if current dictionary does not contain PartitionMetadata, add its subdirectory to queue to be processed.
      engineContext.setJobStatus(this.getClass().getSimpleName(), "Listing all partitions with prefix " + relativePathPrefix);
      // result below holds a list of pair. first entry in the pair optionally holds the deduced list of partitions.
      // and second entry holds optionally a directory path to be processed further.
      List<Pair<Option<String>, Option<Path>>> result = engineContext.flatMap(pathsToList, path -> {
        FileSystem fileSystem = path.getFileSystem(hadoopConf.get());
        if (HoodiePartitionMetadata.hasPartitionMetadata(fileSystem, path)) {
          return Stream.of(Pair.of(Option.of(FSUtils.getRelativePartitionPath(new Path(datasetBasePath), path)), Option.empty()));
        }
        return Arrays.stream(fileSystem.listStatus(path, p -> {
          try {
            return fileSystem.isDirectory(p) && !p.getName().equals(HoodieTableMetaClient.METAFOLDER_NAME);
          } catch (IOException e) {
            // noop
          }
          return false;
        })).map(status -> Pair.of(Option.empty(), Option.of(status.getPath())));
      }, listingParallelism);
      pathsToList.clear();

      partitionPaths.addAll(result.stream().filter(entry -> entry.getKey().isPresent()).map(entry -> entry.getKey().get())
          .collect(Collectors.toList()));

      pathsToList.addAll(result.stream().filter(entry -> entry.getValue().isPresent()).map(entry -> entry.getValue().get())
          .collect(Collectors.toList()));
    }
    return partitionPaths;
  }

  @Override
  public Map<String, FileStatus[]> getAllFilesInPartitions(Collection<String> partitionPaths)
      throws IOException {
    if (partitionPaths == null || partitionPaths.isEmpty()) {
      return Collections.emptyMap();
    }

    int parallelism = Math.min(DEFAULT_LISTING_PARALLELISM, partitionPaths.size());

    engineContext.setJobStatus(this.getClass().getSimpleName(), "Listing all files in " + partitionPaths.size() + " partitions");
    List<Pair<String, FileStatus[]>> partitionToFiles = engineContext.map(new ArrayList<>(partitionPaths), partitionPathStr -> {
      Path partitionPath = new Path(partitionPathStr);
      FileSystem fs = partitionPath.getFileSystem(hadoopConf.get());
      return Pair.of(partitionPathStr, FSUtils.getAllDataFilesInPartition(fs, partitionPath));
    }, parallelism);

    return partitionToFiles.stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
  }

  @Override
  public Option<String> getSyncedInstantTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Option<String> getLatestCompactionTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws Exception {
    // no-op
  }

  @Override
  public void reset() {
    // no-op
  }

  public Option<BloomFilter> getBloomFilter(final String partitionName, final String fileName)
      throws HoodieMetadataException {
    throw new HoodieMetadataException("Unsupported operation: getBloomFilter for " + fileName);
  }

  @Override
  public Map<Pair<String, String>, BloomFilter> getBloomFilters(final List<Pair<String, String>> partitionNameFileNameList)
      throws HoodieMetadataException {
    throw new HoodieMetadataException("Unsupported operation: getBloomFilters!");
  }

  @Override
  public Map<Pair<String, String>, HoodieMetadataColumnStats> getColumnStats(final List<Pair<String, String>> partitionNameFileNameList, final String columnName)
      throws HoodieMetadataException {
    throw new HoodieMetadataException("Unsupported operation: getColumnsStats!");
  }

  @Override
  public HoodieData<HoodieRecord<HoodieMetadataPayload>> getRecordsByKeyPrefixes(List<String> keyPrefixes, String partitionName, boolean shouldLoadInMemory) {
    throw new HoodieMetadataException("Unsupported operation: getRecordsByKeyPrefixes!");
  }

  @Override
  public Map<String, HoodieRecordGlobalLocation> readRecordIndex(List<String> recordKeys) {
    throw new HoodieMetadataException("Unsupported operation: readRecordIndex!");
  }

  @Override
  public int getNumFileGroupsForPartition(MetadataPartitionType partition) {
    throw new HoodieMetadataException("Unsupported operation: getNumFileGroupsForPartition");
  }
}

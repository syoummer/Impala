// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import static com.cloudera.impala.thrift.ImpalaInternalServiceConstants.DEFAULT_PARTITION_ID;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.BlockStorageLocation;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.VolumeId;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.avro.AvroSerdeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.LiteralExpr;
import com.cloudera.impala.analysis.NullLiteral;
import com.cloudera.impala.analysis.PartitionKeyValue;
import com.cloudera.impala.catalog.HdfsPartition.FileBlock;
import com.cloudera.impala.catalog.HdfsPartition.FileDescriptor;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.FileSystemUtil;
import com.cloudera.impala.thrift.ImpalaInternalServiceConstants;
import com.cloudera.impala.thrift.TAccessLevel;
import com.cloudera.impala.thrift.TCatalogObjectType;
import com.cloudera.impala.thrift.TColumn;
import com.cloudera.impala.thrift.THdfsFileBlock;
import com.cloudera.impala.thrift.THdfsPartition;
import com.cloudera.impala.thrift.THdfsTable;
import com.cloudera.impala.thrift.TNetworkAddress;
import com.cloudera.impala.thrift.TPartitionKeyValue;
import com.cloudera.impala.thrift.TResultSet;
import com.cloudera.impala.thrift.TResultSetMetadata;
import com.cloudera.impala.thrift.TTable;
import com.cloudera.impala.thrift.TTableDescriptor;
import com.cloudera.impala.thrift.TTableType;
import com.cloudera.impala.util.AvroSchemaParser;
import com.cloudera.impala.util.FsPermissionChecker;
import com.cloudera.impala.util.HdfsCachingUtil;
import com.cloudera.impala.util.ListMap;
import com.cloudera.impala.util.MetaStoreUtil;
import com.cloudera.impala.util.TAccessLevelUtil;
import com.cloudera.impala.util.TResultRowBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Internal representation of table-related metadata of an hdfs-resident table.
 * Owned by Catalog instance.
 * The partition keys constitute the clustering columns.
 *
 * This class is not thread-safe due to the static counter variable inside HdfsPartition.
 * Also not thread safe because of possibility of concurrent modifications to the list of
 * partitions in methods addPartition and dropPartition.
 */
public class HdfsTable extends Table {
  // hive's default value for table property 'serialization.null.format'
  private static final String DEFAULT_NULL_COLUMN_VALUE = "\\N";

  // Number of times to retry fetching the partitions from the HMS should an error occur.
  private final static int NUM_PARTITION_FETCH_RETRIES = 5;;

  // string to indicate NULL. set in load() from table properties
  private String nullColumnValue_;

  // hive uses this string for NULL partition keys. Set in load().
  private String nullPartitionKeyValue_;

  // Avro schema of this table if this is an Avro table, otherwise null. Set in load().
  private String avroSchema_ = null;

  // True if this table's metadata is marked as cached. Does not necessarily mean the
  // data is cached or that all/any partitions are cached.
  private boolean isMarkedCached_ = false;

  private static boolean hasLoggedDiskIdFormatWarning_ = false;

  private final List<HdfsPartition> partitions_; // these are only non-empty partitions

  // Array of sorted maps storing the association between partition values and
  // partition ids. There is one sorted map per partition key.
  private final ArrayList<TreeMap<LiteralExpr, HashSet<Long>>> partitionValuesMap_ =
      Lists.newArrayList();

  // Array of partition id sets that correspond to partitions with null values
  // in the partition keys; one set per partition key.
  private final ArrayList<HashSet<Long>> nullPartitionIds_ = Lists.newArrayList();

  // Map of partition ids to HdfsPartitions. Used for speeding up partition
  // pruning.
  private final HashMap<Long, HdfsPartition> partitionMap_ = Maps.newHashMap();

  // Store all the partition ids of an HdfsTable.
  private final HashSet<Long> partitionIds_ = Sets.newHashSet();

  // Flag to indicate if the HdfsTable has the partition metadata populated.
  private boolean hasPartitionMd_ = false;

  // Bi-directional map between an integer index and a unique datanode
  // TNetworkAddresses, each of which contains blocks of 1 or more
  // files in this table. The network addresses are stored using IP
  // address as the host name. Each FileBlock specifies a list of
  // indices within this hostIndex_ to specify which nodes contain
  // replicas of the block.
  private ListMap<TNetworkAddress> hostIndex_ = new ListMap<TNetworkAddress>();

  // Map of parent directory (partition location) to list of files (FileDescriptors)
  // under that directory. Used to look up/index all files in the table.
  private final Map<String, List<FileDescriptor>> fileDescMap_ = Maps.newHashMap();

  // Total number of Hdfs files in this table. Set in load().
  private long numHdfsFiles_;

  // Sum of sizes of all Hdfs files in this table. Set in load().
  private long totalHdfsBytes_;

  // Base Hdfs directory where files of this table are stored.
  // For unpartitioned tables it is simply the path where all files live.
  // For partitioned tables it is the root directory
  // under which partition dirs are placed.
  protected String hdfsBaseDir_;

  private final static Logger LOG = LoggerFactory.getLogger(HdfsTable.class);

  // Caching this configuration object makes calls to getFileSystem much quicker
  // (saves ~50ms on a standard plan)
  // TODO(henry): confirm that this is thread safe - cursory inspection of the class
  // and its usage in getFileSystem suggests it should be.
  private static final Configuration CONF = new Configuration();

  private static final DistributedFileSystem DFS;

  private static final boolean SUPPORTS_VOLUME_ID;

  static {
    try {
      // call newInstance() instead of using a shared instance from a cache
      // to avoid accidentally having it closed by someone else
      FileSystem fs = FileSystem.newInstance(FileSystem.getDefaultUri(CONF), CONF);
      if (!(fs instanceof DistributedFileSystem)) {
        String error = "Cannot connect to HDFS. " +
            CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY +
            "(" + CONF.get(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY) + ")" +
            " might be set incorrectly";
        throw new RuntimeException(error);
      }
      DFS = (DistributedFileSystem) fs;
    } catch (IOException e) {
      throw new RuntimeException("couldn't retrieve FileSystem:\n" + e.getMessage(), e);
    }

    SUPPORTS_VOLUME_ID =
        CONF.getBoolean(DFSConfigKeys.DFS_HDFS_BLOCKS_METADATA_ENABLED,
                        DFSConfigKeys.DFS_HDFS_BLOCKS_METADATA_ENABLED_DEFAULT);
  }

  /**
   * Returns a disk id (0-based) index from the Hdfs VolumeId object.
   * There is currently no public API to get at the volume id. We'll have to get it
   * by accessing the internals.
   */
  private static int getDiskId(VolumeId hdfsVolumeId) {
    // Initialize the diskId as -1 to indicate it is unknown
    int diskId = -1;

    if (hdfsVolumeId != null && hdfsVolumeId.isValid()) {
      // TODO: this is a hack and we'll have to address this by getting the
      // public API. Also, we need to be very mindful of this when we change
      // the version of HDFS.
      String volumeIdString = hdfsVolumeId.toString();
      // This is the hacky part. The toString is currently the underlying id
      // encoded in base64.
      byte[] volumeIdBytes = Base64.decodeBase64(volumeIdString);
      if (volumeIdBytes.length == 4) {
        diskId = Bytes.toInt(volumeIdBytes);
      } else if (!hasLoggedDiskIdFormatWarning_) {
        LOG.warn("wrong disk id format: " + volumeIdString);
        hasLoggedDiskIdFormatWarning_ = true;
      }
    }
    return diskId;
  }

  public Map<String, List<FileDescriptor>> getFileDescMap() { return fileDescMap_; }

  /**
   * Loads the file block metadata for the given collection of FileDescriptors.
   * The FileDescriptors are passed as a Map of partition location to list of
   * files that exist under that directory.
   */
  private void loadBlockMd(Map<String, List<FileDescriptor>> fileDescriptors)
      throws RuntimeException {
    Preconditions.checkNotNull(fileDescriptors);
    LOG.debug("load block md for " + name_);

    // Store all BlockLocations so they can be reused when loading the disk IDs.
    List<BlockLocation> blockLocations = Lists.newArrayList();

    // loop over all files and record their block metadata, minus volume ids
    for (String parentPath: fileDescriptors.keySet()) {
      for (FileDescriptor fileDescriptor: fileDescriptors.get(parentPath)) {
        Path p = new Path(parentPath, fileDescriptor.getFileName());
        BlockLocation[] locations = null;
        try {
          FileStatus fileStatus = DFS.getFileStatus(p);
          // fileDescriptors should not contain directories.
          Preconditions.checkArgument(!fileStatus.isDirectory());
          locations = DFS.getFileBlockLocations(fileStatus, 0, fileStatus.getLen());
          Preconditions.checkNotNull(locations);
          blockLocations.addAll(Arrays.asList(locations));

          // Loop over all blocks in the file.
          for (BlockLocation block: locations) {
            String[] blockHostPorts = block.getNames();
            try {
              blockHostPorts = block.getNames();
            } catch (IOException e) {
              // this shouldn't happen, getNames() doesn't throw anything
              String errorMsg = "BlockLocation.getNames() failed:\n" + e.getMessage();
              LOG.error(errorMsg);
              throw new IllegalStateException(errorMsg);
            }
            // Now enumerate all replicas of the block, adding any unknown hosts
            // to hostIndex_ and the index for that host to replicaHostIdxs.
            List<Integer> replicaHostIdxs = new ArrayList<Integer>(blockHostPorts.length);
            for (int i = 0; i < blockHostPorts.length; ++i) {
              String[] ip_port = blockHostPorts[i].split(":");
              Preconditions.checkState(ip_port.length == 2);
              TNetworkAddress network_address = new TNetworkAddress(ip_port[0],
                  Integer.parseInt(ip_port[1]));
              replicaHostIdxs.add(hostIndex_.getIndex(network_address));
            }
            fileDescriptor.addFileBlock(
                new FileBlock(block.getOffset(), block.getLength(), replicaHostIdxs));
          }
        } catch (IOException e) {
          throw new RuntimeException("couldn't determine block locations for path '"
              + p + "':\n" + e.getMessage(), e);
        }
      }
    }

    if (SUPPORTS_VOLUME_ID) {
      LOG.trace("loading disk ids for: " + getFullName() +
          ". nodes: " + getNumNodes());
      loadDiskIds(blockLocations, fileDescriptors);
      LOG.trace("completed load of disk ids for: " + getFullName());
    }
  }

  /**
   * Populates disk/volume ID metadata inside FileDescriptors given a list of
   * BlockLocations. The FileDescriptors are passed as a Map of parent directory
   * (partition location) to list of files (FileDescriptors) under that directory.
   */
  private void loadDiskIds(List<BlockLocation> blockLocations,
      Map<String, List<FileDescriptor>> fileDescriptors) {
    // BlockStorageLocations for all the blocks
    // block described by blockMetadataList[i] is located at locations[i]
    BlockStorageLocation[] locations = null;
    try {
      // Get the BlockStorageLocations for all the blocks
      locations = DFS.getFileBlockStorageLocations(blockLocations);
    } catch (IOException e) {
      LOG.error("Couldn't determine block storage locations:\n" + e.getMessage());
      return;
    }

    if (locations == null || locations.length == 0) {
      LOG.warn("Attempted to get block locations but the call returned nulls");
      return;
    }

    if (locations.length != blockLocations.size()) {
      // blocks and locations don't match up
      LOG.error("Number of block locations not equal to number of blocks: "
          + "#locations=" + Long.toString(locations.length)
          + " #blocks=" + Long.toString(blockLocations.size()));
      return;
    }

    int locationsIdx = 0;
    int unknownDiskIdCount = 0;
    for (String parentPath: fileDescriptors.keySet()) {
      for (FileDescriptor fileDescriptor: fileDescriptors.get(parentPath)) {
        for (THdfsFileBlock blockMd: fileDescriptor.getFileBlocks()) {
          VolumeId[] volumeIds = locations[locationsIdx++].getVolumeIds();
          // Convert opaque VolumeId to 0 based ids.
          // TODO: the diskId should be eventually retrievable from Hdfs when
          // the community agrees this API is useful.
          int[] diskIds = new int[volumeIds.length];
          for (int i = 0; i < volumeIds.length; ++i) {
            diskIds[i] = getDiskId(volumeIds[i]);
            if (diskIds[i] < 0) ++unknownDiskIdCount;
          }
          FileBlock.setDiskIds(diskIds, blockMd);
        }
      }
      if (unknownDiskIdCount > 0) {
        LOG.warn("unknown disk id count " + unknownDiskIdCount);
      }
    }
  }

  protected HdfsTable(TableId id, org.apache.hadoop.hive.metastore.api.Table msTbl,
      Db db, String name, String owner) {
    super(id, msTbl, db, name, owner);
    this.partitions_ = Lists.newArrayList();
  }

  @Override
  public TCatalogObjectType getCatalogObjectType() { return TCatalogObjectType.TABLE; }
  public List<HdfsPartition> getPartitions() {
    return new ArrayList<HdfsPartition>(partitions_);
  }
  public boolean isMarkedCached() { return isMarkedCached_; }

  public HashMap<Long, HdfsPartition> getPartitionMap() { return partitionMap_; }
  public HashSet<Long> getNullPartitionIds(int i) { return nullPartitionIds_.get(i); }
  public HashSet<Long> getPartitionIds() { return partitionIds_; }
  public TreeMap<LiteralExpr, HashSet<Long>> getPartitionValueMap(int i) {
    return partitionValuesMap_.get(i);
  }

  /**
   * Returns the value Hive is configured to use for NULL partition key values.
   * Set during load.
   */
  public String getNullPartitionKeyValue() { return nullPartitionKeyValue_; }
  public String getNullColumnValue() { return nullColumnValue_; }

  /*
   * Returns the storage location (HDFS path) of this table.
   */
  public String getLocation() { return super.getMetaStoreTable().getSd().getLocation(); }

  // True if Impala has HDFS write permissions on the hdfsBaseDir (for an unpartitioned
  // table) or if Impala has write permissions on all partition directories (for
  // a partitioned table).
  public boolean hasWriteAccess() {
    return TAccessLevelUtil.impliesWriteAccess(accessLevel_);
  }

  /**
   * Returns the first location (HDFS path) that Impala does not have WRITE access
   * to, or an null if none is found. For an unpartitioned table, this just
   * checks the hdfsBaseDir. For a partitioned table it checks all partition directories.
   */
  public String getFirstLocationWithoutWriteAccess() {
    if (getMetaStoreTable() == null) return null;

    if (getMetaStoreTable().getPartitionKeysSize() == 0) {
      if (!TAccessLevelUtil.impliesWriteAccess(accessLevel_)) {
        return hdfsBaseDir_;
      }
    } else {
      for (HdfsPartition partition: partitions_) {
        if (!TAccessLevelUtil.impliesWriteAccess(partition.getAccessLevel())) {
          return partition.getLocation();
        }
      }
    }
    return null;
  }

  /**
   * Gets the HdfsPartition matching the given partition spec. Returns null if no match
   * was found.
   */
  public HdfsPartition getPartition(List<PartitionKeyValue> partitionSpec) {
    List<TPartitionKeyValue> partitionKeyValues = Lists.newArrayList();
    for (PartitionKeyValue kv: partitionSpec) {
      String value = PartitionKeyValue.getPartitionKeyValueString(
          kv.getLiteralValue(), getNullPartitionKeyValue());
      partitionKeyValues.add(new TPartitionKeyValue(kv.getColName(), value));
    }
    return getPartitionFromThriftPartitionSpec(partitionKeyValues);
  }

  /**
   * Gets the HdfsPartition matching the Thrift version of the partition spec.
   * Returns null if no match was found.
   */
  public HdfsPartition getPartitionFromThriftPartitionSpec(
      List<TPartitionKeyValue> partitionSpec) {
    // First, build a list of the partition values to search for in the same order they
    // are defined in the table.
    List<String> targetValues = Lists.newArrayList();
    Set<String> keys = Sets.newHashSet();
    for (FieldSchema fs: getMetaStoreTable().getPartitionKeys()) {
      for (TPartitionKeyValue kv: partitionSpec) {
        if (fs.getName().toLowerCase().equals(kv.getName().toLowerCase())) {
          targetValues.add(kv.getValue().toLowerCase());
          // Same key was specified twice
          if (!keys.add(kv.getName().toLowerCase())) {
            return null;
          }
        }
      }
    }

    // Make sure the number of values match up and that some values were found.
    if (targetValues.size() == 0 ||
        (targetValues.size() != getMetaStoreTable().getPartitionKeysSize())) {
      return null;
    }

    // Now search through all the partitions and check if their partition key values match
    // the values being searched for.
    for (HdfsPartition partition: getPartitions()) {
      if (partition.getId() == ImpalaInternalServiceConstants.DEFAULT_PARTITION_ID) {
        continue;
      }
      List<LiteralExpr> partitionValues = partition.getPartitionValues();
      Preconditions.checkState(partitionValues.size() == targetValues.size());
      boolean matchFound = true;
      for (int i = 0; i < targetValues.size(); ++i) {
        String value;
        if (partitionValues.get(i) instanceof NullLiteral) {
          value = getNullPartitionKeyValue();
        } else {
          value = partitionValues.get(i).getStringValue();
          Preconditions.checkNotNull(value);
          // See IMPALA-252: we deliberately map empty strings on to
          // NULL when they're in partition columns. This is for
          // backwards compatibility with Hive, and is clearly broken.
          if (value.isEmpty()) value = getNullPartitionKeyValue();
        }
        if (!targetValues.get(i).equals(value.toLowerCase())) {
          matchFound = false;
          break;
        }
      }
      if (matchFound) {
        return partition;
      }
    }
    return null;
  }

  /**
   * Create columns corresponding to fieldSchemas, including column statistics.
   * Throws a TableLoadingException if the metadata is incompatible with what we
   * support.
   */
  private void loadColumns(List<FieldSchema> fieldSchemas, HiveMetaStoreClient client)
      throws TableLoadingException {
    int pos = 0;
    for (FieldSchema s: fieldSchemas) {
      Type type = parseColumnType(s);
      // Check if we support partitioning on columns of such a type.
      if (pos < numClusteringCols_ && !type.supportsTablePartitioning()) {
        throw new TableLoadingException(
            String.format("Failed to load metadata for table '%s' because of " +
                "unsupported partition-column type '%s' in partition column '%s'",
                getName(), type.toString(), s.getName()));
      }

      Column col = new Column(s.getName(), type, s.getComment(), pos);
      addColumn(col);
      ++pos;

      // Load and set column stats in col.
      loadColumnStats(col, client);
    }
  }

  /**
   * Populate the partition metadata of an HdfsTable.
   */
  private void populatePartitionMd() {
    if (hasPartitionMd_) return;
    for (HdfsPartition partition: partitions_) {
      updatePartitionMdAndColStats(partition);
    }
    hasPartitionMd_ = true;
  }

  /**
   * Clear the partition metadata of an HdfsTable including column stats.
   */
  private void resetPartitionMd() {
    partitionIds_.clear();
    partitionMap_.clear();
    partitionValuesMap_.clear();
    nullPartitionIds_.clear();
    // Initialize partitionValuesMap_ and nullPartitionIds_. Also reset column stats.
    for (int i = 0; i < numClusteringCols_; ++i) {
      getColumns().get(i).getStats().setNumNulls(0);
      getColumns().get(i).getStats().setNumDistinctValues(0);
      partitionValuesMap_.add(Maps.<LiteralExpr, HashSet<Long>>newTreeMap());
      nullPartitionIds_.add(Sets.<Long>newHashSet());
    }
    hasPartitionMd_ = false;
  }

  /**
   * Create HdfsPartition objects corresponding to 'partitions'.
   *
   * If there are no partitions in the Hive metadata, a single partition is added with no
   * partition keys.
   *
   * For files that have not been changed, reuses file descriptors from oldFileDescMap.
   *
   * TODO: If any partition fails to load, the entire table will fail to load. Instead,
   * we should consider skipping partitions that cannot be loaded and raise a warning
   * whenever the table is accessed.
   */
  private void loadPartitions(
      List<org.apache.hadoop.hive.metastore.api.Partition> msPartitions,
      org.apache.hadoop.hive.metastore.api.Table msTbl,
      Map<String, List<FileDescriptor>> oldFileDescMap) throws IOException,
      CatalogException {
    resetPartitionMd();
    partitions_.clear();
    hdfsBaseDir_ = msTbl.getSd().getLocation();

    // Map of parent path to a list of new/modified FileDescriptors. FileDescriptors
    // in this Map will have their block location information (re)loaded. This is used
    // to speedup the incremental refresh of a table's metadata by skipping unmodified,
    // previously loaded FileDescriptors.
    Map<String, List<FileDescriptor>> fileDescsToLoad = Maps.newHashMap();

    // INSERT statements need to refer to this if they try to write to new partitions
    // Scans don't refer to this because by definition all partitions they refer to
    // exist.
    addDefaultPartition(msTbl.getSd());
    Long cacheDirectiveId =
        HdfsCachingUtil.getCacheDirIdFromParams(msTbl.getParameters());
    isMarkedCached_ = cacheDirectiveId != null;

    if (msTbl.getPartitionKeysSize() == 0) {
      Preconditions.checkArgument(msPartitions == null || msPartitions.isEmpty());
      // This table has no partition key, which means it has no declared partitions.
      // We model partitions slightly differently to Hive - every file must exist in a
      // partition, so add a single partition with no keys which will get all the
      // files in the table's root directory.
      HdfsPartition part = createPartition(msTbl.getSd(), null, oldFileDescMap,
          fileDescsToLoad);
      addPartition(part);
      if (isMarkedCached_) part.markCached();
      Path location = new Path(hdfsBaseDir_);
      if (DFS.exists(location)) {
        accessLevel_ = getAvailableAccessLevel(location);
      }
    } else {
      for (org.apache.hadoop.hive.metastore.api.Partition msPartition: msPartitions) {
        HdfsPartition partition = createPartition(msPartition.getSd(), msPartition,
            oldFileDescMap, fileDescsToLoad);
        addPartition(partition);
        // If the partition is null, its HDFS path does not exist, and it was not added to
        // this table's partition list. Skip the partition.
        if (partition == null) continue;
        if (msPartition.getParameters() != null); {
          partition.setNumRows(getRowCount(msPartition.getParameters()));
        }
        if (!TAccessLevelUtil.impliesWriteAccess(partition.getAccessLevel())) {
          // TODO: READ_ONLY isn't exactly correct because the it's possible the
          // partition does not have READ permissions either. When we start checking
          // whether we can READ from a table, this should be updated to set the
          // table's access level to the "lowest" effective level across all
          // partitions. That is, if one partition has READ_ONLY and another has
          // WRITE_ONLY the table's access level should be NONE.
          accessLevel_ = TAccessLevel.READ_ONLY;
        }
      }
    }
    loadBlockMd(fileDescsToLoad);
  }

  /**
   * Gets the AccessLevel that is available for Impala for this table based on the
   * permissions Impala has on the given path. If the path does not exist, recurses up the
   * path until a existing parent directory is found, and inherit access permissions from
   * that.
   */
  private TAccessLevel getAvailableAccessLevel(Path location) throws IOException {
    FsPermissionChecker permissionChecker = FsPermissionChecker.getInstance();
    while (location != null) {
      if (DFS.exists(location)) {
        FsPermissionChecker.Permissions perms =
            permissionChecker.getPermissions(DFS, location);
        if (perms.canReadAndWrite()) {
          return TAccessLevel.READ_WRITE;
        } else if (perms.canRead()) {
          LOG.debug(
              String.format("Impala does not have WRITE access to '%s' in table: %s",
              location, getFullName()));
          return TAccessLevel.READ_ONLY;
        } else if (perms.canWrite()) {
          LOG.debug(String.format("Impala does not have READ access to '%s' in table: %s",
                  location, getFullName()));
          return TAccessLevel.WRITE_ONLY;
        }
        LOG.debug(String.format("Impala does not have READ or WRITE access to " +
                "'%s' in table: %s", location, getFullName()));
        return TAccessLevel.NONE;
      }
      location = location.getParent();
    }
    // Should never get here.
    Preconditions.checkNotNull(location, "Error: no path ancestor exists");
    return TAccessLevel.NONE;
  }

  /**
   * Creates a new HdfsPartition object to be added to HdfsTable's partition list.
   * Partitions may be empty, or may not even exist in the file system (a partition's
   * location may have been changed to a new path that is about to be created by an
   * INSERT). Also loads the block metadata for this partition.
   * Returns new partition if successful or null if none was added.
   * Separated from addPartition to reduce the number of operations done while holding
   * the lock on HdfsTable.
   *
   *  @throws CatalogException
   *    if the supplied storage descriptor contains metadata that Impala can't
   *    understand.
   */
  public HdfsPartition createPartition(StorageDescriptor storageDescriptor,
      org.apache.hadoop.hive.metastore.api.Partition msPartition)
      throws CatalogException {
    Map<String, List<FileDescriptor>> fileDescsToLoad = Maps.newHashMap();
    HdfsPartition hdfsPartition = createPartition(storageDescriptor, msPartition,
        fileDescMap_, fileDescsToLoad);
    loadBlockMd(fileDescsToLoad);
    return hdfsPartition;
  }

  /**
   * Creates a new HdfsPartition object to be added to the internal partition list.
   * Populates with file format information and file locations. Partitions may be empty,
   * or may not even exist on the file system (a partition's location may have been
   * changed to a new path that is about to be created by an INSERT). For unchanged
   * files (indicated by unchanged mtime), reuses the FileDescriptor from the
   * oldFileDescMap. The one exception is if the partition is marked as cached
   * in which case the block metadata cannot be reused. Otherwise, creates a new
   * FileDescriptor for each modified or new file and adds it to newFileDescMap.
   * Both old and newFileDescMap are Maps of parent directory (partition location)
   * to list of files (FileDescriptors) under that directory.
   * Returns new partition if successful or null if none was added.
   * Separated from addPartition to reduce the number of operations done
   * while holding the lock on the hdfs table.

   *  @throws CatalogException
   *    if the supplied storage descriptor contains metadata that Impala can't
   *    understand.
   */
  private HdfsPartition createPartition(StorageDescriptor storageDescriptor,
      org.apache.hadoop.hive.metastore.api.Partition msPartition,
      Map<String, List<FileDescriptor>> oldFileDescMap,
      Map<String, List<FileDescriptor>> newFileDescMap)
      throws CatalogException {
    HdfsStorageDescriptor fileFormatDescriptor =
        HdfsStorageDescriptor.fromStorageDescriptor(this.name_, storageDescriptor);
    Path partDirPath = new Path(storageDescriptor.getLocation());
    List<FileDescriptor> fileDescriptors = Lists.newArrayList();
    // If the partition is marked as cached, the block location metadata must be
    // reloaded, even if the file times have not changed.
    boolean isMarkedCached = isMarkedCached_;
    List<LiteralExpr> keyValues = Lists.newArrayList();
    if (msPartition != null) {
      isMarkedCached =
          HdfsCachingUtil.getCacheDirIdFromParams(msPartition.getParameters()) != null;
      // Load key values
      for (String partitionKey: msPartition.getValues()) {
        Type type = getColumns().get(keyValues.size()).getType();
        // Deal with Hive's special NULL partition key.
        if (partitionKey.equals(nullPartitionKeyValue_)) {
          keyValues.add(NullLiteral.create(type));
        } else {
          try {
            keyValues.add(LiteralExpr.create(partitionKey, type));
          } catch (Exception ex) {
            LOG.warn("Failed to create literal expression of type: " + type, ex);
            throw new CatalogException("Invalid partition key value of type: " + type,
                ex);
          }
        }
      }
      try {
        Expr.analyze(keyValues, null);
      } catch (AnalysisException e) {
        // should never happen
        throw new IllegalStateException(e);
      }
    }
    try {
      if (DFS.exists(partDirPath)) {
        // DistributedFilesystem does not have an API that takes in a timestamp and return
        // a list of files that has been added/changed since. Therefore, we are calling
        // DFS.listStatus() to list all the files.
        for (FileStatus fileStatus: DFS.listStatus(partDirPath)) {
          String fileName = fileStatus.getPath().getName().toString();
          if (fileStatus.isDirectory() || FileSystemUtil.isHiddenFile(fileName) ||
              HdfsCompression.fromFileName(fileName) == HdfsCompression.LZO_INDEX) {
            // Ignore directory, hidden file starting with . or _, and LZO index files
            // If a directory is erroneously created as a subdirectory of a partition dir
            // we should ignore it and move on. Hive will not recurse into directories.
            // Skip index files, these are read by the LZO scanner directly.
            continue;
          }

          String partitionDir = fileStatus.getPath().getParent().toString();
          FileDescriptor fd = null;
          // Search for a FileDescriptor with the same partition dir and file name. If one
          // is found, it will be chosen as a candidate to reuse.
          if (oldFileDescMap != null && oldFileDescMap.get(partitionDir) != null) {
            for (FileDescriptor oldFileDesc: oldFileDescMap.get(partitionDir)) {
              if (oldFileDesc.getFileName().equals(fileName)) {
                fd = oldFileDesc;
                break;
              }
            }
          }

          // Check if this FileDescriptor has been modified since last loading its block
          // location information. If it has not been changed, the previously loaded
          // value can be reused.
          if (fd == null || isMarkedCached || fd.getFileLength() != fileStatus.getLen()
              || fd.getModificationTime() != fileStatus.getModificationTime()) {
            // Create a new file descriptor, the block metadata will be populated by
            // loadBlockMd.
            fd = new FileDescriptor(fileName, fileStatus.getLen(),
                fileStatus.getModificationTime());

            List<FileDescriptor> fds = newFileDescMap.get(partitionDir);
            if (fds == null) {
              fds = Lists.newArrayList();
              newFileDescMap.put(partitionDir, fds);
            }
            fds.add(fd);
          }

          List<FileDescriptor> fds = fileDescMap_.get(partitionDir);
          if (fds == null) {
            fds = Lists.newArrayList();
            fileDescMap_.put(partitionDir, fds);
          }
          fds.add(fd);

          // Add to the list of FileDescriptors for this partition.
          fileDescriptors.add(fd);
        }
        numHdfsFiles_ += fileDescriptors.size();
      }
      HdfsPartition partition = new HdfsPartition(this, msPartition, keyValues,
          fileFormatDescriptor, fileDescriptors, getAvailableAccessLevel(partDirPath));
      partition.checkWellFormed();
      return partition;
    } catch (Exception e) {
      throw new CatalogException("Failed to create partition: ", e);
    }
  }

  /**
   * Adds the partition to the HdfsTable.
   *
   * Note: This method is not thread safe because it modifies the list of partitions
   * and the HdfsTable's partition metadata.
   */
  public void addPartition(HdfsPartition partition) {
    if (partitions_.contains(partition)) return;
    partitions_.add(partition);
    totalHdfsBytes_ += partition.getSize();
    updatePartitionMdAndColStats(partition);
  }

  /**
   * Updates the HdfsTable's partition metadata, i.e. adds the id to the HdfsTable and
   * populates structures used for speeding up partition pruning/lookup. Also updates
   * column stats.
   */
  private void updatePartitionMdAndColStats(HdfsPartition partition) {
    if (partition.getPartitionValues().size() != numClusteringCols_) return;

    partitionIds_.add(partition.getId());
    partitionMap_.put(partition.getId(), partition);
    for (int i = 0; i < partition.getPartitionValues().size(); ++i) {
      ColumnStats stats = getColumns().get(i).getStats();
      LiteralExpr literal = partition.getPartitionValues().get(i);
      // Store partitions with null partition values separately
      if (literal instanceof NullLiteral) {
        stats.setNumNulls(stats.getNumNulls() + 1);
        if (nullPartitionIds_.get(i).isEmpty()) {
          stats.setNumDistinctValues(stats.getNumDistinctValues() + 1);
        }
        nullPartitionIds_.get(i).add(partition.getId());
        continue;
      }
      HashSet<Long> partitionIds = partitionValuesMap_.get(i).get(literal);
      if (partitionIds == null) {
        partitionIds = Sets.newHashSet();
        partitionValuesMap_.get(i).put(literal, partitionIds);
        stats.setNumDistinctValues(stats.getNumDistinctValues() + 1);
      }
      partitionIds.add(partition.getId());
    }
  }

  /**
   * Drops the partition having the given partition spec from HdfsTable. Cleans up its
   * metadata from all the mappings used to speed up partition pruning/lookup.
   * Also updates partition column statistics. Given partitionSpec must match exactly
   * one partition.
   * Returns the HdfsPartition that was dropped. If the partition does not exist, returns
   * null.
   *
   * Note: This method is not thread safe because it modifies the list of partitions
   * and the HdfsTable's partition metadata.
   */
  public HdfsPartition dropPartition(List<TPartitionKeyValue> partitionSpec) {
    HdfsPartition partition = getPartitionFromThriftPartitionSpec(partitionSpec);
    // Check if the partition does not exist.
    if (partition == null || !partitions_.remove(partition)) return null;
    totalHdfsBytes_ -= partition.getSize();
    Preconditions.checkArgument(partition.getPartitionValues().size() ==
        numClusteringCols_);
    Long partitionId = partition.getId();
    // Remove the partition id from the list of partition ids and other mappings.
    partitionIds_.remove(partitionId);
    partitionMap_.remove(partitionId);
    for (int i = 0; i < partition.getPartitionValues().size(); ++i) {
      ColumnStats stats = getColumns().get(i).getStats();
      LiteralExpr literal = partition.getPartitionValues().get(i);
      // Check if this is a null literal.
      if (literal instanceof NullLiteral) {
        nullPartitionIds_.get(i).remove(partitionId);
        stats.setNumNulls(stats.getNumNulls() - 1);
        if (nullPartitionIds_.get(i).isEmpty()) {
          stats.setNumDistinctValues(stats.getNumDistinctValues() - 1);
        }
        continue;
      }
      HashSet<Long> partitionIds = partitionValuesMap_.get(i).get(literal);
      // If there are multiple partition ids corresponding to a literal, remove
      // only this id. Otherwise, remove the <literal, id> pair.
      if (partitionIds.size() > 1) partitionIds.remove(partitionId);
      else {
        partitionValuesMap_.get(i).remove(literal);
        stats.setNumDistinctValues(stats.getNumDistinctValues() - 1);
      }
    }
    return partition;
  }

  private void addDefaultPartition(StorageDescriptor storageDescriptor)
      throws CatalogException {
    // Default partition has no files and is not referred to by scan nodes. Data sinks
    // refer to this to understand how to create new partitions.
    HdfsStorageDescriptor hdfsStorageDescriptor =
        HdfsStorageDescriptor.fromStorageDescriptor(this.name_, storageDescriptor);
    HdfsPartition partition = HdfsPartition.defaultPartition(this, hdfsStorageDescriptor);
    partitions_.add(partition);
  }

  @Override
  /**
   * Load the table metadata and reuse metadata to speed up metadata loading.
   * If the lastDdlTime has not been changed, that means the Hive metastore metadata has
   * not been changed. Reuses the old Hive partition metadata from cachedEntry.
   * To speed up Hdfs metadata loading, if a file's mtime has not been changed, reuses
   * the old file block metadata from old value.
   *
   * There are several cases where the cachedEntry might be reused incorrectly:
   * 1. an ALTER TABLE ADD PARTITION or dynamic partition insert is executed through
   *    Hive. This does not update the lastDdlTime.
   * 2. Hdfs rebalancer is executed. This changes the block locations but won't update
   *    the mtime (file modification time).
   * If any of these occurs, user has to execute "invalidate metadata" to invalidate the
   * metadata cache of the table to trigger a fresh load.
   */
  public void load(Table cachedEntry, HiveMetaStoreClient client,
      org.apache.hadoop.hive.metastore.api.Table msTbl) throws TableLoadingException {
    numHdfsFiles_ = 0;
    totalHdfsBytes_ = 0;
    LOG.debug("load table: " + db_.getName() + "." + name_);

    // turn all exceptions into TableLoadingException
    try {
      // set nullPartitionKeyValue from the hive conf.
      nullPartitionKeyValue_ = client.getConfigValue(
          "hive.exec.default.partition.name", "__HIVE_DEFAULT_PARTITION__");

      // set NULL indicator string from table properties
      nullColumnValue_ =
          msTbl.getParameters().get(serdeConstants.SERIALIZATION_NULL_FORMAT);
      if (nullColumnValue_ == null) nullColumnValue_ = DEFAULT_NULL_COLUMN_VALUE;

      // populate with both partition keys and regular columns
      List<FieldSchema> partKeys = msTbl.getPartitionKeys();
      List<FieldSchema> tblFields = Lists.newArrayList();
      String inputFormat = msTbl.getSd().getInputFormat();
      if (HdfsFileFormat.fromJavaClassName(inputFormat) == HdfsFileFormat.AVRO) {
        // Look for the schema in TBLPROPERTIES and in SERDEPROPERTIES, with the latter
        // taking precedence.
        List<Map<String, String>> schemaSearchLocations = Lists.newArrayList();
        schemaSearchLocations.add(
            getMetaStoreTable().getSd().getSerdeInfo().getParameters());
        schemaSearchLocations.add(getMetaStoreTable().getParameters());

        avroSchema_ =
            HdfsTable.getAvroSchema(schemaSearchLocations, getFullName(), true);
        String serdeLib = msTbl.getSd().getSerdeInfo().getSerializationLib();
        if (serdeLib == null ||
            serdeLib.equals("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe")) {
          // If the SerDe library is null or set to LazySimpleSerDe or is null, it
          // indicates there is an issue with the table metadata since Avro table need a
          // non-native serde. Instead of failing to load the table, fall back to
          // using the fields from the storage descriptor (same as Hive).
          tblFields.addAll(msTbl.getSd().getCols());
        } else {
          // Load the fields from the Avro schema.
          for (Column parsedCol: AvroSchemaParser.parse(avroSchema_)) {
            FieldSchema fs = new FieldSchema();
            fs.setName(parsedCol.getName());
            fs.setType(parsedCol.getType().toString());
            fs.setComment("from deserializer");
            tblFields.add(fs);
          }
        }
      } else {
        tblFields.addAll(msTbl.getSd().getCols());
      }
      List<FieldSchema> fieldSchemas = new ArrayList<FieldSchema>(
          partKeys.size() + tblFields.size());
      fieldSchemas.addAll(partKeys);
      fieldSchemas.addAll(tblFields);
      // The number of clustering columns is the number of partition keys.
      numClusteringCols_ = partKeys.size();
      loadColumns(fieldSchemas, client);

      // Collect the list of partitions to use for the table. Partitions may be reused
      // from the existing cached table entry (if one exists), read from the metastore,
      // or a mix of both. Whether or not a partition is reused depends on whether
      // the table or partition has been modified.
      List<org.apache.hadoop.hive.metastore.api.Partition> msPartitions =
          Lists.newArrayList();
      if (cachedEntry == null || !(cachedEntry instanceof HdfsTable) ||
          cachedEntry.lastDdlTime_ != lastDdlTime_) {
        msPartitions.addAll(MetaStoreUtil.fetchAllPartitions(
            client, db_.getName(), name_, NUM_PARTITION_FETCH_RETRIES));
      } else {
        // The table was already in the metadata cache and it has not been modified.
        Preconditions.checkArgument(cachedEntry instanceof HdfsTable);
        HdfsTable cachedHdfsTableEntry = (HdfsTable) cachedEntry;
        // Set of partition names that have been modified. Partitions in this Set need to
        // be reloaded from the metastore.
        Set<String> modifiedPartitionNames = Sets.newHashSet();

        // If these are not the exact same object, look up the set of partition names in
        // the metastore. This is to support the special case of CTAS which creates a
        // "temp" table that doesn't actually exist in the metastore.
        if (cachedEntry != this) {
          // Since the table has not been modified, we might be able to reuse some of the
          // old partition metadata if the individual partitions have not been modified.
          // First get a list of all the partition names for this table from the
          // metastore, this is much faster than listing all the Partition objects.
          modifiedPartitionNames.addAll(
              client.listPartitionNames(db_.getName(), name_, (short) -1));
        }

        int totalPartitions = modifiedPartitionNames.size();
        // Get all the partitions from the cached entry that have not been modified.
        for (HdfsPartition cachedPart: cachedHdfsTableEntry.getPartitions()) {
          // Skip the default partition and any partitions that have been modified.
          if (cachedPart.isDirty() || cachedPart.getMetaStorePartition() == null ||
              cachedPart.getId() == DEFAULT_PARTITION_ID) {
            continue;
          }
          org.apache.hadoop.hive.metastore.api.Partition cachedMsPart =
              cachedPart.getMetaStorePartition();
          Preconditions.checkNotNull(cachedMsPart);

          // This is a partition we already know about and it hasn't been modified.
          // No need to reload the metadata.
          String cachedPartName = cachedPart.getPartitionName();
          if (modifiedPartitionNames.contains(cachedPartName)) {
            msPartitions.add(cachedMsPart);
            modifiedPartitionNames.remove(cachedPartName);
          }
        }
        LOG.info(String.format("Incrementally refreshing %d/%d partitions.",
            modifiedPartitionNames.size(), totalPartitions));

        // No need to make the metastore call if no partitions are to be updated.
        if (modifiedPartitionNames.size() > 0) {
          // Now reload the the remaining partitions.
          msPartitions.addAll(MetaStoreUtil.fetchPartitionsByName(client,
              Lists.newArrayList(modifiedPartitionNames), db_.getName(), name_));
        }
      }

      Map<String, List<FileDescriptor>> oldFileDescMap = null;
      if (cachedEntry != null && cachedEntry instanceof HdfsTable) {
        HdfsTable cachedHdfsTable = (HdfsTable) cachedEntry;
        oldFileDescMap = cachedHdfsTable.fileDescMap_;
        hostIndex_.populate(cachedHdfsTable.hostIndex_.getList());
      }
      loadPartitions(msPartitions, msTbl, oldFileDescMap);

      // load table stats
      numRows_ = getRowCount(msTbl.getParameters());
      LOG.debug("table #rows=" + Long.toString(numRows_));

      // For unpartitioned tables set the numRows in its partitions
      // to the table's numRows.
      if (numClusteringCols_ == 0 && !partitions_.isEmpty()) {
        // Unpartitioned tables have a 'dummy' partition and a default partition.
        // Temp tables used in CTAS statements have one partition.
        Preconditions.checkState(partitions_.size() == 2 || partitions_.size() == 1);
        for (HdfsPartition p: partitions_) {
          p.setNumRows(numRows_);
        }
      }
    } catch (TableLoadingException e) {
      throw e;
    } catch (Exception e) {
      throw new TableLoadingException("Failed to load metadata for table: " + name_, e);
    }
  }

  /**
   * Gets an Avro table's JSON schema from the list of given table property search
   * locations. The schema may be specified as a string literal or provided as an
   * HDFS/http URL that points to the schema. This function does not perform any
   * validation on the returned string (e.g., it may not be a valid schema).
   * If downloadSchema is true and the schema was found to be specified as a SCHEMA_URL,
   * this function will attempt to download the schema from the given URL. Otherwise,
   * only the the URL string will be returned.
   * Throws a TableLoadingException if no schema is found or if there was any error
   * extracting the schema.
   */
  public static String getAvroSchema(List<Map<String, String>> schemaSearchLocations,
      String tableName, boolean downloadSchema) throws TableLoadingException {
    String url = null;
    // Search all locations and break out on the first valid schema found.
    for (Map<String, String> schemaLocation: schemaSearchLocations) {
      if (schemaLocation == null) continue;

      String literal = schemaLocation.get(AvroSerdeUtils.SCHEMA_LITERAL);
      if (literal != null && !literal.equals(AvroSerdeUtils.SCHEMA_NONE)) return literal;

      url = schemaLocation.get(AvroSerdeUtils.SCHEMA_URL);
      if (url != null) {
        url = url.trim();
        break;
      }
    }

    if (url == null || url.equals(AvroSerdeUtils.SCHEMA_NONE)) {
      throw new TableLoadingException(String.format("No Avro schema provided in " +
          "SERDEPROPERTIES or TBLPROPERTIES for table: %s ", tableName));
    }

    if (!url.toLowerCase().startsWith("hdfs://") &&
        !url.toLowerCase().startsWith("http://")) {
      throw new TableLoadingException("avro.schema.url must be of form " +
          "\"http://path/to/schema/file\" or " +
          "\"hdfs://namenode:port/path/to/schema/file\", got " + url);
    }
    return downloadSchema ? loadAvroSchemaFromUrl(url) : url;
  }

  private static String loadAvroSchemaFromUrl(String url)
      throws TableLoadingException {
    if (url.toLowerCase().startsWith("hdfs://")) {
      try {
        return FileSystemUtil.readFile(new Path(url));
      } catch (IOException e) {
        throw new TableLoadingException(
            "Problem reading Avro schema at: " + url, e);
      }
    } else {
      Preconditions.checkState(url.toLowerCase().startsWith("http://"));
      InputStream urlStream = null;
      try {
        urlStream = new URL(url).openStream();
        return IOUtils.toString(urlStream);
      } catch (IOException e) {
        throw new TableLoadingException("Problem reading Avro schema from: " + url, e);
      } finally {
        IOUtils.closeQuietly(urlStream);
      }
    }
  }

  @Override
  protected void loadFromThrift(TTable thriftTable) throws TableLoadingException {
    super.loadFromThrift(thriftTable);
    THdfsTable hdfsTable = thriftTable.getHdfs_table();
    hdfsBaseDir_ = hdfsTable.getHdfsBaseDir();
    nullColumnValue_ = hdfsTable.nullColumnValue;
    nullPartitionKeyValue_ = hdfsTable.nullPartitionKeyValue;
    hostIndex_.populate(hdfsTable.getNetwork_addresses());
    resetPartitionMd();

    numHdfsFiles_ = 0;
    totalHdfsBytes_ = 0;
    for (Map.Entry<Long, THdfsPartition> part: hdfsTable.getPartitions().entrySet()) {
      HdfsPartition hdfsPart =
          HdfsPartition.fromThrift(this, part.getKey(), part.getValue());
      numHdfsFiles_ += hdfsPart.getFileDescriptors().size();
      totalHdfsBytes_ += hdfsPart.getSize();
      partitions_.add(hdfsPart);
    }
    avroSchema_ = hdfsTable.isSetAvroSchema() ? hdfsTable.getAvroSchema() : null;
    isMarkedCached_ = HdfsCachingUtil.getCacheDirIdFromParams(
        getMetaStoreTable().getParameters()) != null;
    populatePartitionMd();
  }

  @Override
  public TTableDescriptor toThriftDescriptor(Set<Long> referencedPartitions) {
    // Create thrift descriptors to send to the BE.  The BE does not
    // need any information below the THdfsPartition level.
    TTableDescriptor tableDesc = new TTableDescriptor(id_.asInt(), TTableType.HDFS_TABLE,
        getColumns().size(), numClusteringCols_, name_, db_.getName());
    tableDesc.setHdfsTable(getTHdfsTable(false, referencedPartitions));
    tableDesc.setColNames(getColumnNames());
    return tableDesc;
  }

  @Override
  public TTable toThrift() {
    // Send all metadata between the catalog service and the FE.
    TTable table = super.toThrift();
    table.setTable_type(TTableType.HDFS_TABLE);
    table.setHdfs_table(getTHdfsTable(true, null));
    return table;
  }

  /**
   * Create a THdfsTable corresponding to this HdfsTable. If includeFileDesc is true,
   * then then all partitions and THdfsFileDescs of each partition should be included.
   * Otherwise, don't include any THdfsFileDescs, and include only those partitions in
   * the refPartitions set (the backend doesn't need metadata for unreferenced
   * partitions).
   */
  private THdfsTable getTHdfsTable(boolean includeFileDesc, Set<Long> refPartitions) {
    // includeFileDesc implies all partitions should be included (refPartitions == null).
    Preconditions.checkState(!includeFileDesc || refPartitions == null);
    Map<Long, THdfsPartition> idToPartition = Maps.newHashMap();
    for (HdfsPartition partition: partitions_) {
      long id = partition.getId();
      if (refPartitions == null || refPartitions.contains(id)) {
        idToPartition.put(id, partition.toThrift(includeFileDesc));
      }
    }
    THdfsTable hdfsTable = new THdfsTable(hdfsBaseDir_, getColumnNames(),
        nullPartitionKeyValue_, nullColumnValue_, idToPartition);
    hdfsTable.setAvroSchema(avroSchema_);
    if (includeFileDesc) {
      // Network addresses are used only by THdfsFileBlocks which are inside
      // THdfsFileDesc, so include network addreses only when including THdfsFileDesc.
      hdfsTable.setNetwork_addresses(hostIndex_.getList());
    }
    return hdfsTable;
  }

  public long getNumHdfsFiles() { return numHdfsFiles_; }
  public long getTotalHdfsBytes() { return totalHdfsBytes_; }
  public String getHdfsBaseDir() { return hdfsBaseDir_; }
  public boolean isAvroTable() { return avroSchema_ != null; }

  @Override
  public int getNumNodes() { return hostIndex_.size(); }

  /**
   * Get the index of hosts that store replicas of blocks of this table.
   */
  public ListMap<TNetworkAddress> getHostIndex() { return hostIndex_; }

  /**
   * Returns the file format that the majority of partitions are stored in.
   */
  public HdfsFileFormat getMajorityFormat() {
    Map<HdfsFileFormat, Integer> numPartitionsByFormat = Maps.newHashMap();
    for (HdfsPartition partition: partitions_) {
      HdfsFileFormat format = partition.getInputFormatDescriptor().getFileFormat();
      Integer numPartitions = numPartitionsByFormat.get(format);
      if (numPartitions == null) {
        numPartitions = Integer.valueOf(1);
      } else {
        numPartitions = Integer.valueOf(numPartitions.intValue() + 1);
      }
      numPartitionsByFormat.put(format, numPartitions);
    }

    int maxNumPartitions = Integer.MIN_VALUE;
    HdfsFileFormat majorityFormat = null;
    for (Map.Entry<HdfsFileFormat, Integer> entry: numPartitionsByFormat.entrySet()) {
      if (entry.getValue().intValue() > maxNumPartitions) {
        majorityFormat = entry.getKey();
        maxNumPartitions = entry.getValue().intValue();
      }
    }
    Preconditions.checkNotNull(majorityFormat);
    return majorityFormat;
  }

  /**
   * Returns statistics on this table as a tabular result set. Used for the
   * SHOW TABLE STATS statement. The schema of the returned TResultSet is set
   * inside this method.
   */
  public TResultSet getTableStats() {
    TResultSet result = new TResultSet();
    TResultSetMetadata resultSchema = new TResultSetMetadata();
    result.setSchema(resultSchema);
    for (int i = 0; i < numClusteringCols_; ++i) {
      // Add the partition-key values as strings for simplicity.
      Column partCol = getColumns().get(i);
      TColumn colDesc = new TColumn(partCol.getName(), partCol.getType().toThrift());
      resultSchema.addToColumns(colDesc);
    }
    resultSchema.addToColumns(new TColumn("#Rows", Type.BIGINT.toThrift()));
    resultSchema.addToColumns(new TColumn("#Files", Type.BIGINT.toThrift()));
    resultSchema.addToColumns(new TColumn("Size", Type.STRING.toThrift()));
    resultSchema.addToColumns(new TColumn("Bytes Cached", Type.STRING.toThrift()));
    resultSchema.addToColumns(new TColumn("Format", Type.STRING.toThrift()));

    // Pretty print partitions and their stats.
    ArrayList<HdfsPartition> orderedPartitions = Lists.newArrayList(partitions_);
    Collections.sort(orderedPartitions);

    long totalCachedBytes = 0L;
    for (HdfsPartition p: orderedPartitions) {
      // Ignore dummy default partition.
      if (p.getId() == ImpalaInternalServiceConstants.DEFAULT_PARTITION_ID) continue;
      TResultRowBuilder rowBuilder = new TResultRowBuilder();

      // Add the partition-key values (as strings for simplicity).
      for (LiteralExpr expr: p.getPartitionValues()) {
        rowBuilder.add(expr.getStringValue());
      }

      // Add number of rows, files, bytes, cache stats, and file format.
      rowBuilder.add(p.getNumRows()).add(p.getFileDescriptors().size())
          .addBytes(p.getSize());
      if (!p.isMarkedCached()) {
        // Helps to differentiate partitions that have 0B cached versus partitions
        // that are not marked as cached.
        rowBuilder.add("NOT CACHED");
      } else {
        // Calculate the number the number of bytes that are cached.
        long cachedBytes = 0L;
        for (FileDescriptor fd: p.getFileDescriptors()) {
          for (THdfsFileBlock fb: fd.getFileBlocks()) {
            // There should never be any cached bytes on CDH4.
            if (false) cachedBytes += fb.getLength();
          }
        }
        totalCachedBytes += cachedBytes;
        rowBuilder.addBytes(cachedBytes);
      }
      rowBuilder.add(p.getInputFormatDescriptor().getFileFormat().toString());
      result.addToRows(rowBuilder.get());
    }

    // For partitioned tables add a summary row at the bottom.
    if (numClusteringCols_ > 0) {
      TResultRowBuilder rowBuilder = new TResultRowBuilder();
      int numEmptyCells = numClusteringCols_ - 1;
      rowBuilder.add("Total");
      for (int i = 0; i < numEmptyCells; ++i) {
        rowBuilder.add("");
      }

      // Total num rows, files, and bytes (leave format empty).
      rowBuilder.add(numRows_).add(numHdfsFiles_).addBytes(totalHdfsBytes_)
          .addBytes(totalCachedBytes).add("");
      result.addToRows(rowBuilder.get());
    }
    return result;
  }
}

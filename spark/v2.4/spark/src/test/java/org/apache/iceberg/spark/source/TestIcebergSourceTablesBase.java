/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.spark.source;

import java.util.Comparator;
import java.util.List;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.iceberg.AssertHelpers;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.actions.DeleteOrphanFiles;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.spark.SparkReadOptions;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.SparkTableUtil;
import org.apache.iceberg.spark.SparkTestBase;
import org.apache.iceberg.spark.actions.SparkActions;
import org.apache.iceberg.spark.data.TestHelpers;
import org.apache.iceberg.types.Types;
import org.apache.spark.SparkException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.types.StructType;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;

public abstract class TestIcebergSourceTablesBase extends SparkTestBase {

  private static final Schema SCHEMA = new Schema(
      optional(1, "id", Types.IntegerType.get()),
      optional(2, "data", Types.StringType.get())
  );

  private static final Schema SCHEMA2 = new Schema(
      optional(1, "id", Types.IntegerType.get()),
      optional(2, "data", Types.StringType.get()),
      optional(3, "category", Types.StringType.get())
  );

  private static final Schema SCHEMA3 = new Schema(
      optional(1, "id", Types.IntegerType.get()),
      optional(3, "category", Types.StringType.get())
  );

  private static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).identity("id").build();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  public abstract Table createTable(TableIdentifier ident, Schema schema, PartitionSpec spec);

  public abstract Table loadTable(TableIdentifier ident, String entriesSuffix);

  public abstract String loadLocation(TableIdentifier ident, String entriesSuffix);

  public abstract String loadLocation(TableIdentifier ident);

  @Test
  public synchronized void testTablesSupport() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "table");
    createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<SimpleRecord> expectedRecords = Lists.newArrayList(
        new SimpleRecord(1, "1"),
        new SimpleRecord(2, "2"),
        new SimpleRecord(3, "3"));

    Dataset<Row> inputDf = spark.createDataFrame(expectedRecords, SimpleRecord.class);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode(SaveMode.Append)
        .save(loadLocation(tableIdentifier));

    Dataset<Row> resultDf = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    List<SimpleRecord> actualRecords = resultDf.orderBy("id")
        .as(Encoders.bean(SimpleRecord.class))
        .collectAsList();

    Assert.assertEquals("Records should match", expectedRecords, actualRecords);
  }

  @Test
  public void testEntriesTable() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "entries_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());
    Table entriesTable = loadTable(tableIdentifier, "entries");

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));

    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "entries"))
        .collectAsList();

    Snapshot snapshot = table.currentSnapshot();

    Assert.assertEquals("Should only contain one manifest", 1, snapshot.allManifests().size());

    InputFile manifest = table.io().newInputFile(snapshot.allManifests().get(0).path());
    List<GenericData.Record> expected = Lists.newArrayList();
    try (CloseableIterable<GenericData.Record> rows = Avro.read(manifest).project(entriesTable.schema()).build()) {
      // each row must inherit snapshot_id and sequence_number
      rows.forEach(row -> {
        row.put(2, 0L);
        GenericData.Record file = (GenericData.Record) row.get("data_file");
        asMetadataRecord(file);
        expected.add(row);
      });
    }

    Assert.assertEquals("Entries table should have one row", 1, expected.size());
    Assert.assertEquals("Actual results should have one row", 1, actual.size());
    TestHelpers.assertEqualsSafe(entriesTable.schema().asStruct(), expected.get(0), actual.get(0));
  }

  @Test
  public void testEntriesTablePartitionedPrune() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "entries_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));

    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "entries"))
        .select("status")
        .collectAsList();

    Assert.assertEquals("Results should contain only one status", 1, actual.size());
    Assert.assertEquals("That status should be Added (1)", 1, actual.get(0).getInt(0));
  }

  @Test
  public void testEntriesTableDataFilePrune() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "entries_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));

    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    DataFile file = table.currentSnapshot().addedFiles().iterator().next();

    List<Object[]> singleActual = rowsToJava(spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "entries"))
        .select("data_file.file_path")
        .collectAsList());

    List<Object[]> singleExpected = ImmutableList.of(row(file.path()));

    assertEquals("Should prune a single element from a nested struct", singleExpected, singleActual);
  }

  @Test
  public void testEntriesTableDataFilePruneMulti() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "entries_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));

    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    DataFile file = table.currentSnapshot().addedFiles().iterator().next();

    List<Object[]> multiActual = rowsToJava(spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "entries"))
        .select("data_file.file_path", "data_file.value_counts", "data_file.record_count", "data_file.column_sizes")
        .collectAsList());

    List<Object[]> multiExpected = ImmutableList.of(
        row(file.path(), file.valueCounts(), file.recordCount(), file.columnSizes()));

    assertEquals("Should prune a single element from a nested struct", multiExpected, multiActual);
  }

  @Test
  public void testFilesSelectMap() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "entries_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));

    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    DataFile file = table.currentSnapshot().addedFiles().iterator().next();

    List<Object[]> multiActual = rowsToJava(spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "files"))
        .select("file_path", "value_counts", "record_count", "column_sizes")
        .collectAsList());

    List<Object[]> multiExpected = ImmutableList.of(
        row(file.path(), file.valueCounts(), file.recordCount(), file.columnSizes()));

    assertEquals("Should prune a single element from a row", multiExpected, multiActual);
  }

  @Test
  public void testAllEntriesTable() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "entries_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());
    Table entriesTable = loadTable(tableIdentifier, "all_entries");

    Dataset<Row> df1 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);
    Dataset<Row> df2 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // delete the first file to test that not only live files are listed
    table.newDelete().deleteFromRowFilter(Expressions.equal("id", 1)).commit();

    // add a second file
    df2.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // ensure table data isn't stale
    table.refresh();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "all_entries"))
        .orderBy("snapshot_id")
        .collectAsList();

    List<GenericData.Record> expected = Lists.newArrayList();
    for (ManifestFile manifest : Iterables.concat(Iterables.transform(table.snapshots(), Snapshot::allManifests))) {
      InputFile in = table.io().newInputFile(manifest.path());
      try (CloseableIterable<GenericData.Record> rows = Avro.read(in).project(entriesTable.schema()).build()) {
        // each row must inherit snapshot_id and sequence_number
        rows.forEach(row -> {
          row.put(2, 0L);
          GenericData.Record file = (GenericData.Record) row.get("data_file");
          asMetadataRecord(file);
          expected.add(row);
        });
      }
    }

    expected.sort(Comparator.comparing(o -> (Long) o.get("snapshot_id")));

    Assert.assertEquals("Entries table should have 3 rows", 3, expected.size());
    Assert.assertEquals("Actual results should have 3 rows", 3, actual.size());
    for (int i = 0; i < expected.size(); i += 1) {
      TestHelpers.assertEqualsSafe(entriesTable.schema().asStruct(), expected.get(i), actual.get(i));
    }
  }

  @Test
  public void testCountEntriesTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "count_entries_test");
    createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    // init load
    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));
    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    final int expectedEntryCount = 1;

    // count entries
    Assert.assertEquals("Count should return " + expectedEntryCount,
        expectedEntryCount, spark.read().format("iceberg").load(loadLocation(tableIdentifier, "entries")).count());

    // count all_entries
    Assert.assertEquals("Count should return " + expectedEntryCount,
        expectedEntryCount, spark.read().format("iceberg").load(loadLocation(tableIdentifier, "all_entries")).count());
  }

  @Test
  public void testFilesTable() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "files_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);
    Table entriesTable = loadTable(tableIdentifier, "entries");
    Table filesTable = loadTable(tableIdentifier, "files");

    Dataset<Row> df1 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);
    Dataset<Row> df2 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(2, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // add a second file
    df2.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // delete the first file to test that only live files are listed
    table.newDelete().deleteFromRowFilter(Expressions.equal("id", 1)).commit();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "files"))
        .collectAsList();

    List<GenericData.Record> expected = Lists.newArrayList();
    for (ManifestFile manifest : table.currentSnapshot().dataManifests()) {
      InputFile in = table.io().newInputFile(manifest.path());
      try (CloseableIterable<GenericData.Record> rows = Avro.read(in).project(entriesTable.schema()).build()) {
        for (GenericData.Record record : rows) {
          if ((Integer) record.get("status") < 2 /* added or existing */) {
            GenericData.Record file = (GenericData.Record) record.get("data_file");
            asMetadataRecord(file);
            expected.add(file);
          }
        }
      }
    }

    Assert.assertEquals("Files table should have one row", 1, expected.size());
    Assert.assertEquals("Actual results should have one row", 1, actual.size());
    TestHelpers.assertEqualsSafe(filesTable.schema().asStruct(), expected.get(0), actual.get(0));
  }

  @Test
  public void testFilesTableWithSnapshotIdInheritance() throws Exception {
    spark.sql("DROP TABLE IF EXISTS parquet_table");

    TableIdentifier tableIdentifier = TableIdentifier.of("db", "files_inheritance_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);
    table.updateProperties()
        .set(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, "true")
        .commit();
    Table entriesTable = loadTable(tableIdentifier, "entries");
    Table filesTable = loadTable(tableIdentifier, "files");

    spark.sql(String.format(
        "CREATE TABLE parquet_table (data string, id int) " +
            "USING parquet PARTITIONED BY (id) LOCATION '%s'",
        temp.newFolder()));

    List<SimpleRecord> records = Lists.newArrayList(
        new SimpleRecord(1, "a"),
        new SimpleRecord(2, "b")
     );

    Dataset<Row> inputDF = spark.createDataFrame(records, SimpleRecord.class);
    inputDF.select("data", "id").write()
        .mode("overwrite")
        .insertInto("parquet_table");

    try {
      String stagingLocation = table.location() + "/metadata";
      SparkTableUtil.importSparkTable(spark,
          new org.apache.spark.sql.catalyst.TableIdentifier("parquet_table"),
          table, stagingLocation);

      List<Row> actual = spark.read()
          .format("iceberg")
          .load(loadLocation(tableIdentifier, "files"))
          .collectAsList();

      List<GenericData.Record> expected = Lists.newArrayList();
      for (ManifestFile manifest : table.currentSnapshot().dataManifests()) {
        InputFile in = table.io().newInputFile(manifest.path());
        try (CloseableIterable<GenericData.Record> rows = Avro.read(in).project(entriesTable.schema()).build()) {
          for (GenericData.Record record : rows) {
            GenericData.Record file = (GenericData.Record) record.get("data_file");
            asMetadataRecord(file);
            expected.add(file);
          }
        }
      }

      Assert.assertEquals("Files table should have one row", 2, expected.size());
      Assert.assertEquals("Actual results should have one row", 2, actual.size());
      TestHelpers.assertEqualsSafe(filesTable.schema().asStruct(), expected.get(0), actual.get(0));
      TestHelpers.assertEqualsSafe(filesTable.schema().asStruct(), expected.get(1), actual.get(1));
    } finally {
      spark.sql("DROP TABLE parquet_table");
    }

  }

  @Test
  public void testEntriesTableWithSnapshotIdInheritance() throws Exception {
    spark.sql("DROP TABLE IF EXISTS parquet_table");

    TableIdentifier tableIdentifier = TableIdentifier.of("db", "entries_inheritance_test");
    PartitionSpec spec = SPEC;
    Table table = createTable(tableIdentifier, SCHEMA, spec);

    table.updateProperties()
        .set(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, "true")
        .commit();

    spark.sql(String.format(
        "CREATE TABLE parquet_table (data string, id int) " +
            "USING parquet PARTITIONED BY (id) LOCATION '%s'",
        temp.newFolder()));

    List<SimpleRecord> records = Lists.newArrayList(
        new SimpleRecord(1, "a"),
        new SimpleRecord(2, "b")
    );

    Dataset<Row> inputDF = spark.createDataFrame(records, SimpleRecord.class);
    inputDF.select("data", "id").write()
        .mode("overwrite")
        .insertInto("parquet_table");

    try {
      String stagingLocation = table.location() + "/metadata";
      SparkTableUtil.importSparkTable(
          spark, new org.apache.spark.sql.catalyst.TableIdentifier("parquet_table"), table, stagingLocation);

      List<Row> actual = spark.read()
          .format("iceberg")
          .load(loadLocation(tableIdentifier, "entries"))
          .select("sequence_number", "snapshot_id", "data_file")
          .collectAsList();

      table.refresh();

      long snapshotId = table.currentSnapshot().snapshotId();

      Assert.assertEquals("Entries table should have 2 rows", 2, actual.size());
      Assert.assertEquals("Sequence number must match", 0, actual.get(0).getLong(0));
      Assert.assertEquals("Snapshot id must match", snapshotId, actual.get(0).getLong(1));
      Assert.assertEquals("Sequence number must match", 0, actual.get(1).getLong(0));
      Assert.assertEquals("Snapshot id must match", snapshotId, actual.get(1).getLong(1));
    } finally {
      spark.sql("DROP TABLE parquet_table");
    }
  }

  @Test
  public void testFilesUnpartitionedTable() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "unpartitioned_files_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());
    Table entriesTable = loadTable(tableIdentifier, "entries");
    Table filesTable = loadTable(tableIdentifier, "files");

    Dataset<Row> df1 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);
    Dataset<Row> df2 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(2, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    DataFile toDelete = Iterables.getOnlyElement(table.currentSnapshot().addedFiles());

    // add a second file
    df2.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // delete the first file to test that only live files are listed
    table.newDelete().deleteFile(toDelete).commit();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "files"))
        .collectAsList();

    List<GenericData.Record> expected = Lists.newArrayList();
    for (ManifestFile manifest : table.currentSnapshot().dataManifests()) {
      InputFile in = table.io().newInputFile(manifest.path());
      try (CloseableIterable<GenericData.Record> rows = Avro.read(in).project(entriesTable.schema()).build()) {
        for (GenericData.Record record : rows) {
          if ((Integer) record.get("status") < 2 /* added or existing */) {
            GenericData.Record file = (GenericData.Record) record.get("data_file");
            asMetadataRecord(file);
            expected.add(file);
          }
        }
      }
    }

    Assert.assertEquals("Files table should have one row", 1, expected.size());
    Assert.assertEquals("Actual results should have one row", 1, actual.size());
    TestHelpers.assertEqualsSafe(filesTable.schema().asStruct(), expected.get(0), actual.get(0));
  }

  @Test
  public void testAllMetadataTablesWithStagedCommits() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "stage_aggregate_table_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);

    table.updateProperties().set(TableProperties.WRITE_AUDIT_PUBLISH_ENABLED, "true").commit();
    spark.conf().set("spark.wap.id", "1234567");
    Dataset<Row> df1 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);
    Dataset<Row> df2 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(2, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // add a second file
    df2.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    List<Row> actualAllData = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "all_data_files"))
        .collectAsList();

    List<Row> actualAllManifests = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "all_manifests"))
        .collectAsList();

    List<Row> actualAllEntries = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "all_entries"))
        .collectAsList();

    Assert.assertTrue("Stage table should have some snapshots", table.snapshots().iterator().hasNext());
    Assert.assertEquals("Stage table should have null currentSnapshot",
        null, table.currentSnapshot());
    Assert.assertEquals("Actual results should have two rows", 2, actualAllData.size());
    Assert.assertEquals("Actual results should have two rows", 2, actualAllManifests.size());
    Assert.assertEquals("Actual results should have two rows", 2, actualAllEntries.size());
  }

  @Test
  public void testAllDataFilesTable() throws Exception {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "files_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);
    Table entriesTable = loadTable(tableIdentifier, "entries");
    Table filesTable = loadTable(tableIdentifier, "all_data_files");

    Dataset<Row> df1 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);
    Dataset<Row> df2 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(2, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // delete the first file to test that not only live files are listed
    table.newDelete().deleteFromRowFilter(Expressions.equal("id", 1)).commit();

    // add a second file
    df2.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    // ensure table data isn't stale
    table.refresh();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "all_data_files"))
        .orderBy("file_path")
        .collectAsList();
    actual.sort(Comparator.comparing(o -> o.getString(1)));

    List<GenericData.Record> expected = Lists.newArrayList();
    for (ManifestFile manifest : Iterables.concat(Iterables.transform(table.snapshots(), Snapshot::dataManifests))) {
      InputFile in = table.io().newInputFile(manifest.path());
      try (CloseableIterable<GenericData.Record> rows = Avro.read(in).project(entriesTable.schema()).build()) {
        for (GenericData.Record record : rows) {
          if ((Integer) record.get("status") < 2 /* added or existing */) {
            GenericData.Record file = (GenericData.Record) record.get("data_file");
            asMetadataRecord(file);
            expected.add(file);
          }
        }
      }
    }

    expected.sort(Comparator.comparing(o -> o.get("file_path").toString()));

    Assert.assertEquals("Files table should have two rows", 2, expected.size());
    Assert.assertEquals("Actual results should have two rows", 2, actual.size());
    for (int i = 0; i < expected.size(); i += 1) {
      TestHelpers.assertEqualsSafe(filesTable.schema().asStruct(), expected.get(i), actual.get(i));
    }
  }

  @Test
  public void testHistoryTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "history_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());
    Table historyTable = loadTable(tableIdentifier, "history");

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));
    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);

    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    long firstSnapshotTimestamp = table.currentSnapshot().timestampMillis();
    long firstSnapshotId = table.currentSnapshot().snapshotId();

    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    long secondSnapshotTimestamp = table.currentSnapshot().timestampMillis();
    long secondSnapshotId = table.currentSnapshot().snapshotId();

    // rollback the table state to the first snapshot
    table.rollback().toSnapshotId(firstSnapshotId).commit();
    long rollbackTimestamp = Iterables.getLast(table.history()).timestampMillis();

    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    long thirdSnapshotTimestamp = table.currentSnapshot().timestampMillis();
    long thirdSnapshotId = table.currentSnapshot().snapshotId();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "history"))
        .collectAsList();

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(historyTable.schema(), "history"));
    List<GenericData.Record> expected = Lists.newArrayList(
        builder.set("made_current_at", firstSnapshotTimestamp * 1000)
            .set("snapshot_id", firstSnapshotId)
            .set("parent_id", null)
            .set("is_current_ancestor", true)
            .build(),
        builder.set("made_current_at", secondSnapshotTimestamp * 1000)
            .set("snapshot_id", secondSnapshotId)
            .set("parent_id", firstSnapshotId)
            .set("is_current_ancestor", false) // commit rolled back, not an ancestor of the current table state
            .build(),
        builder.set("made_current_at", rollbackTimestamp * 1000)
            .set("snapshot_id", firstSnapshotId)
            .set("parent_id", null)
            .set("is_current_ancestor", true)
            .build(),
        builder.set("made_current_at", thirdSnapshotTimestamp * 1000)
            .set("snapshot_id", thirdSnapshotId)
            .set("parent_id", firstSnapshotId)
            .set("is_current_ancestor", true)
            .build()
    );

    Assert.assertEquals("History table should have a row for each commit", 4, actual.size());
    TestHelpers.assertEqualsSafe(historyTable.schema().asStruct(), expected.get(0), actual.get(0));
    TestHelpers.assertEqualsSafe(historyTable.schema().asStruct(), expected.get(1), actual.get(1));
    TestHelpers.assertEqualsSafe(historyTable.schema().asStruct(), expected.get(2), actual.get(2));
  }

  @Test
  public void testSnapshotsTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "snapshots_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());
    Table snapTable = loadTable(tableIdentifier, "snapshots");

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));
    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);

    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    long firstSnapshotTimestamp = table.currentSnapshot().timestampMillis();
    long firstSnapshotId = table.currentSnapshot().snapshotId();
    String firstManifestList = table.currentSnapshot().manifestListLocation();

    table.newDelete().deleteFromRowFilter(Expressions.alwaysTrue()).commit();

    long secondSnapshotTimestamp = table.currentSnapshot().timestampMillis();
    long secondSnapshotId = table.currentSnapshot().snapshotId();
    String secondManifestList = table.currentSnapshot().manifestListLocation();

    // rollback the table state to the first snapshot
    table.rollback().toSnapshotId(firstSnapshotId).commit();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "snapshots"))
        .collectAsList();

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(snapTable.schema(), "snapshots"));
    List<GenericData.Record> expected = Lists.newArrayList(
        builder.set("committed_at", firstSnapshotTimestamp * 1000)
            .set("snapshot_id", firstSnapshotId)
            .set("parent_id", null)
            .set("operation", "append")
            .set("manifest_list", firstManifestList)
            .set("summary", ImmutableMap.of(
                "added-records", "1",
                "added-data-files", "1",
                "changed-partition-count", "1",
                "total-data-files", "1",
                "total-records", "1"
            ))
            .build(),
        builder.set("committed_at", secondSnapshotTimestamp * 1000)
            .set("snapshot_id", secondSnapshotId)
            .set("parent_id", firstSnapshotId)
            .set("operation", "delete")
            .set("manifest_list", secondManifestList)
            .set("summary", ImmutableMap.of(
                "deleted-records", "1",
                "deleted-data-files", "1",
                "changed-partition-count", "1",
                "total-records", "0",
                "total-data-files", "0"
            ))
            .build()
    );

    Assert.assertEquals("Snapshots table should have a row for each snapshot", 2, actual.size());
    TestHelpers.assertEqualsSafe(snapTable.schema().asStruct(), expected.get(0), actual.get(0));
    TestHelpers.assertEqualsSafe(snapTable.schema().asStruct(), expected.get(1), actual.get(1));
  }

  @Test
  public void testPrunedSnapshotsTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "snapshots_test");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<SimpleRecord> records = Lists.newArrayList(new SimpleRecord(1, "1"));
    Dataset<Row> inputDf = spark.createDataFrame(records, SimpleRecord.class);

    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    long firstSnapshotTimestamp = table.currentSnapshot().timestampMillis();
    long firstSnapshotId = table.currentSnapshot().snapshotId();

    table.newDelete().deleteFromRowFilter(Expressions.alwaysTrue()).commit();

    long secondSnapshotTimestamp = table.currentSnapshot().timestampMillis();

    // rollback the table state to the first snapshot
    table.rollback().toSnapshotId(firstSnapshotId).commit();

    Dataset<Row> actualDf = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "snapshots"))
        .select("operation", "committed_at", "summary", "parent_id");

    Schema projectedSchema = SparkSchemaUtil.convert(actualDf.schema());

    List<Row> actual = actualDf.collectAsList();

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(projectedSchema, "snapshots"));
    List<GenericData.Record> expected = Lists.newArrayList(
        builder.set("committed_at", firstSnapshotTimestamp * 1000)
            .set("parent_id", null)
            .set("operation", "append")
            .set("summary", ImmutableMap.of(
                "added-records", "1",
                "added-data-files", "1",
                "changed-partition-count", "1",
                "total-data-files", "1",
                "total-records", "1"
            ))
            .build(),
        builder.set("committed_at", secondSnapshotTimestamp * 1000)
            .set("parent_id", firstSnapshotId)
            .set("operation", "delete")
            .set("summary", ImmutableMap.of(
                "deleted-records", "1",
                "deleted-data-files", "1",
                "changed-partition-count", "1",
                "total-records", "0",
                "total-data-files", "0"
            ))
            .build()
    );

    Assert.assertEquals("Snapshots table should have a row for each snapshot", 2, actual.size());
    TestHelpers.assertEqualsSafe(projectedSchema.asStruct(), expected.get(0), actual.get(0));
    TestHelpers.assertEqualsSafe(projectedSchema.asStruct(), expected.get(1), actual.get(1));
  }

  @Test
  public void testManifestsTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "manifests_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);
    Table manifestTable = loadTable(tableIdentifier, "manifests");
    Dataset<Row> df1 = spark.createDataFrame(
        Lists.newArrayList(new SimpleRecord(1, "a"), new SimpleRecord(null, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "manifests"))
        .collectAsList();

    table.refresh();

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        manifestTable.schema(), "manifests"));
    GenericRecordBuilder summaryBuilder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        manifestTable.schema().findType("partition_summaries.element").asStructType(), "partition_summary"));
    List<GenericData.Record> expected = Lists.transform(table.currentSnapshot().allManifests(), manifest ->
        builder.set("path", manifest.path())
            .set("length", manifest.length())
            .set("partition_spec_id", manifest.partitionSpecId())
            .set("added_snapshot_id", manifest.snapshotId())
            .set("added_data_files_count", manifest.addedFilesCount())
            .set("existing_data_files_count", manifest.existingFilesCount())
            .set("deleted_data_files_count", manifest.deletedFilesCount())
            .set("partition_summaries", Lists.transform(manifest.partitions(), partition ->
                summaryBuilder
                    .set("contains_null", true)
                    .set("contains_nan", false)
                    .set("lower_bound", "1")
                    .set("upper_bound", "1")
                    .build()
                ))
            .build()
    );

    Assert.assertEquals("Manifests table should have one manifest row", 1, actual.size());
    TestHelpers.assertEqualsSafe(manifestTable.schema().asStruct(), expected.get(0), actual.get(0));
  }

  @Test
  public void testPruneManifestsTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "manifests_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);
    Table manifestTable = loadTable(tableIdentifier, "manifests");
    Dataset<Row> df1 = spark.createDataFrame(
        Lists.newArrayList(new SimpleRecord(1, "a"), new SimpleRecord(null, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    if (!spark.version().startsWith("2")) {
      // Spark 2 isn't able to actually push down nested struct projections so this will not break
      AssertHelpers.assertThrows("Can't prune struct inside list", SparkException.class,
          "Cannot project a partial list element struct",
          () -> spark.read()
              .format("iceberg")
              .load(loadLocation(tableIdentifier, "manifests"))
              .select("partition_spec_id", "path", "partition_summaries.contains_null")
              .collectAsList());
    }

    Dataset<Row> actualDf = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "manifests"))
        .select("partition_spec_id", "path", "partition_summaries");

    Schema projectedSchema = SparkSchemaUtil.convert(actualDf.schema());

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "manifests"))
        .select("partition_spec_id", "path", "partition_summaries")
        .collectAsList();

    table.refresh();

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(projectedSchema.asStruct()));
    GenericRecordBuilder summaryBuilder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        projectedSchema.findType("partition_summaries.element").asStructType(), "partition_summary"));
    List<GenericData.Record> expected = Lists.transform(table.currentSnapshot().allManifests(), manifest ->
        builder.set("partition_spec_id", manifest.partitionSpecId())
            .set("path", manifest.path())
            .set("partition_summaries", Lists.transform(manifest.partitions(), partition ->
                summaryBuilder
                    .set("contains_null", true)
                    .set("contains_nan", false)
                    .set("lower_bound", "1")
                    .set("upper_bound", "1")
                    .build()
            ))
            .build()
    );

    Assert.assertEquals("Manifests table should have one manifest row", 1, actual.size());
    TestHelpers.assertEqualsSafe(projectedSchema.asStruct(), expected.get(0), actual.get(0));
  }

  @Test
  public void testAllManifestsTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "manifests_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);
    Table manifestTable = loadTable(tableIdentifier, "all_manifests");
    Dataset<Row> df1 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);

    List<ManifestFile> manifests = Lists.newArrayList();

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    manifests.addAll(table.currentSnapshot().allManifests());

    table.newDelete().deleteFromRowFilter(Expressions.alwaysTrue()).commit();

    manifests.addAll(table.currentSnapshot().allManifests());

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "all_manifests"))
        .orderBy("path")
        .collectAsList();

    table.refresh();

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        manifestTable.schema(), "manifests"));
    GenericRecordBuilder summaryBuilder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        manifestTable.schema().findType("partition_summaries.element").asStructType(), "partition_summary"));
    List<GenericData.Record> expected = Lists.newArrayList(Iterables.transform(manifests, manifest ->
        builder.set("path", manifest.path())
            .set("length", manifest.length())
            .set("partition_spec_id", manifest.partitionSpecId())
            .set("added_snapshot_id", manifest.snapshotId())
            .set("added_data_files_count", manifest.addedFilesCount())
            .set("existing_data_files_count", manifest.existingFilesCount())
            .set("deleted_data_files_count", manifest.deletedFilesCount())
            .set("partition_summaries", Lists.transform(manifest.partitions(), partition ->
                summaryBuilder
                    .set("contains_null", false)
                    .set("contains_nan", false)
                    .set("lower_bound", "1")
                    .set("upper_bound", "1")
                    .build()
            ))
            .build()
    ));

    expected.sort(Comparator.comparing(o -> o.get("path").toString()));

    Assert.assertEquals("Manifests table should have two manifest rows", 2, actual.size());
    for (int i = 0; i < expected.size(); i += 1) {
      TestHelpers.assertEqualsSafe(manifestTable.schema().asStruct(), expected.get(i), actual.get(i));
    }
  }

  @Test
  public void testUnpartitionedPartitionsTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "unpartitioned_partitions_test");
    createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    Dataset<Row> df = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);

    df.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    Types.StructType expectedSchema = Types.StructType.of(
        required(3, "record_count", Types.LongType.get()),
        required(4, "file_count", Types.IntegerType.get()));

    Table partitionsTable = loadTable(tableIdentifier, "partitions");

    Assert.assertEquals("Schema should not have partition field",
        expectedSchema, partitionsTable.schema().asStruct());

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        partitionsTable.schema(), "partitions"));
    GenericData.Record expectedRow = builder
        .set("record_count", 1L)
        .set("file_count", 1)
        .build();

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "partitions"))
        .collectAsList();

    Assert.assertEquals("Unpartitioned partitions table should have one row", 1, actual.size());
    TestHelpers.assertEqualsSafe(expectedSchema, expectedRow, actual.get(0));
  }

  @Test
  public void testPartitionsTable() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "partitions_test");
    Table table = createTable(tableIdentifier, SCHEMA, SPEC);
    Table partitionsTable = loadTable(tableIdentifier, "partitions");
    Dataset<Row> df1 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(1, "a")), SimpleRecord.class);
    Dataset<Row> df2 = spark.createDataFrame(Lists.newArrayList(new SimpleRecord(2, "b")), SimpleRecord.class);

    df1.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    table.refresh();
    long firstCommitId = table.currentSnapshot().snapshotId();

    // add a second file
    df2.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    List<Row> actual = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "partitions"))
        .orderBy("partition.id")
        .collectAsList();

    GenericRecordBuilder builder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        partitionsTable.schema(), "partitions"));
    GenericRecordBuilder partitionBuilder = new GenericRecordBuilder(AvroSchemaUtil.convert(
        partitionsTable.schema().findType("partition").asStructType(), "partition"));
    List<GenericData.Record> expected = Lists.newArrayList();
    expected.add(builder
        .set("spec_id", 0)
        .set("partition", partitionBuilder.set("id", 1).build())
        .set("record_count", 1L)
        .set("file_count", 1)
        .build());
    expected.add(builder
        .set("spec_id", 0)
        .set("partition", partitionBuilder.set("id", 2).build())
        .set("record_count", 1L)
        .set("file_count", 1)
        .build());

    Assert.assertEquals("Partitions table should have two rows", 2, expected.size());
    Assert.assertEquals("Actual results should have two rows", 2, actual.size());
    for (int i = 0; i < 2; i += 1) {
      TestHelpers.assertEqualsSafe(partitionsTable.schema().asStruct(), expected.get(i), actual.get(i));
    }

    // check time travel
    List<Row> actualAfterFirstCommit = spark.read()
        .format("iceberg")
        .option(SparkReadOptions.SNAPSHOT_ID, String.valueOf(firstCommitId))
        .load(loadLocation(tableIdentifier, "partitions"))
        .orderBy("partition.id")
        .collectAsList();

    Assert.assertEquals("Actual results should have one row", 1, actualAfterFirstCommit.size());
    TestHelpers.assertEqualsSafe(partitionsTable.schema().asStruct(), expected.get(0), actualAfterFirstCommit.get(0));

    // check predicate push down
    List<Row> filtered = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "partitions"))
        .filter("partition.id < 2")
        .collectAsList();
    Assert.assertEquals("Actual results should have one row", 1, filtered.size());
    TestHelpers.assertEqualsSafe(partitionsTable.schema().asStruct(), expected.get(0), filtered.get(0));

    List<Row> nonFiltered = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier, "partitions"))
        .filter("partition.id < 2 or record_count=1")
        .collectAsList();
    Assert.assertEquals("Actual results should have one row", 2, nonFiltered.size());
    for (int i = 0; i < 2; i += 1) {
      TestHelpers.assertEqualsSafe(partitionsTable.schema().asStruct(), expected.get(i), actual.get(i));
    }
  }

  @Test
  public synchronized void testSnapshotReadAfterAddColumn() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "table");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<Row> originalRecords = Lists.newArrayList(
        RowFactory.create(1, "x"),
        RowFactory.create(2, "y"),
        RowFactory.create(3, "z"));

    StructType originalSparkSchema = SparkSchemaUtil.convert(SCHEMA);
    Dataset<Row> inputDf = spark.createDataFrame(originalRecords, originalSparkSchema);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode(SaveMode.Append)
        .save(loadLocation(tableIdentifier));

    table.refresh();

    Dataset<Row> resultDf = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", originalRecords,
        resultDf.orderBy("id").collectAsList());

    Snapshot snapshot1 = table.currentSnapshot();

    table.updateSchema().addColumn("category", Types.StringType.get()).commit();

    List<Row> newRecords = Lists.newArrayList(
        RowFactory.create(4, "xy", "B"),
        RowFactory.create(5, "xyz", "C"));

    StructType newSparkSchema = SparkSchemaUtil.convert(SCHEMA2);
    Dataset<Row> inputDf2 = spark.createDataFrame(newRecords, newSparkSchema);
    inputDf2.select("id", "data", "category").write()
        .format("iceberg")
        .mode(SaveMode.Append)
        .save(loadLocation(tableIdentifier));

    table.refresh();

    List<Row> updatedRecords = Lists.newArrayList(
        RowFactory.create(1, "x", null),
        RowFactory.create(2, "y", null),
        RowFactory.create(3, "z", null),
        RowFactory.create(4, "xy", "B"),
        RowFactory.create(5, "xyz", "C"));

    Dataset<Row> resultDf2 = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", updatedRecords,
        resultDf2.orderBy("id").collectAsList());

    Dataset<Row> resultDf3 = spark.read()
        .format("iceberg")
        .option(SparkReadOptions.SNAPSHOT_ID, snapshot1.snapshotId())
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", originalRecords,
        resultDf3.orderBy("id").collectAsList());
    Assert.assertEquals("Schemas should match", originalSparkSchema, resultDf3.schema());
  }

  @Test
  public synchronized void testSnapshotReadAfterDropColumn() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "table");
    Table table = createTable(tableIdentifier, SCHEMA2, PartitionSpec.unpartitioned());

    List<Row> originalRecords = Lists.newArrayList(
        RowFactory.create(1, "x", "A"),
        RowFactory.create(2, "y", "A"),
        RowFactory.create(3, "z", "B"));

    StructType originalSparkSchema = SparkSchemaUtil.convert(SCHEMA2);
    Dataset<Row> inputDf = spark.createDataFrame(originalRecords, originalSparkSchema);
    inputDf.select("id", "data", "category").write()
        .format("iceberg")
        .mode(SaveMode.Append)
        .save(loadLocation(tableIdentifier));

    table.refresh();

    Dataset<Row> resultDf = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", originalRecords,
        resultDf.orderBy("id").collectAsList());

    long tsBeforeDropColumn = waitUntilAfter(System.currentTimeMillis());
    table.updateSchema().deleteColumn("data").commit();
    long tsAfterDropColumn = waitUntilAfter(System.currentTimeMillis());

    List<Row> newRecords = Lists.newArrayList(
        RowFactory.create(4, "B"),
        RowFactory.create(5, "C"));

    StructType newSparkSchema = SparkSchemaUtil.convert(SCHEMA3);
    Dataset<Row> inputDf2 = spark.createDataFrame(newRecords, newSparkSchema);
    inputDf2.select("id", "category").write()
        .format("iceberg")
        .mode(SaveMode.Append)
        .save(loadLocation(tableIdentifier));

    table.refresh();

    List<Row> updatedRecords = Lists.newArrayList(
        RowFactory.create(1, "A"),
        RowFactory.create(2, "A"),
        RowFactory.create(3, "B"),
        RowFactory.create(4, "B"),
        RowFactory.create(5, "C"));

    Dataset<Row> resultDf2 = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", updatedRecords,
        resultDf2.orderBy("id").collectAsList());

    Dataset<Row> resultDf3 = spark.read()
        .format("iceberg")
        .option(SparkReadOptions.AS_OF_TIMESTAMP, tsBeforeDropColumn)
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", originalRecords,
        resultDf3.orderBy("id").collectAsList());
    Assert.assertEquals("Schemas should match", originalSparkSchema, resultDf3.schema());

    // At tsAfterDropColumn, there has been a schema change, but no new snapshot,
    // so the snapshot as of tsAfterDropColumn is the same as that as of tsBeforeDropColumn.
    Dataset<Row> resultDf4 = spark.read()
        .format("iceberg")
        .option(SparkReadOptions.AS_OF_TIMESTAMP, tsAfterDropColumn)
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", originalRecords,
        resultDf4.orderBy("id").collectAsList());
    Assert.assertEquals("Schemas should match", originalSparkSchema, resultDf4.schema());
  }

  @Test
  public synchronized void testSnapshotReadAfterAddAndDropColumn() {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "table");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<Row> originalRecords = Lists.newArrayList(
        RowFactory.create(1, "x"),
        RowFactory.create(2, "y"),
        RowFactory.create(3, "z"));

    StructType originalSparkSchema = SparkSchemaUtil.convert(SCHEMA);
    Dataset<Row> inputDf = spark.createDataFrame(originalRecords, originalSparkSchema);
    inputDf.select("id", "data").write()
        .format("iceberg")
        .mode(SaveMode.Append)
        .save(loadLocation(tableIdentifier));

    table.refresh();

    Dataset<Row> resultDf = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", originalRecords,
        resultDf.orderBy("id").collectAsList());

    Snapshot snapshot1 = table.currentSnapshot();

    table.updateSchema().addColumn("category", Types.StringType.get()).commit();

    List<Row> newRecords = Lists.newArrayList(
        RowFactory.create(4, "xy", "B"),
        RowFactory.create(5, "xyz", "C"));

    StructType sparkSchemaAfterAddColumn = SparkSchemaUtil.convert(SCHEMA2);
    Dataset<Row> inputDf2 = spark.createDataFrame(newRecords, sparkSchemaAfterAddColumn);
    inputDf2.select("id", "data", "category").write()
        .format("iceberg")
        .mode(SaveMode.Append)
        .save(loadLocation(tableIdentifier));

    table.refresh();

    List<Row> updatedRecords = Lists.newArrayList(
        RowFactory.create(1, "x", null),
        RowFactory.create(2, "y", null),
        RowFactory.create(3, "z", null),
        RowFactory.create(4, "xy", "B"),
        RowFactory.create(5, "xyz", "C"));

    Dataset<Row> resultDf2 = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", updatedRecords,
        resultDf2.orderBy("id").collectAsList());

    table.updateSchema().deleteColumn("data").commit();

    List<Row> recordsAfterDropColumn = Lists.newArrayList(
        RowFactory.create(1, null),
        RowFactory.create(2, null),
        RowFactory.create(3, null),
        RowFactory.create(4, "B"),
        RowFactory.create(5, "C"));

    Dataset<Row> resultDf3 = spark.read()
        .format("iceberg")
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", recordsAfterDropColumn,
        resultDf3.orderBy("id").collectAsList());

    Dataset<Row> resultDf4 = spark.read()
        .format("iceberg")
        .option(SparkReadOptions.SNAPSHOT_ID, snapshot1.snapshotId())
        .load(loadLocation(tableIdentifier));
    Assert.assertEquals("Records should match", originalRecords,
        resultDf4.orderBy("id").collectAsList());
    Assert.assertEquals("Schemas should match", originalSparkSchema, resultDf4.schema());
  }

  @Test
  public void testRemoveOrphanFilesActionSupport() throws InterruptedException {
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "table");
    Table table = createTable(tableIdentifier, SCHEMA, PartitionSpec.unpartitioned());

    List<SimpleRecord> records = Lists.newArrayList(
        new SimpleRecord(1, "1")
    );

    Dataset<Row> df = spark.createDataFrame(records, SimpleRecord.class);

    df.select("id", "data").write()
        .format("iceberg")
        .mode("append")
        .save(loadLocation(tableIdentifier));

    df.write().mode("append").parquet(table.location() + "/data");

    // sleep for 1 second to ensure files will be old enough
    Thread.sleep(1000);

    SparkActions actions = SparkActions.get();

    DeleteOrphanFiles.Result result1 = actions.deleteOrphanFiles(table)
        .location(table.location() + "/metadata")
        .olderThan(System.currentTimeMillis())
        .execute();
    Assert.assertTrue("Should not delete any metadata files", Iterables.isEmpty(result1.orphanFileLocations()));

    DeleteOrphanFiles.Result result2 = actions.deleteOrphanFiles(table)
        .olderThan(System.currentTimeMillis())
        .execute();
    Assert.assertEquals("Should delete 1 data file", 1, Iterables.size(result2.orphanFileLocations()));

    Dataset<Row> resultDF = spark.read().format("iceberg").load(loadLocation(tableIdentifier));
    List<SimpleRecord> actualRecords = resultDF
        .as(Encoders.bean(SimpleRecord.class))
        .collectAsList();

    Assert.assertEquals("Rows must match", records, actualRecords);
  }

  private void asMetadataRecord(GenericData.Record file) {
    file.put(0, FileContent.DATA.id());
    file.put(3, 0); // specId
  }
}

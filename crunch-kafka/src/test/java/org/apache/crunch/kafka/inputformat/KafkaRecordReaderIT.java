/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.crunch.kafka.inputformat;

import kafka.api.OffsetRequest;
import org.apache.crunch.Pair;
import org.apache.crunch.kafka.ClusterTest;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.TaskAttemptContext;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.apache.crunch.kafka.KafkaUtils.getBrokerOffsets;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.matchers.JUnitMatchers.hasItem;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class KafkaRecordReaderIT {

  @Mock
  private TaskAttemptContext context;

  @Mock
  private Consumer<String, String> consumer;

  @Rule
  public TestName testName = new TestName();
  private Properties consumerProps;
  private Configuration config;

  @BeforeClass
  public static void setup() throws Exception {
    ClusterTest.startTest();
  }

  @AfterClass
  public static void cleanup() throws Exception {
    ClusterTest.endTest();
  }

  private String topic;

  @Before
  public void setupTest() {
    topic = testName.getMethodName();
    consumerProps = ClusterTest.getConsumerProperties();
    config = ClusterTest.getConsumerConfig();
    when(context.getConfiguration()).thenReturn(config);
    when(consumer.poll(Matchers.anyLong())).thenReturn(null);
  }

  @Test
  public void readData() throws IOException, InterruptedException {
    List<String> keys = ClusterTest.writeData(ClusterTest.getProducerProperties(), topic, "batch", 10, 10);

    Map<TopicPartition, Long> startOffsets = getBrokerOffsets(consumerProps, OffsetRequest.EarliestTime(), topic);
    Map<TopicPartition, Long> endOffsets = getBrokerOffsets(consumerProps, OffsetRequest.LatestTime(), topic);

    Map<TopicPartition, Pair<Long, Long>> offsets = new HashMap<>();
    for (Map.Entry<TopicPartition, Long> entry : startOffsets.entrySet()) {
      Long endingOffset = endOffsets.get(entry.getKey());
      offsets.put(entry.getKey(), Pair.of(entry.getValue(), endingOffset));
    }

    KafkaInputFormat.writeOffsetsToConfiguration(offsets, config);

    Set<String> keysRead = new HashSet<>();
    //read all data from all splits
    for (Map.Entry<TopicPartition, Pair<Long, Long>> partitionInfo : offsets.entrySet()) {
      KafkaInputSplit split = new KafkaInputSplit(partitionInfo.getKey().topic(), partitionInfo.getKey().partition(),
          partitionInfo.getValue().first(), partitionInfo.getValue().second());

      KafkaRecordReader<String, String> recordReader = new KafkaRecordReader<>();
      recordReader.initialize(split, context);

      int numRecordsFound = 0;
      while (recordReader.nextKeyValue()) {
        keysRead.add(recordReader.getCurrentKey());
        assertThat(keys, hasItem(recordReader.getCurrentKey()));
        assertThat(recordReader.getCurrentValue(), is(notNullValue()));
        numRecordsFound++;
      }
      recordReader.close();

      //assert that it encountered a partitions worth of data
      assertThat(((long) numRecordsFound), is(partitionInfo.getValue().second() - partitionInfo.getValue().first()));
    }

    //validate the same number of unique keys was read as were written.
    assertThat(keysRead.size(), is(keys.size()));
  }

  @Test
  public void pollReturnsNullAtStart() throws IOException, InterruptedException {
    List<String> keys = ClusterTest.writeData(ClusterTest.getProducerProperties(), topic, "batch", 10, 10);

    Map<TopicPartition, Long> startOffsets = getBrokerOffsets(consumerProps, OffsetRequest.EarliestTime(), topic);
    Map<TopicPartition, Long> endOffsets = getBrokerOffsets(consumerProps, OffsetRequest.LatestTime(), topic);

    Map<TopicPartition, Pair<Long, Long>> offsets = new HashMap<>();
    for (Map.Entry<TopicPartition, Long> entry : startOffsets.entrySet()) {
      Long endingOffset = endOffsets.get(entry.getKey());
      offsets.put(entry.getKey(), Pair.of(entry.getValue(), endingOffset));
    }

    KafkaInputFormat.writeOffsetsToConfiguration(offsets, config);

    Set<String> keysRead = new HashSet<>();
    //read all data from all splits
    for (Map.Entry<TopicPartition, Pair<Long, Long>> partitionInfo : offsets.entrySet()) {
      KafkaInputSplit split = new KafkaInputSplit(partitionInfo.getKey().topic(), partitionInfo.getKey().partition(),
              partitionInfo.getValue().first(), partitionInfo.getValue().second());

      KafkaRecordReader<String, String> recordReader = new NullAtStartKafkaRecordReader<>(consumer, 3);
      recordReader.initialize(split, context);

      int numRecordsFound = 0;
      while (recordReader.nextKeyValue()) {
        keysRead.add(recordReader.getCurrentKey());
        assertThat(keys, hasItem(recordReader.getCurrentKey()));
        assertThat(recordReader.getCurrentValue(), is(notNullValue()));
        numRecordsFound++;
      }
      recordReader.close();

      //assert that it encountered a partitions worth of data
      assertThat(((long) numRecordsFound), is(partitionInfo.getValue().second() - partitionInfo.getValue().first()));
    }

    //validate the same number of unique keys was read as were written.
    assertThat(keysRead.size(), is(keys.size()));
  }

  @Test
  public void pollReturnsEmptyAtStart() throws IOException, InterruptedException {
    List<String> keys = ClusterTest.writeData(ClusterTest.getProducerProperties(), topic, "batch", 10, 10);

    Map<TopicPartition, Long> startOffsets = getBrokerOffsets(consumerProps, OffsetRequest.EarliestTime(), topic);
    Map<TopicPartition, Long> endOffsets = getBrokerOffsets(consumerProps, OffsetRequest.LatestTime(), topic);

    Map<TopicPartition, Pair<Long, Long>> offsets = new HashMap<>();
    for (Map.Entry<TopicPartition, Long> entry : startOffsets.entrySet()) {
      Long endingOffset = endOffsets.get(entry.getKey());
      offsets.put(entry.getKey(), Pair.of(entry.getValue(), endingOffset));
    }

    KafkaInputFormat.writeOffsetsToConfiguration(offsets, config);

    Set<String> keysRead = new HashSet<>();
    //read all data from all splits
    for (Map.Entry<TopicPartition, Pair<Long, Long>> partitionInfo : offsets.entrySet()) {
      KafkaInputSplit split = new KafkaInputSplit(partitionInfo.getKey().topic(), partitionInfo.getKey().partition(),
              partitionInfo.getValue().first(), partitionInfo.getValue().second());

      when(consumer.poll(Matchers.anyLong())).thenReturn(ConsumerRecords.<String, String>empty());
      KafkaRecordReader<String, String> recordReader = new NullAtStartKafkaRecordReader<>(consumer, 3);
      recordReader.initialize(split, context);

      int numRecordsFound = 0;
      while (recordReader.nextKeyValue()) {
        keysRead.add(recordReader.getCurrentKey());
        assertThat(keys, hasItem(recordReader.getCurrentKey()));
        assertThat(recordReader.getCurrentValue(), is(notNullValue()));
        numRecordsFound++;
      }
      recordReader.close();

      //assert that it encountered a partitions worth of data
      assertThat(((long) numRecordsFound), is(partitionInfo.getValue().second() - partitionInfo.getValue().first()));
    }

    //validate the same number of unique keys was read as were written.
    assertThat(keysRead.size(), is(keys.size()));
  }

  @Test
  public void pollReturnsNullInMiddle() throws IOException, InterruptedException {
    List<String> keys = ClusterTest.writeData(ClusterTest.getProducerProperties(), topic, "batch", 10, 10);

    Map<TopicPartition, Long> startOffsets = getBrokerOffsets(consumerProps, OffsetRequest.EarliestTime(), topic);
    Map<TopicPartition, Long> endOffsets = getBrokerOffsets(consumerProps, OffsetRequest.LatestTime(), topic);

    Map<TopicPartition, Pair<Long, Long>> offsets = new HashMap<>();
    for (Map.Entry<TopicPartition, Long> entry : startOffsets.entrySet()) {
      Long endingOffset = endOffsets.get(entry.getKey());
      offsets.put(entry.getKey(), Pair.of(entry.getValue(), endingOffset));
    }

    KafkaInputFormat.writeOffsetsToConfiguration(offsets, config);

    Set<String> keysRead = new HashSet<>();
    //read all data from all splits
    for (Map.Entry<TopicPartition, Pair<Long, Long>> partitionInfo : offsets.entrySet()) {
      KafkaInputSplit split = new KafkaInputSplit(partitionInfo.getKey().topic(), partitionInfo.getKey().partition(),
              partitionInfo.getValue().first(), partitionInfo.getValue().second());

      KafkaRecordReader<String, String> recordReader = new InjectableKafkaRecordReader<>(consumer, 1);
      recordReader.initialize(split, context);

      int numRecordsFound = 0;
      while (recordReader.nextKeyValue()) {
        keysRead.add(recordReader.getCurrentKey());
        assertThat(keys, hasItem(recordReader.getCurrentKey()));
        assertThat(recordReader.getCurrentValue(), is(notNullValue()));
        numRecordsFound++;
      }
      recordReader.close();

      //assert that it encountered a partitions worth of data
      assertThat(((long) numRecordsFound), is(partitionInfo.getValue().second() - partitionInfo.getValue().first()));
    }

    //validate the same number of unique keys was read as were written.
    assertThat(keysRead.size(), is(keys.size()));
  }

  @Test
  public void pollReturnsEmptyInMiddle() throws IOException, InterruptedException {
    List<String> keys = ClusterTest.writeData(ClusterTest.getProducerProperties(), topic, "batch", 10, 10);

    Map<TopicPartition, Long> startOffsets = getBrokerOffsets(consumerProps, OffsetRequest.EarliestTime(), topic);
    Map<TopicPartition, Long> endOffsets = getBrokerOffsets(consumerProps, OffsetRequest.LatestTime(), topic);

    Map<TopicPartition, Pair<Long, Long>> offsets = new HashMap<>();
    for (Map.Entry<TopicPartition, Long> entry : startOffsets.entrySet()) {
      Long endingOffset = endOffsets.get(entry.getKey());
      offsets.put(entry.getKey(), Pair.of(entry.getValue(), endingOffset));
    }

    KafkaInputFormat.writeOffsetsToConfiguration(offsets, config);

    Set<String> keysRead = new HashSet<>();
    //read all data from all splits
    for (Map.Entry<TopicPartition, Pair<Long, Long>> partitionInfo : offsets.entrySet()) {
      KafkaInputSplit split = new KafkaInputSplit(partitionInfo.getKey().topic(), partitionInfo.getKey().partition(),
              partitionInfo.getValue().first(), partitionInfo.getValue().second());

      when(consumer.poll(Matchers.anyLong())).thenReturn(ConsumerRecords.<String, String>empty());
      KafkaRecordReader<String, String> recordReader = new InjectableKafkaRecordReader<>(consumer, 1);
      recordReader.initialize(split, context);

      int numRecordsFound = 0;
      while (recordReader.nextKeyValue()) {
        keysRead.add(recordReader.getCurrentKey());
        assertThat(keys, hasItem(recordReader.getCurrentKey()));
        assertThat(recordReader.getCurrentValue(), is(notNullValue()));
        numRecordsFound++;
      }
      recordReader.close();

      //assert that it encountered a partitions worth of data
      assertThat(((long) numRecordsFound), is(partitionInfo.getValue().second() - partitionInfo.getValue().first()));
    }

    //validate the same number of unique keys was read as were written.
    assertThat(keysRead.size(), is(keys.size()));
  }

  @Test
  public void pollEarliestEqualsEnding() throws IOException, InterruptedException {
    List<String> keys = ClusterTest.writeData(ClusterTest.getProducerProperties(), topic, "batch", 10, 10);

    Map<TopicPartition, Long> startOffsets = getBrokerOffsets(consumerProps, OffsetRequest.EarliestTime(), topic);
    Map<TopicPartition, Long> endOffsets = getBrokerOffsets(consumerProps, OffsetRequest.LatestTime(), topic);

    Map<TopicPartition, Pair<Long, Long>> offsets = new HashMap<>();
    for (Map.Entry<TopicPartition, Long> entry : startOffsets.entrySet()) {
      Long endingOffset = endOffsets.get(entry.getKey());
      offsets.put(entry.getKey(), Pair.of(entry.getValue(), endingOffset));
    }

    KafkaInputFormat.writeOffsetsToConfiguration(offsets, config);

    Set<String> keysRead = new HashSet<>();
    //read all data from all splits
    for (Map.Entry<TopicPartition, Pair<Long, Long>> partitionInfo : offsets.entrySet()) {
      KafkaInputSplit split = new KafkaInputSplit(partitionInfo.getKey().topic(), partitionInfo.getKey().partition(),
              partitionInfo.getValue().first(), partitionInfo.getValue().second());

      when(consumer.poll(Matchers.anyLong())).thenReturn(ConsumerRecords.<String, String>empty());
      KafkaRecordReader<String, String> recordReader = new EarliestRecordReader<>(consumer,
              partitionInfo.getValue().second());
      recordReader.initialize(split, context);

      int numRecordsFound = 0;
      while (recordReader.nextKeyValue()) {
        keysRead.add(recordReader.getCurrentKey());
        numRecordsFound++;
      }
      recordReader.close();

      //assert that it encountered a partitions worth of data
      assertThat(numRecordsFound, is(0));
    }

    //validate the same number of unique keys was read as were written.
    assertThat(keysRead.size(), is(0));
  }


  private static class NullAtStartKafkaRecordReader<K, V> extends KafkaRecordReader<K, V>{

    private final Consumer consumer;
    private final int callAttempts;

    private int attempts;

    public NullAtStartKafkaRecordReader(Consumer consumer, int callAttempts){
      this.consumer = consumer;
      this.callAttempts = callAttempts;
      attempts = 0;
    }

    @Override
    protected Consumer<K, V> getConsumer() {
      if(attempts > callAttempts){
        return super.getConsumer();
      }
      attempts++;
      return consumer;
    }
  }

  private static class InjectableKafkaRecordReader<K, V> extends KafkaRecordReader<K, V>{

    private final Consumer consumer;
    private final int failAttempt;

    private int attempts;

    public InjectableKafkaRecordReader(Consumer consumer, int failAttempt){
      this.consumer = consumer;
      this.failAttempt = failAttempt;
      attempts = 0;
    }

    @Override
    protected Consumer<K, V> getConsumer() {
      if(attempts == failAttempt){
        attempts++;
        return consumer;
      }
      attempts++;
      return super.getConsumer();
    }
  }

  private static class EarliestRecordReader<K,V> extends KafkaRecordReader<K, V>{

    private final long earliest;
    private final Consumer consumer;

    public EarliestRecordReader(Consumer consumer, long earliest){
      this.earliest = earliest;
      this.consumer = consumer;
    }

    @Override
    protected Consumer<K, V> getConsumer() {
      return consumer;
    }

    @Override
    protected long getEarliestOffset() {
      return earliest;
    }
  }
}

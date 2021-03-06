//*********************************************************
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.
//*********************************************************

package com.microsoft.kafkaavailability;

import com.microsoft.kafkaavailability.properties.ConsumerProperties;
import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.common.ErrorMapping;
import kafka.common.TopicAndPartition;
import kafka.javaapi.*;
import kafka.message.MessageAndOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/***
 * Responsible for consuming data from the tail of specified topics and partitions in Kafka
 */
public class Consumer implements IConsumer
{
    IPropertiesManager<ConsumerProperties> m_propManager;
    ConsumerProperties m_consumerProperties;
    private IMetaDataManager m_metaDataManager;
    private final Logger logger = LoggerFactory.getLogger(Consumer.class);

    public Consumer(IPropertiesManager<ConsumerProperties> propManager, IMetaDataManager metaDataManager)

    {
        m_propManager = propManager;
        m_consumerProperties = propManager.getProperties();
        m_metaDataManager = metaDataManager;
    }

    private List<String> m_replicaBrokers = new ArrayList<String>();

    public Consumer()
    {
        m_replicaBrokers = new ArrayList<String>();
    }

    /***
     * Consume the last message in the specified topic and partition
     * @param a_topic topic name
     * @param a_partition partition id
     * @throws Exception if it cannot find metadata for topic and partition, cannot find leader or if fetch size is too small.
     */
    @Override
    public void ConsumeFromTopicPartition(String a_topic, int a_partition) throws Exception
    {
        // find the meta data about the topic and partition we are interested in

        PartitionMetadata metadata = findLeader(m_metaDataManager.getBrokerList(false), m_consumerProperties.port, a_topic, a_partition);
        if (metadata == null)
        {
            throw new Exception("Cannot find metadata for Topic and Partition. Exiting");
        }
        if (metadata.leader() == null)
        {
            throw new Exception("Cannnot find Leader for Topic and Partition. Exiting");
        }
        String leadBroker = metadata.leader().host();
        String clientName = "Client_" + a_topic + "_" + a_partition;

        kafka.javaapi.consumer.SimpleConsumer consumer = new kafka.javaapi.consumer.SimpleConsumer(leadBroker, m_consumerProperties.port, m_consumerProperties.soTimeout, m_consumerProperties.bufferSize, clientName);
        long readOffset = getLastOffset(consumer, a_topic, a_partition, kafka.api.OffsetRequest.EarliestTime(), clientName);

        int numErrors = 0;
        int maxReads = m_consumerProperties.maxReads;
        while (maxReads > 0)
        {
            if (consumer == null)
            {
                consumer = new kafka.javaapi.consumer.SimpleConsumer(leadBroker, m_consumerProperties.port, m_consumerProperties.soTimeout, m_consumerProperties.bufferSize, clientName);
            }
            FetchRequest req = new FetchRequestBuilder()
                    .clientId(clientName)
                    .addFetch(a_topic, a_partition, readOffset, m_consumerProperties.fetchSize) // Note: this fetchSize of 100000 might need to be increased if large batches are written to Kafka
                    .build();
            FetchResponse fetchResponse = consumer.fetch(req);

            if (fetchResponse.hasError())
            {
                numErrors++;
                // Something went wrong!
                short code = fetchResponse.errorCode(a_topic, a_partition);
                logger.error("Error fetching data from the Broker:" + leadBroker + " Reason: " + code);
                if (numErrors > 5) break;
                if (code == ErrorMapping.OffsetOutOfRangeCode())
                {
                    // We asked for an invalid offset. For simple case ask for the last element to reset
                    readOffset = getLastOffset(consumer, a_topic, a_partition, kafka.api.OffsetRequest.LatestTime(), clientName);
                    continue;
                }
                consumer.close();
                consumer = null;
                leadBroker = findNewLeader(leadBroker, a_topic, a_partition, m_consumerProperties.port);
                continue;
            }
            numErrors = 0;

            long numRead = 0;
            for (MessageAndOffset messageAndOffset : fetchResponse.messageSet(a_topic, a_partition))
            {
                long currentOffset = messageAndOffset.offset();
                if (currentOffset < readOffset)
                {
                    logger.error("Found an old offset: " + currentOffset + " Expecting: " + readOffset);
                    continue;
                }
                readOffset = messageAndOffset.nextOffset();
                ByteBuffer payload = messageAndOffset.message().payload();

                byte[] bytes = new byte[payload.limit()];
                payload.get(bytes);
                logger.info(String.valueOf(messageAndOffset.offset()) + ": " + new String(bytes, "UTF-8"));
                numRead++;
                maxReads--;
            }

            if (numRead == 0)
            {
                throw new Exception("Either this topic has no data or fetchSize is too small");
            }
        }
        if (consumer != null) consumer.close();
    }

    /***
     * Get the offset of the last message in the specified topic and partition
     * @param consumer consumer object
     * @param topic topic name
     * @param partition partition id
     * @param whichTime time
     * @param clientName client name
     * @return offset value
     * @throws Exception if leader is not found or there is an error fetching data offset from broker.
     */
    public static long getLastOffset(kafka.javaapi.consumer.SimpleConsumer consumer, String topic, int partition,
                                     long whichTime, String clientName) throws Exception
    {
        TopicAndPartition topicAndPartition = new TopicAndPartition(topic, partition);
        Map<TopicAndPartition, PartitionOffsetRequestInfo> requestInfo = new HashMap<TopicAndPartition, PartitionOffsetRequestInfo>();
        requestInfo.put(topicAndPartition, new PartitionOffsetRequestInfo(whichTime, 1));
        kafka.javaapi.OffsetRequest request = new kafka.javaapi.OffsetRequest(
                requestInfo, kafka.api.OffsetRequest.CurrentVersion(), clientName);
        OffsetResponse response = consumer.getOffsetsBefore(request);

        if (response.hasError())
        {
            throw new Exception("Error fetching data Offset from the Broker. Reason: " + response.errorCode(topic, partition));
//            return 0;
        }
        long[] offsets = response.offsets(topic, partition);
        return offsets[0];
    }

    private String findNewLeader(String a_oldLeader, String a_topic, int a_partition, int a_port) throws Exception
    {
        for (int i = 0; i < 3; i++)
        {
            boolean goToSleep = false;
            PartitionMetadata metadata = findLeader(m_replicaBrokers, a_port, a_topic, a_partition);
            if (metadata == null)
            {
                goToSleep = true;
            } else if (metadata.leader() == null)
            {
                goToSleep = true;
            } else if (a_oldLeader.equalsIgnoreCase(metadata.leader().host()) && i == 0)
            {
                // first time through if the leader hasn't changed give ZooKeeper a second to recover
                // second time, assume the broker did recover before failover, or it was a non-Broker issue
                //
                goToSleep = true;
            } else
            {
                return metadata.leader().host();
            }
            if (goToSleep)
            {
                try
                {
                    Thread.sleep(1000);
                } catch (InterruptedException ie)
                {
                }
            }
        }
        throw new Exception("Unable to find new leader after Broker failure. Exiting");
    }

    private PartitionMetadata findLeader(List<String> a_seedBrokers, int a_port, String a_topic, int a_partition) throws Exception
    {
        PartitionMetadata returnMetaData = null;
        loop:
        for (String seed : a_seedBrokers)
        {
            kafka.javaapi.consumer.SimpleConsumer consumer = null;
            try
            {
                consumer = new kafka.javaapi.consumer.SimpleConsumer(seed, a_port, m_consumerProperties.soTimeout, m_consumerProperties.bufferSize, "leaderLookup");
                List<String> topics = Collections.singletonList(a_topic);
                TopicMetadataRequest req = new TopicMetadataRequest(topics);
                kafka.javaapi.TopicMetadataResponse resp = consumer.send(req);

                List<TopicMetadata> metaData = resp.topicsMetadata();
                for (TopicMetadata item : metaData)
                {
                    for (PartitionMetadata part : item.partitionsMetadata())
                    {
                        if (part.partitionId() == a_partition)
                        {
                            returnMetaData = part;
                            break loop;
                        }
                    }
                }
            } catch (Exception e)
            {
                throw new Exception("Error communicating with Broker [" + seed + "] to find Leader for [" + a_topic
                        + ", " + a_partition + "] Reason: " + e);
            } finally
            {
                if (consumer != null) consumer.close();
            }
        }
        if (returnMetaData != null)
        {
            m_replicaBrokers.clear();
            for (kafka.cluster.Broker replica : returnMetaData.replicas())
            {
                m_replicaBrokers.add(replica.host());
            }
        }
        return returnMetaData;
    }
}
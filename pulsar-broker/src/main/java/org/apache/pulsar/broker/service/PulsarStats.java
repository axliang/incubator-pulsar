/**
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
package org.apache.pulsar.broker.service;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.broker.stats.BrokerOperabilityMetrics;
import org.apache.pulsar.broker.stats.ClusterReplicationMetrics;
import org.apache.pulsar.broker.stats.NamespaceStats;
import org.apache.pulsar.broker.zookeeper.aspectj.ClientCnxnAspect.EventType;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.stats.Metrics;
import org.apache.pulsar.common.util.collections.ConcurrentOpenHashMap;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.apache.pulsar.utils.StatsOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

public class PulsarStats implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(PulsarStats.class);

    private volatile ByteBuf topicStatsBuf;
    private volatile ByteBuf tempTopicStatsBuf;
    private NamespaceStats nsStats;
    private final ClusterReplicationMetrics clusterReplicationMetrics;
    private Map<String, NamespaceBundleStats> bundleStats;
    private List<Metrics> tempMetricsCollection;
    private List<Metrics> metricsCollection;
    private final BrokerOperabilityMetrics brokerOperabilityMetrics;

    private final ReentrantReadWriteLock bufferLock = new ReentrantReadWriteLock();

    public PulsarStats(PulsarService pulsar) {
        this.topicStatsBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(16 * 1024);
        this.tempTopicStatsBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(16 * 1024);

        this.nsStats = new NamespaceStats();
        this.clusterReplicationMetrics = new ClusterReplicationMetrics(pulsar.getConfiguration().getClusterName(),
                pulsar.getConfiguration().isReplicationMetricsEnabled());
        this.bundleStats = Maps.newHashMap();
        this.tempMetricsCollection = Lists.newArrayList();
        this.metricsCollection = Lists.newArrayList();
        this.brokerOperabilityMetrics = new BrokerOperabilityMetrics(pulsar.getConfiguration().getClusterName(),
                pulsar.getAdvertisedAddress());
    }

    @Override
    public void close() {
        ReferenceCountUtil.safeRelease(topicStatsBuf);
        ReferenceCountUtil.safeRelease(tempTopicStatsBuf);
    }

    public ClusterReplicationMetrics getClusterReplicationMetrics() {
        return clusterReplicationMetrics;
    }

    public synchronized void updateStats(
            ConcurrentOpenHashMap<String, ConcurrentOpenHashMap<String, ConcurrentOpenHashMap<String, PersistentTopic>>> topicsMap) {

        StatsOutputStream topicStatsStream = new StatsOutputStream(tempTopicStatsBuf);

        try {
            tempMetricsCollection.clear();
            bundleStats.clear();
            brokerOperabilityMetrics.reset();

            // Json begin
            topicStatsStream.startObject();

            topicsMap.forEach((namespaceName, bundles) -> {
                if (bundles.isEmpty()) {
                    return;
                }

                try {
                    topicStatsStream.startObject(namespaceName);

                    nsStats.reset();

                    bundles.forEach((bundle, topics) -> {
                        NamespaceBundleStats currentBundleStats = bundleStats.computeIfAbsent(bundle,
                                k -> new NamespaceBundleStats());
                        currentBundleStats.reset();
                        currentBundleStats.topics = topics.size();

                        topicStatsStream.startObject(NamespaceBundle.getBundleRange(bundle));
                        topicStatsStream.startObject("persistent");
                        topics.forEach((name, topic) -> {
                            try {
                                topic.updateRates(nsStats, currentBundleStats, topicStatsStream,
                                        clusterReplicationMetrics, namespaceName);
                            } catch (Exception e) {
                                log.error("Failed to generate topic stats for topic {}: {}", name, e.getMessage(), e);
                            }
                            // this task: helps to activate inactive-backlog-cursors which have caught up and
                            // connected, also deactivate active-backlog-cursors which has backlog
                            topic.getManagedLedger().checkBackloggedCursors();
                        });

                        topicStatsStream.endObject();
                        topicStatsStream.endObject();
                    });

                    topicStatsStream.endObject();
                    // Update metricsCollection with namespace stats
                    tempMetricsCollection.add(nsStats.add(namespaceName));
                } catch (Exception e) {
                    log.error("Failed to generate namespace stats for namespace {}: {}", namespaceName, e.getMessage(),
                            e);
                }
            });
            if (clusterReplicationMetrics.isMetricsEnabled()) {
                clusterReplicationMetrics.get().forEach(clusterMetric -> tempMetricsCollection.add(clusterMetric));
                clusterReplicationMetrics.reset();
            }
            brokerOperabilityMetrics.getMetrics()
                    .forEach(brokerOperabilityMetric -> tempMetricsCollection.add(brokerOperabilityMetric));

            // json end
            topicStatsStream.endObject();
        } catch (Exception e) {
            log.error("Unable to update destination stats", e);
        }

        // swap metricsCollection and tempMetricsCollection
        List<Metrics> tempRefMetrics = metricsCollection;
        metricsCollection = tempMetricsCollection;
        tempMetricsCollection = tempRefMetrics;

        bufferLock.writeLock().lock();
        try {
            ByteBuf tmp = topicStatsBuf;
            topicStatsBuf = tempTopicStatsBuf;
            tempTopicStatsBuf = tmp;
            tempTopicStatsBuf.clear();
        } finally {
            bufferLock.writeLock().unlock();
        }
    }

    public void getDimensionMetrics(Consumer<ByteBuf> consumer) {
        bufferLock.readLock().lock();
        try {
            consumer.accept(topicStatsBuf);
        } finally {
            bufferLock.readLock().unlock();
        }
    }

    public List<Metrics> getDestinationMetrics() {
        return metricsCollection;
    }

    public Map<String, NamespaceBundleStats> getBundleStats() {
        return bundleStats;
    }

    public void recordTopicLoadTimeValue(String topic, long topicLoadLatencyMs) {
        try {
            brokerOperabilityMetrics.recordTopicLoadTimeValue(topicLoadLatencyMs);
        } catch (Exception ex) {
            log.warn("Exception while recording topic load time for topic {}, {}", topic, ex.getMessage());
        }
    }
    
    public void recordZkLatencyTimeValue(EventType eventType, long latencyMs) {
        try {
            if (EventType.write.equals(eventType)) {
                brokerOperabilityMetrics.recordZkWriteLatencyTimeValue(latencyMs);
            } else if (EventType.read.equals(eventType)) {
                brokerOperabilityMetrics.recordZkReadLatencyTimeValue(latencyMs);
            }
        } catch (Exception ex) {
            log.warn("Exception while recording zk-latency {}, {}", eventType, ex.getMessage());
        }
    }
}
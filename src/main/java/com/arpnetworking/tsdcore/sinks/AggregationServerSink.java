/**
 * Copyright 2014 Brandon Arp
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arpnetworking.tsdcore.sinks;

import com.arpnetworking.metrics.aggregation.protocol.Messages;
import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import com.arpnetworking.tsdcore.model.AggregatedData;
import com.arpnetworking.tsdcore.model.AggregationMessage;
import com.arpnetworking.tsdcore.model.PeriodicData;
import com.arpnetworking.tsdcore.model.Quantity;
import com.arpnetworking.tsdcore.statistics.HistogramStatistic;
import com.arpnetworking.tsdcore.statistics.Statistic;
import com.arpnetworking.tsdcore.statistics.StatisticFactory;
import com.google.protobuf.ByteString;
import net.sf.oval.constraint.NotNull;
import org.joda.time.DateTime;
import org.vertx.java.core.Handler;
import org.vertx.java.core.net.NetSocket;

import java.util.Map;

/**
 * Publisher to send data to an upstream aggregation server.
 *
 * @author Brandon Arp (barp at groupon dot com)
 */
public final class AggregationServerSink extends VertxSink {
    /**
     * {@inheritDoc}
     */
    @Override
    public void recordAggregateData(final PeriodicData periodicData) {
        LOGGER.debug()
                .setMessage("Writing aggregated data")
                .addData("sink", getName())
                .addData("dataSize", periodicData.getData().size())
                .addData("conditionsSize", periodicData.getConditions().size())
                .log();

        final Messages.StatisticSetRecord record = serializeMetricData(periodicData);
        if (record.getStatisticsCount() > 0) {
            enqueueData(AggregationMessage.create(record).serialize());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onConnect(final NetSocket socket) {
        _sentHandshake = false;
    }

    private Messages.StatisticSetRecord serializeMetricData(final PeriodicData periodicData) {
        // Get the cluster from the first data element
        final AggregatedData firstElement = periodicData.getData().iterator().next();
        if (!_sentHandshake) {
            // TODO(barp): Revise aggregator sink protocol for host and cluster support [MAI-443]
            final String host = periodicData.getDimensions().get("host");
            final String cluster = firstElement.getFQDSN().getCluster();
            final Messages.HostIdentification identifyHostMessage =
                    Messages.HostIdentification.newBuilder()
                            .setHostName(host)
                            .setClusterName(cluster)
                            .build();
            enqueueData(AggregationMessage.create(identifyHostMessage).serialize());
            LOGGER.debug()
                    .setMessage("Writing host identification message")
                    .addData("sink", getName())
                    .addData("hostName", host)
                    .addData("clusterName", cluster)
                    .log();
            _sentHandshake = true;
        }

        final Messages.StatisticSetRecord.Builder builder = Messages.StatisticSetRecord.newBuilder()
                .setMetric(firstElement.getFQDSN().getMetric())
                .setPeriod(periodicData.getPeriod().toString())
                .setPeriodStart(periodicData.getStart().toString())
                .setCluster(firstElement.getFQDSN().getCluster())
                .setService(firstElement.getFQDSN().getService())
                .setForwardHostData(_forwardHostData);

        for (final AggregatedData datum : periodicData.getData()) {
            if (EXPRESSION_STATISTIC.equals(datum.getFQDSN().getStatistic())) {
                continue;
            }

            final String unit;
            if (datum.getValue().getUnit().isPresent()) {
                unit = datum.getValue().getUnit().get().toString();
            } else {
                unit = "";
            }

            final Messages.StatisticRecord.Builder entryBuilder = builder.addStatisticsBuilder()
                    .setUnit(unit)
                    .setStatistic(datum.getFQDSN().getStatistic().getName())
                    .setValue(datum.getValue().getValue())
                    .setUnit(unit)
                    .setUserSpecified(datum.isSpecified());

            final ByteString supportingData = serializeSupportingData(datum);
            if (supportingData != null) {
                entryBuilder.setSupportingData(supportingData);
            }
            entryBuilder.build();
        }

        return builder.build();
    }

    private ByteString serializeSupportingData(final AggregatedData datum) {
        final Object data = datum.getSupportingData();
        final ByteString byteString;
        if (data instanceof HistogramStatistic.HistogramSupportingData) {
            final HistogramStatistic.HistogramSupportingData histogramSupportingData = (HistogramStatistic.HistogramSupportingData) data;
            final Messages.SparseHistogramSupportingData.Builder builder = Messages.SparseHistogramSupportingData.newBuilder();
            final HistogramStatistic.HistogramSnapshot histogram = histogramSupportingData.getHistogramSnapshot();
            final String unit;
            if (histogramSupportingData.getUnit().isPresent()) {
                unit = histogramSupportingData.getUnit().get().toString();
            } else {
                unit = "";
            }
            builder.setUnit(unit);

            for (final Map.Entry<Double, Integer> entry : histogram.getValues()) {
                builder.addEntriesBuilder()
                        .setBucket(entry.getKey())
                        .setCount(entry.getValue())
                        .build();
            }
            byteString = ByteString.copyFrom(AggregationMessage.create(builder.build()).serialize().getBytes());
        } else {
            return null;
        }
        return byteString;
    }

    private void heartbeat() {

        final Messages.HeartbeatRecord message = Messages.HeartbeatRecord.newBuilder()
                .setTimestamp(DateTime.now().toString())
                .build();
        sendRawData(AggregationMessage.create(message).serialize());
        LOGGER.debug()
                .setMessage("Heartbeat sent to aggregation server")
                .addData("sink", getName())
                .log();
    }

    private AggregationServerSink(final Builder builder) {
        super(builder);
        _forwardHostData = builder._forwardHostData;
        super.getVertx().setPeriodic(15000, new Handler<Long>() {
            @Override
            public void handle(final Long event) {
                LOGGER.trace()
                        .setMessage("Heartbeat tick")
                        .addData("sink", getName())
                        .log();
                heartbeat();
            }
        });
    }

    private final boolean _forwardHostData;

    private boolean _sentHandshake = false;

    private static final com.google.common.base.Function<Quantity, Double> EXTRACT_VALUES_FROM_SAMPLES =
            input -> input != null ? input.getValue() : null;

    private static final StatisticFactory STATISTIC_FACTORY = new StatisticFactory();
    private static final Statistic EXPRESSION_STATISTIC = STATISTIC_FACTORY.getStatistic("expression");
    private static final Logger LOGGER = LoggerFactory.getLogger(AggregationServerSink.class);

    /**
     * Implementation of builder pattern for <code>AggreationServerSink</code>.
     *
     * @author Ville Koskela (vkoskela at groupon dot com)
     */
    public static final class Builder extends VertxSink.Builder<Builder, AggregationServerSink> {

        /**
         * Public constructor.
         */
        public Builder() {
            super(AggregationServerSink.class);
            setServerPort(7065);
        }

        /**
         * Whether or not the aggregation server should forward this host data to its sinks.
         * Optional. Default is false.
         *
         * @param value Forward the data; true = forward and aggregate, false = just aggregate.
         * @return This instance of <code>Builder</code>.
         */
        public Builder setForwardHostData(final Boolean value) {
            _forwardHostData = value;
            return self();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Builder self() {
            return this;
        }

        @NotNull
        private Boolean _forwardHostData = false;
    }
}
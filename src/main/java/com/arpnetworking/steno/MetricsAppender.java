/**
 * Copyright 2016 Groupon.com
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
package com.arpnetworking.steno;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.arpnetworking.metrics.Metrics;
import com.arpnetworking.metrics.MetricsFactory;

/**
 * Logback appender that, when attached to a Logger, will increment a counter that correlates to the log level.
 * Primary use case: Attach to root Logger during application startup.
 *
 * @author Ryan Ascheman (rascheman at groupon dot com)
 */
public class MetricsAppender extends AppenderBase<ILoggingEvent> {
    /**
     * Metrics Factory.
     */
    private final MetricsFactory _metricsFactory;

    /**
     * Public constructor to set metrics factory.
     *
     * @param metricsFactory Metrics Factory
     */
    public MetricsAppender(final MetricsFactory metricsFactory) {
        _metricsFactory = metricsFactory;
    }

    @Override
    protected void append(final ILoggingEvent event) {
        final Level level = event.getLevel();
        try (final Metrics metrics = _metricsFactory.create()) {
            metrics.incrementCounter(level.toString());
        }
    }
}

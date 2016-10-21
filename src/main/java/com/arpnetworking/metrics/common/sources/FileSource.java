/**
 * Copyright 2014 Groupon.com
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
package com.arpnetworking.metrics.common.sources;

import com.arpnetworking.logback.annotations.LogValue;
import com.arpnetworking.metrics.common.parsers.Parser;
import com.arpnetworking.metrics.common.parsers.exceptions.ParsingException;
import com.arpnetworking.metrics.common.tailer.FilePositionStore;
import com.arpnetworking.metrics.common.tailer.InitialPosition;
import com.arpnetworking.metrics.common.tailer.NoPositionStore;
import com.arpnetworking.metrics.common.tailer.PositionStore;
import com.arpnetworking.metrics.common.tailer.StatefulTailer;
import com.arpnetworking.metrics.common.tailer.Tailer;
import com.arpnetworking.metrics.common.tailer.TailerListener;
import com.arpnetworking.steno.LogValueMapFactory;
import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import com.google.common.base.Optional;
import net.sf.oval.constraint.NotEmpty;
import net.sf.oval.constraint.NotNull;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Period;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Produce instances of <code>T</code>from a file. Supports rotating files
 * using <code>Tailer</code> from Apache Commons IO.
 *
 * @param <T> The data type to parse from the <code>Source</code>.
 *
 * @author Ville Koskela (ville dot koskela at inscopemetrics dot com)
 */
public final class FileSource<T> extends BaseSource {

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        _tailerExecutor.execute(_tailer);
        for (int i = 0; i < _lineProcessorThreads; i++) {
            _lineProcessorExecutor.execute(new LineQueueRunner<>(_lineProcessingStopped, _parser, this, _lineQueue));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        _tailer.stop();
        _tailerExecutor.shutdown();
        _lineProcessingStopped.set(true);
        _lineProcessorExecutor.shutdownNow();
        try {
            _tailerExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            LOGGER.warn()
                    .setMessage("Unable to shutdown tailer executor")
                    .setThrowable(e)
                    .log();
        }
        try {
            _lineProcessorExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            LOGGER.warn()
                    .setMessage("Unable to shutdown line processor executor")
                    .setThrowable(e)
                    .log();
        }
    }

    /**
     * Generate a Steno log compatible representation.
     *
     * @return Steno log compatible representation.
     */
    @LogValue
    public Object toLogValue() {
        return LogValueMapFactory.builder(this)
                .put("super", super.toLogValue())
                .put("parser", _parser)
                .put("tailer", _tailer)
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return toLogValue().toString();
    }

    @SuppressWarnings("unused")
    private FileSource(final Builder<T> builder) {
        this(builder, LOGGER);
    }

    // NOTE: Package private for testing
    /* package private */ FileSource(final Builder<T> builder, final Logger logger) {
        super(builder);
        _logger = logger;
        _parser = builder._parser;
        final PositionStore positionStore;
        if (builder._stateFile == null) {
            positionStore = NO_POSITION_STORE;
        } else {
            positionStore = new FilePositionStore.Builder().setFile(builder._stateFile).build();
        }

        _lineQueue = new ArrayBlockingQueue<>(builder._lineQueueMaxSize);

        final LogTailerListener logTailerListener = new LogTailerListener();

        _tailer = new StatefulTailer.Builder()
                .setFile(builder._sourceFile)
                .setListener(logTailerListener)
                .setReadInterval(builder._interval)
                .setPositionStore(positionStore)
                .setInitialPosition(builder._initialPosition)
                .build();
        _tailerExecutor = Executors.newSingleThreadExecutor((runnable) -> new Thread(runnable, "FileSourceTailer"));

        _lineProcessorThreads = builder._lineProcessorThreads;
        _lineProcessorExecutor =
                Executors.newFixedThreadPool(_lineProcessorThreads, runnable -> new Thread(runnable, "FileSourceLineProcessor"));


    }


    private final Parser<T, byte[]> _parser;
    private final Tailer _tailer;
    private final ExecutorService _tailerExecutor;
    private final ExecutorService _lineProcessorExecutor;
    private final AtomicBoolean _lineProcessingStopped = new AtomicBoolean(false);
    private final int _lineProcessorThreads;
    private ArrayBlockingQueue<byte[]> _lineQueue;
    private final Logger _logger;

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSource.class);
    private static final Period FILE_NOT_FOUND_WARNING_INTERVAL = Period.minutes(1);
    private static final NoPositionStore NO_POSITION_STORE = new NoPositionStore();

    private class LogTailerListener implements TailerListener {

        @Override
        public void initialize(final Tailer tailer) {
            _logger.debug()
                    .setMessage("Tailer initialized")
                    .addData("source", FileSource.this)
                    .log();
        }

        @Override
        public void fileNotFound() {
            final DateTime now = DateTime.now();
            if (!_lastFileNotFoundWarning.isPresent()
                    || _lastFileNotFoundWarning.get().isBefore(now.minus(FILE_NOT_FOUND_WARNING_INTERVAL))) {
                _logger.warn()
                        .setMessage("Tailer file not found")
                        .addData("source", FileSource.this)
                        .log();
                _lastFileNotFoundWarning = Optional.of(now);
            }
        }

        @Override
        public void fileRotated() {
            _logger.info()
                    .setMessage("Tailer file rotate")
                    .addData("source", FileSource.this)
                    .log();
        }

        @Override
        public void fileOpened() {
            _logger.info()
                    .setMessage("Tailer file opened")
                    .addData("source", FileSource.this)
                    .log();
        }

        @Override
        public void handle(final byte[] line) {
            try {
                _lineQueue.put(line);
            } catch (InterruptedException e) {
                _logger.error()
                        .setMessage("FileSource tailer thread interrupted while waiting to write line to queue")
                        .addData("source", FileSource.this)
                        .setThrowable(e)
                        .log();
                e.printStackTrace();
            }
        }

        @Override
        public void handle(final Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();

                _logger.info()
                        .setMessage("Tailer interrupted")
                        .addData("source", FileSource.this)
                        .addData("action", "stopping")
                        .setThrowable(t)
                        .log();

                _tailer.stop();
            } else {
                _logger.error()
                        .setMessage("Tailer exception")
                        .addData("source", FileSource.this)
                        .addData("action", "sleeping")
                        .setThrowable(t)
                        .log();
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();

                    _logger.info()
                            .setMessage("Sleep interrupted")
                            .addData("source", FileSource.this)
                            .addData("action", "stopping")
                            .setThrowable(t)
                            .log();

                    _tailer.stop();
                }
            }
        }

        private Optional<DateTime> _lastFileNotFoundWarning = Optional.absent();

    }

    private static class LineQueueRunner<T> implements Runnable {

        private LineQueueRunner(final AtomicBoolean lineProcessingStopped, final Parser<T, byte[]> parser, final FileSource<T> fileSource, final ArrayBlockingQueue<byte[]> queue) {
            _lineProcessingStopped = lineProcessingStopped;
            _parser = parser;
            _fileSource = fileSource;
            _queue = queue;
        }

        @Override
        public void run() {
            while(!_lineProcessingStopped.get()) {
                final byte[] line;
                try {
                    line = _queue.take();
                } catch (InterruptedException e) {
                    LOGGER.info()
                            .setMessage("Thread interrupted while waiting for query log line.")
                            .log();
                    continue;
                }
                try {
                    final T record;
                    try {
                        record = _parser.parse(line);
                    } catch (final ParsingException e) {
                        LOGGER.error()
                                .setMessage("Failed to parse data")
                                .setThrowable(e)
                                .log();
                        continue;
                    }
                    _fileSource.notify(record);
                } catch (RuntimeException e) {
                    LOGGER.error()
                            .setMessage("Caught exception while processing query log line.")
                            .setThrowable(e)
                            .addData("logLine", line)
                            .log();
                } catch (Throwable e) {
                    LOGGER.error()
                            .setMessage("Caught critical exception while processing query log line.")
                            .setThrowable(e)
                            .addData("logLine", line)
                            .log();
                    return;
                }
            }
        }

        private final ArrayBlockingQueue<byte[]> _queue;
        private final AtomicBoolean _lineProcessingStopped;
        private final Parser<T, byte[]> _parser;
        private final FileSource<T> _fileSource;
    }

    /**
     * Implementation of builder pattern for <code>FileSource</code>.
     *
     * @param <T> the type parsed from the parser.
     * @author Ville Koskela (ville dot koskela at inscopemetrics dot com)
     */
    public static class Builder<T> extends BaseSource.Builder<Builder<T>, FileSource<T>> {

        /**
         * Public constructor.
         */
        public Builder() {
            super(FileSource::new);
        }

        /**
         * Sets source file. Cannot be null.
         *
         * @param value The file path.
         * @return This instance of <code>Builder</code>.
         */
        public final Builder<T> setSourceFile(final Path value) {
            _sourceFile = value;
            return this;
        }

        /**
         * Sets file read interval in milliseconds. Cannot be null, minimum 1.
         * Default is 500 milliseconds.
         *
         * @param value The file read interval in milliseconds.
         * @return This instance of <code>Builder</code>.
         */
        public final Builder<T> setInterval(final Duration value) {
            _interval = value;
            return this;
        }

        /**
         * Sets whether to tail the file from its end or from its start.
         * Default InitialPosition.START;
         *
         * @param value Initial position to tail from.
         * @return This instance of <code>Builder</code>.
         */
        public final Builder<T> setInitialPosition(final InitialPosition value) {
            _initialPosition = value;
            return this;
        }

        /**
         * Sets <code>Parser</code>. Cannot be null.
         *
         * @param value The <code>Parser</code>.
         * @return This instance of <code>Builder</code>.
         */
        public final Builder<T> setParser(final Parser<T, byte[]> value) {
            _parser = value;
            return this;
        }

        /**
         * Sets state file. Optional. Default is null.
         * If null, uses a <code>NoPositionStore</code> in the underlying tailer.
         *
         * @param value The state file.
         * @return This instance of <code>Builder</code>.
         */
        public final Builder<T> setStateFile(final Path value) {
            _stateFile = value;
            return this;
        }

        /**
         * Sets the number of threads to be used to process lines read from the file. Optional.
         * Default is Runtime.getRuntime().availableProcessors().
         *
         * @param value The number of threads to be used.
         * @return This instance of <code>Builder</code>.
         */
        public final Builder<T> setLineProcessorThreads(final int value) {
            _lineProcessorThreads = value;
            return this;
        }

        /**
         * Sets the maximum size of the queue of lines to be stored in memory before processing. Optional.
         * Reading of lines from file will block if the queue is full.
         * Default is Runtime.getRuntime().availableProcessors() * 2.
         *
         * @param value The maximum number of unprocessed lines to store in memory.
         * @return This instance of <code>Builder</code>.
         */
        public final Builder<T> setLineQueueMaxSize(final int value) {
            _lineQueueMaxSize = value;
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Builder<T> self() {
            return this;
        }

        @NotNull
        @NotEmpty
        private Path _sourceFile;
        @NotNull
        private Duration _interval = Duration.millis(500);
        @NotNull
        private Parser<T, byte[]> _parser;
        private Path _stateFile;
        @NotNull
        private InitialPosition _initialPosition = InitialPosition.START;
        private int _lineProcessorThreads = Runtime.getRuntime().availableProcessors();
        private int _lineQueueMaxSize = Runtime.getRuntime().availableProcessors() * 2;
    }
}

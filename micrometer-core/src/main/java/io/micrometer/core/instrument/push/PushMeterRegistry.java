/*
 * Copyright 2018 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.push;

import io.micrometer.common.lang.Nullable;
import io.micrometer.common.util.internal.logging.InternalLogger;
import io.micrometer.common.util.internal.logging.InternalLoggerFactory;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.util.TimeUtils;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class PushMeterRegistry extends MeterRegistry {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PushMeterRegistry.class);

    // Schedule publishing in the beginning X percent of the step to avoid spill-over into
    // the next step.
    private static final double PERCENT_RANGE_OF_RANDOM_PUBLISHING_OFFSET = 0.8;

    private final PushRegistryConfig config;

    private final AtomicBoolean publishing = new AtomicBoolean(false);

    @Nullable
    private ScheduledExecutorService scheduledExecutorService;

    protected PushMeterRegistry(PushRegistryConfig config, Clock clock) {
        super(clock);

        config.requireValid();

        this.config = config;
    }

    protected abstract void publish();

    /**
     * Catch uncaught exceptions thrown from {@link #publish()}.
     */
    // VisibleForTesting
    void publishSafely() {
        if (this.publishing.compareAndSet(false, true)) {
            try {
                publish();
            }
            catch (Throwable e) {
                logger.warn("Unexpected exception thrown while publishing metrics for " + getClass().getSimpleName(),
                        e);
            }
            finally {
                this.publishing.set(false);
            }
        }
        else {
            logger.warn("Publishing is already in progress. Skipping duplicate call to publish().");
        }
    }

    /**
     * Returns if scheduled publishing of metrics is in progress.
     * @return if scheduled publishing of metrics is in progress
     * @since 1.11.0
     */
    protected boolean isPublishing() {
        return publishing.get();
    }

    /**
     * @deprecated Use {@link #start(ThreadFactory)} instead.
     */
    @Deprecated
    public final void start() {
        start(Executors.defaultThreadFactory());
    }

    public void start(ThreadFactory threadFactory) {
        if (scheduledExecutorService != null)
            stop();

        if (config.enabled()) {
            logger.info("publishing metrics for " + getClass().getSimpleName() + " every "
                    + TimeUtils.format(config.step()));

            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
            long stepMillis = config.step().toMillis();
            long initialDelayMillis = calculateInitialDelay();
            scheduledExecutorService.scheduleAtFixedRate(this::publishSafely, initialDelayMillis, stepMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
            scheduledExecutorService = null;
        }
    }

    @Override
    public void close() {
        stop();
        if (config.enabled() && !isClosed()) {
            publishSafely();
        }
        super.close();
    }

    // VisibleForTesting
    long calculateInitialDelay() {
        long stepMillis = config.step().toMillis();
        Random random = new Random();
        // in range of [0, X% of step - 2)
        long randomOffsetWithinStep = Math.max(0,
                (long) (stepMillis * random.nextDouble() * PERCENT_RANGE_OF_RANDOM_PUBLISHING_OFFSET) - 2);
        long offsetToStartOfNextStep = stepMillis - (clock.wallTime() % stepMillis);
        // at least 2ms into step, so it is after StepMeterRegistry's meterPollingService
        return offsetToStartOfNextStep + 2 + randomOffsetWithinStep;
    }

}

//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.opentelemetry.server;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;

public class LocalMetricExporter
    implements MetricExporter
{
    private final CompletableResultCode isShutdown = new CompletableResultCode();

    private final ConcurrentHashMap<String, Optional<MetricData>> currentMetricData = new ConcurrentHashMap<>();
    private Collection<MetricData> lastCollectedMetricData = null;

    private final Object updateNotification = new Object();

    public LocalMetricExporter()
    {
    }

    public void reset()
    {
        currentMetricData.clear();
    }

    public Optional<MetricData> getMetric(String name)
    {
        synchronized (updateNotification)
        {
            try
            {
                updateNotification.wait(30000);
            }
            catch (InterruptedException e)
            {
                return Optional.empty();
            }
        }

        return currentMetricData.computeIfAbsent(name, n ->
        {
            if (lastCollectedMetricData != null)
            {
                for (MetricData lastCollectedMetricDatum : lastCollectedMetricData)
                {
                    if (lastCollectedMetricDatum.getName().equals(n))
                    {
                        return Optional.of(lastCollectedMetricDatum);
                    }
                }
            }
            return Optional.empty();
        });
    }

    public Collection<MetricData> getMetrics()
    {
        if (lastCollectedMetricData == null)
        {
            synchronized (updateNotification)
            {
                try
                {
                    updateNotification.wait(30000);
                }
                catch (InterruptedException e)
                {
                    return Collections.emptyList();
                }
            }
        }
        return lastCollectedMetricData;
    }

    // MetricExporter implementation
    @Override
    public CompletableResultCode export(Collection<MetricData> metrics)
    {
        lastCollectedMetricData = metrics;
        for (MetricData collectedMetricDatum : lastCollectedMetricData)
        {
            if (currentMetricData.containsKey(collectedMetricDatum.getName()))
            {
                currentMetricData.put(collectedMetricDatum.getName(), Optional.of(collectedMetricDatum));
            }
        }
        synchronized (updateNotification)
        {
            updateNotification.notifyAll();
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush()
    {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown()
    {
        return isShutdown.succeed();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType)
    {
        return AggregationTemporalitySelector.alwaysCumulative().getAggregationTemporality(instrumentType);
    }
}

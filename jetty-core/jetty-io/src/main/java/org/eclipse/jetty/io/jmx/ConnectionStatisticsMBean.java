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

package org.eclipse.jetty.io.jmx;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class ConnectionStatisticsMBean extends ObjectMBean
{
    private static final String[] ITEM_NAMES = new String[]{
        "name",
        "connections",
        "connectionsTotal",
        "connectionsMax",
        "connectionDurationMax",
        "connectionDurationMean",
        "connectionDurationStdDev",
        "bytesIn",
        "bytesOut",
        "messagesIn",
        "messagesOut"
    };

    private static final String[] ITEM_DESCRIPTIONS = new String[]{
        "Connection class name",
        "Current open connections",
        "Total connections opened",
        "Max concurrent connections",
        "Max connection duration (ms)",
        "Mean connection duration (ms)",
        "Connection duration standard deviation",
        "Total bytes received",
        "Total bytes sent",
        "Total messages received",
        "Total messages sent"
    };

    @SuppressWarnings("rawtypes")
    private static final OpenType[] ITEM_TYPES = new OpenType[]{
        SimpleType.STRING,
        SimpleType.LONG,
        SimpleType.LONG,
        SimpleType.LONG,
        SimpleType.LONG,
        SimpleType.DOUBLE,
        SimpleType.DOUBLE,
        SimpleType.LONG,
        SimpleType.LONG,
        SimpleType.LONG,
        SimpleType.LONG
    };

    private static final CompositeType STATS_TYPE;
    private static final TabularType STATS_TABLE_TYPE;

    static
    {
        try
        {
            STATS_TYPE = new CompositeType(
                "ConnectionStats",
                "Connection statistics for a connection class",
                ITEM_NAMES,
                ITEM_DESCRIPTIONS,
                ITEM_TYPES
            );

            STATS_TABLE_TYPE = new TabularType(
                "ConnectionStatisticsTable",
                "Table of connection statistics by connection class",
                STATS_TYPE,
                new String[]{"name"}
            );
        }
        catch (OpenDataException e)
        {
            throw new ExceptionInInitializerError(e);
        }
    }

    public ConnectionStatisticsMBean(Object object)
    {
        super(object);
    }

    @ManagedAttribute("ConnectionStatistics grouped by connection class")
    public Collection<String> getConnectionStatisticsGroups()
    {
        ConnectionStatistics delegate = (ConnectionStatistics)getManagedObject();
        Map<String, ConnectionStatistics.Stats> groups = delegate.getConnectionStatisticsGroups();
        return groups.values().stream()
            .sorted(Comparator.comparing(ConnectionStatistics.Stats::getName))
            .map(stats -> stats.dump())
            .map(dump -> dump.replaceAll("[\r\n]", " "))
            .collect(Collectors.toList());
    }

    @ManagedAttribute("ConnectionStatistics grouped by connection class as TabularData")
    public TabularData getConnectionStatisticsAsTabularData() throws OpenDataException
    {
        ConnectionStatistics delegate = (ConnectionStatistics)getManagedObject();
        Map<String, ConnectionStatistics.Stats> groups = delegate.getConnectionStatisticsGroups();

        TabularDataSupport table = new TabularDataSupport(STATS_TABLE_TYPE);
        for (ConnectionStatistics.Stats stats : groups.values())
        {
            CompositeData row = new CompositeDataSupport(
                STATS_TYPE,
                ITEM_NAMES,
                new Object[]{
                    stats.getName(),
                    stats.getConnections(),
                    stats.getConnectionsTotal(),
                    stats.getConnectionsMax(),
                    stats.getConnectionDurationMax(),
                    stats.getConnectionDurationMean(),
                    stats.getConnectionDurationStdDev(),
                    stats.getReceivedBytes(),
                    stats.getSentBytes(),
                    stats.getReceivedMessages(),
                    stats.getSentMessages()
                }
            );
            table.put(row);
        }
        return table;
    }
}

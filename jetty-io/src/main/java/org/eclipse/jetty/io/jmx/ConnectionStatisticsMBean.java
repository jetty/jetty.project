//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class ConnectionStatisticsMBean extends ObjectMBean
{
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
}

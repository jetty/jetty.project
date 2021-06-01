//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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

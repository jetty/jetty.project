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

package org.eclipse.jetty.server.jmx;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class ServerMBean extends Handler.AbstractMBean
{
    public ServerMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public Server getManagedObject()
    {
        return (Server)super.getManagedObject();
    }

    @Override
    public String getObjectNameBasis()
    {
        Server server = getManagedObject();
        return server.getName();
    }

    @Override
    public String getObjectContextBasis()
    {
        // Returning the Server name as the "context" property
        // because it is inherited by the ObjectNames of the components
        // of Server such as the threadpool, the connectors, etc.
        Server server = getManagedObject();
        String name = server.getName();
        if (name != null)
            return name;
        return "%s@%x".formatted(TypeUtil.toShortName(server.getClass()), server.hashCode());
    }

    @ManagedAttribute("The contexts on this server")
    public List<ContextHandler> getContexts()
    {
        return getManagedObject().getDescendants(ContextHandler.class);
    }

    @ManagedAttribute("The UTC startup instant")
    public String getStartupTime()
    {
        ZonedDateTime zoned = getManagedObject().getStartupDateTime();
        return String.valueOf(zoned.withZoneSameInstant(ZoneOffset.UTC));
    }

    @ManagedAttribute("The startup date time in the system timezone")
    public String getStartupDateTime()
    {
        return String.valueOf(getManagedObject().getStartupDateTime());
    }

    @ManagedAttribute("The uptime duration in d:HH:mm:ss.SSS")
    public String getUpTime()
    {
        Duration upTime = Duration.ofMillis(getManagedObject().getUptimeMillis());
        return "%d:%02d:%02d:%02d.%03d".formatted(
            upTime.toDaysPart(),
            upTime.toHoursPart(),
            upTime.toMinutesPart(),
            upTime.toSecondsPart(),
            upTime.toMillisPart()
        );
    }
}

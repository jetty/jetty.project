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
import java.time.Instant;
import java.util.List;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class ServerMBean extends Handler.AbstractMBean
{
    private final Instant startup;

    public ServerMBean(Object managedObject)
    {
        super(managedObject);
        startup = Instant.now();
    }

    @Override
    public Server getManagedObject()
    {
        return (Server)super.getManagedObject();
    }

    @ManagedAttribute("The contexts on this server")
    public List<ContextHandler> getContexts()
    {
        return getManagedObject().getDescendants(ContextHandler.class);
    }

    @ManagedAttribute("The UTC startup instant")
    public String getStartupTime()
    {
        return startup.toString();
    }

    @ManagedAttribute("The uptime duration in d:HH:mm:ss.SSS")
    public String getUpTime()
    {
        Duration upTime = Duration.between(startup, Instant.now());
        return "%d:%02d:%02d:%02d.%03d".formatted(
            upTime.toDaysPart(),
            upTime.toHoursPart(),
            upTime.toMinutesPart(),
            upTime.toSecondsPart(),
            upTime.toMillisPart()
        );
    }
}

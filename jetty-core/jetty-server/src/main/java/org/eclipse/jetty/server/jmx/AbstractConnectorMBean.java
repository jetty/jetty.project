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

import java.util.Locale;
import java.util.stream.Collectors;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("MBean Wrapper for Connectors")
public class AbstractConnectorMBean extends ObjectMBean
{
    public AbstractConnectorMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public AbstractConnector getManagedObject()
    {
        return (AbstractConnector)super.getManagedObject();
    }

    @Override
    public String getObjectNameBasis()
    {
        AbstractConnector connector = getManagedObject();
        return connector.getName();
    }

    @Override
    public String getObjectContextBasis()
    {
        AbstractConnector connector = getManagedObject();
        String name = connector.getName();
        if (name != null)
            return name;
        return connector.getConnectionFactories().stream()
            .map(ConnectionFactory::getProtocol)
            .map(p -> p.toLowerCase(Locale.ROOT))
            .map(p -> "http/1.1".equals(p) ? "h1" : p)
            .collect(Collectors.joining(";"));
    }
}

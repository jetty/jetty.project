//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("MBean Wrapper for Connectors")
public class AbstractConnectorMBean extends ObjectMBean
{
    final AbstractConnector _connector;

    public AbstractConnectorMBean(Object managedObject)
    {
        super(managedObject);
        _connector = (AbstractConnector)managedObject;
    }

    @Override
    public String getObjectContextBasis()
    {
        StringBuilder buffer = new StringBuilder();
        for (ConnectionFactory f : _connector.getConnectionFactories())
        {
            String protocol = f.getProtocol();
            if (protocol != null)
            {
                if (buffer.length() > 0)
                    buffer.append("|");
                buffer.append(protocol);
            }
        }

        return String.format("%s@%x", buffer.toString(), _connector.hashCode());
    }
}

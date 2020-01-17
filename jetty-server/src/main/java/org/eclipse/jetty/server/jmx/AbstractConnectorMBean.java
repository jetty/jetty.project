//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

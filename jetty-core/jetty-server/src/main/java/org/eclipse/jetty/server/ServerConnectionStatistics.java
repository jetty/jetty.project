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

package org.eclipse.jetty.server;

import org.eclipse.jetty.io.ConnectionStatistics;

@Deprecated
public class ServerConnectionStatistics extends ConnectionStatistics
{
    /**
     * @param server the server to use to add {@link ConnectionStatistics} to all Connectors.
     * @deprecated use {@link Server#addBeanToAllConnectors(Object)} instead.
     */
    public static void addToAllConnectors(Server server)
    {
        for (Connector connector : server.getConnectors())
        {
            connector.addBean(new ConnectionStatistics());
        }
    }
}

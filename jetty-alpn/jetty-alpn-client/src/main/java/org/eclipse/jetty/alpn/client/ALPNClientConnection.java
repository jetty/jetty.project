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

package org.eclipse.jetty.alpn.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NegotiatingClientConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ALPNClientConnection extends NegotiatingClientConnection
{
    private static final Logger LOG = Log.getLogger(ALPNClientConnection.class);

    private final List<String> protocols;

    public ALPNClientConnection(EndPoint endPoint, Executor executor, ClientConnectionFactory connectionFactory, SSLEngine sslEngine, Map<String, Object> context, List<String> protocols)
    {
        super(endPoint, executor, sslEngine, connectionFactory, context);
        this.protocols = protocols;
    }

    public List<String> getProtocols()
    {
        return protocols;
    }

    public void selected(String protocol)
    {
        if (protocol == null || !protocols.contains(protocol))
            close();
        else
            super.completed();
    }
}

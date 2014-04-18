//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NegotiatingClientConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NPNClientConnection extends NegotiatingClientConnection implements NextProtoNego.ClientProvider
{
    private static final Logger LOG = Log.getLogger(NPNClientConnection.class);

    private final String protocol;

    public NPNClientConnection(EndPoint endPoint, Executor executor, ClientConnectionFactory connectionFactory, SSLEngine sslEngine, Map<String, Object> context, String protocol)
    {
        super(endPoint, executor, sslEngine, connectionFactory, context);
        this.protocol = protocol;
        NextProtoNego.put(sslEngine, this);
    }

    @Override
    public boolean supports()
    {
        return true;
    }

    @Override
    public void unsupported()
    {
        NextProtoNego.remove(getSSLEngine());
        completed();
    }

    @Override
    public String selectProtocol(List<String> protocols)
    {
        if (protocols.contains(protocol))
        {
            NextProtoNego.remove(getSSLEngine());
            completed();
            return protocol;
        }
        else
        {
            LOG.info("Could not negotiate protocol: server {} - client {}", protocols, protocol);
            close();
            return null;
        }
    }

    @Override
    public void close()
    {
        NextProtoNego.remove(getSSLEngine());
        super.close();
    }
}

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

package org.eclipse.jetty.spdy.server;

import java.util.List;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NegotiatingServerConnectionFactory;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NPNServerConnectionFactory extends NegotiatingServerConnectionFactory
{
    private static final Logger LOG = Log.getLogger(NPNServerConnectionFactory.class);

    public NPNServerConnectionFactory(@Name("protocols") String... protocols)
    {
        super("npn", protocols);
        try
        {
            ClassLoader npnClassLoader = NextProtoNego.class.getClassLoader();
            if (npnClassLoader != null)
            {
                LOG.warn("NPN must be in the boot classloader, not in: " + npnClassLoader);
                throw new IllegalStateException("NPN must be in the boot classloader");
            }
        }
        catch (Throwable x)
        {
            LOG.warn("NPN not available: " + x);
            throw new IllegalStateException("NPN not available", x);
        }
    }

    @Override
    protected AbstractConnection newServerConnection(Connector connector, EndPoint endPoint, SSLEngine engine, List<String> protocols, String defaultProtocol)
    {
        return new NPNServerConnection(endPoint, engine, connector, protocols, defaultProtocol);
    }
}

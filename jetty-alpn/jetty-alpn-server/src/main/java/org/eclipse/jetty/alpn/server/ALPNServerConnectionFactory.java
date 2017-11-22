//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.alpn.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.ALPNProcessor.Server;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NegotiatingServerConnectionFactory;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ALPNServerConnectionFactory extends NegotiatingServerConnectionFactory
{
    private static final Logger LOG = Log.getLogger(ALPNServerConnectionFactory.class);

    private final List<Server> processors = new ArrayList<>();

    public ALPNServerConnectionFactory(@Name("protocols") String protocols)
    {
        this(protocols.trim().split(",", 0));
    }
    
    public ALPNServerConnectionFactory(@Name("protocols") String... protocols)
    {
        super("alpn", protocols);

        IllegalStateException failure = new IllegalStateException("No Server ALPNProcessors!");
        // Use a for loop on iterator so load exceptions can be caught and ignored
        for (Iterator<Server> i = ServiceLoader.load(Server.class).iterator(); i.hasNext();)
        {
            Server processor;
            try
            {
                processor = i.next();
            }
            catch(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(x);
                failure.addSuppressed(x);
                continue;
            }

            try
            {
                processor.init();
                processors.add(processor);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not initialize " + processor, x);
                failure.addSuppressed(x);
            }
        }
        
        if (LOG.isDebugEnabled())
        {
            LOG.debug("protocols: {}", Arrays.asList(protocols));
            LOG.debug("processors: {}", processors);
        }

        if (processors.isEmpty())
            throw failure;
    }

    @Override
    protected AbstractConnection newServerConnection(Connector connector, EndPoint endPoint, SSLEngine engine, List<String> protocols, String defaultProtocol)
    {
        for (Server processor : processors)
        {
            if (processor.appliesTo(engine))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} for {} on {}", processor, engine, endPoint);
                ALPNServerConnection connection = new ALPNServerConnection(connector, endPoint, engine, protocols, defaultProtocol);
                processor.configure(engine, connection);
                return connection;
            }
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug("No ALPNProcessor: {} {}",engine,endPoint);
        throw new IllegalStateException("Connection rejected: No ALPN Processor for " + engine.getClass().getName() + " from " + processors);
    }
}

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

package org.eclipse.jetty.alpn.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.ALPNProcessor.Server;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NegotiatingServerConnectionFactory;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ALPNServerConnectionFactory extends NegotiatingServerConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(ALPNServerConnectionFactory.class);

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
        TypeUtil.serviceProviderStream(ServiceLoader.load(Server.class)).forEach(provider ->
        {
            Server processor;
            try
            {
                processor = provider.get();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(x.getMessage(), x);
                if (x != failure)
                    failure.addSuppressed(x);
                return;
            }

            try
            {
                processor.init();
                processors.add(processor);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not initialize {}", processor, x);
                if (x != failure)
                    failure.addSuppressed(x);
            }
        });

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
            LOG.debug("No ALPNProcessor: {} {}", engine, endPoint);
        throw new IllegalStateException("Connection rejected: No ALPN Processor for " + engine.getClass().getName() + " from " + processors);
    }
}

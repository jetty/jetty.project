//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.alpn.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NegotiatingClientConnectionFactory;
import org.eclipse.jetty.io.ssl.ALPNProcessor.Client;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.ServiceLoaderUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ALPNClientConnectionFactory extends NegotiatingClientConnectionFactory
{
    private static final Logger LOG = Log.getLogger(ALPNClientConnectionFactory.class);

    private final List<Client> processors = new ArrayList<>();
    private final Executor executor;
    private final List<String> protocols;

    public ALPNClientConnectionFactory(Executor executor, ClientConnectionFactory connectionFactory, List<String> protocols)
    {
        super(connectionFactory);
        if (protocols.isEmpty())
            throw new IllegalArgumentException("ALPN protocol list cannot be empty");
        this.executor = executor;
        this.protocols = protocols;

        IllegalStateException failure = new IllegalStateException("No Client ALPNProcessors!");

        // Use a for loop on iterator so load exceptions can be caught and ignored
        for (Client processor : ServiceLoaderUtil.load(Client.class))
        {
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
            LOG.debug("protocols: {}", protocols);
            LOG.debug("processors: {}", processors);
        }

        if (processors.isEmpty())
            throw failure;
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        SSLEngine engine = (SSLEngine)context.get(SslClientConnectionFactory.SSL_ENGINE_CONTEXT_KEY);
        for (Client processor : processors)
        {
            if (processor.appliesTo(engine))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} for {} on {}", processor, engine, endPoint);
                ALPNClientConnection connection = new ALPNClientConnection(endPoint, executor, getClientConnectionFactory(),
                    engine, context, protocols);
                processor.configure(engine, connection);
                return customize(connection, context);
            }
        }
        throw new IllegalStateException("No ALPNProcessor for " + engine);
    }

    public static class ALPN extends Info
    {
        public ALPN(Executor executor, ClientConnectionFactory factory, List<String> protocols)
        {
            super(List.of("alpn"), new ALPNClientConnectionFactory(executor, factory, protocols));
        }
    }
}

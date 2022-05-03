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

package org.eclipse.jetty.alpn.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NegotiatingClientConnectionFactory;
import org.eclipse.jetty.io.ssl.ALPNProcessor.Client;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ALPNClientConnectionFactory extends NegotiatingClientConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(ALPNClientConnectionFactory.class);

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
        TypeUtil.serviceProviderStream(ServiceLoader.load(Client.class)).forEach(provider ->
        {
            Client processor;
            try
            {
                processor = provider.get();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to load client processor", x);
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
                failure.addSuppressed(x);
            }
        });

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
}

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

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;
import java.util.function.Function;

import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.internal.CreatorNegotiator;

public interface WebSocketNegotiator extends Configuration.Customizer
{
    FrameHandler negotiate(WebSocketNegotiation negotiation) throws IOException;

    @Override
    default void customize(Configuration configurable)
    {
    }

    static WebSocketNegotiator from(Function<WebSocketNegotiation, FrameHandler> negotiate)
    {
        return from(negotiate, null);
    }

    static WebSocketNegotiator from(Function<WebSocketNegotiation, FrameHandler> negotiate, Configuration.Customizer customizer)
    {
        return new AbstractNegotiator(customizer)
        {
            @Override
            public FrameHandler negotiate(WebSocketNegotiation negotiation)
            {
                return negotiate.apply(negotiation);
            }
        };
    }

    static WebSocketNegotiator from(WebSocketCreator creator, FrameHandlerFactory factory)
    {
        return from(creator, factory, null);
    }

    static WebSocketNegotiator from(WebSocketCreator creator, FrameHandlerFactory factory, Configuration.Customizer customizer)
    {
        return new CreatorNegotiator(creator, factory, customizer);
    }

    abstract class AbstractNegotiator extends Configuration.ConfigurationCustomizer implements WebSocketNegotiator
    {
        final Configuration.Customizer customizer;

        public AbstractNegotiator()
        {
            this(null);
        }

        public AbstractNegotiator(Configuration.Customizer customizer)
        {
            this.customizer = customizer;
        }

        @Override
        public void customize(Configuration configurable)
        {
            if (customizer != null)
                customizer.customize(configurable);
            super.customize(configurable);
        }
    }
}

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

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;
import java.util.function.Function;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

public interface WebSocketNegotiator extends Configuration.Customizer
{
    FrameHandler negotiate(Negotiation negotiation) throws IOException;

    WebSocketExtensionRegistry getExtensionRegistry();

    DecoratedObjectFactory getObjectFactory();

    ByteBufferPool getByteBufferPool();

    WebSocketComponents getWebSocketComponents();

    static WebSocketNegotiator from(Function<Negotiation, FrameHandler> negotiate)
    {
        return new AbstractNegotiator()
        {
            @Override
            public FrameHandler negotiate(Negotiation negotiation)
            {
                return negotiate.apply(negotiation);
            }
        };
    }

    static WebSocketNegotiator from(Function<Negotiation, FrameHandler> negotiate, Configuration.Customizer customizer)
    {
        return new AbstractNegotiator(null, customizer)
        {
            @Override
            public FrameHandler negotiate(Negotiation negotiation)
            {
                return negotiate.apply(negotiation);
            }
        };
    }

    static WebSocketNegotiator from(
        Function<Negotiation, FrameHandler> negotiate,
        WebSocketComponents components,
        Configuration.Customizer customizer)
    {
        return new AbstractNegotiator(components, customizer)
        {
            @Override
            public FrameHandler negotiate(Negotiation negotiation)
            {
                return negotiate.apply(negotiation);
            }
        };
    }

    abstract class AbstractNegotiator implements WebSocketNegotiator
    {
        final WebSocketComponents components;
        final Configuration.Customizer customizer;

        public AbstractNegotiator()
        {
            this(null, null);
        }

        public AbstractNegotiator(WebSocketComponents components, Configuration.Customizer customizer)
        {
            this.components = components == null ? new WebSocketComponents() : components;
            this.customizer = customizer;
        }

        @Override
        public void customize(Configuration configurable)
        {
            if (customizer != null)
                customizer.customize(configurable);
        }

        @Override
        public WebSocketExtensionRegistry getExtensionRegistry()
        {
            return components.getExtensionRegistry();
        }

        @Override
        public DecoratedObjectFactory getObjectFactory()
        {
            return components.getObjectFactory();
        }

        @Override
        public ByteBufferPool getByteBufferPool()
        {
            return components.getBufferPool();
        }

        @Override
        public WebSocketComponents getWebSocketComponents()
        {
            return components;
        }

        public Configuration.Customizer getCustomizer()
        {
            return customizer;
        }
    }
}

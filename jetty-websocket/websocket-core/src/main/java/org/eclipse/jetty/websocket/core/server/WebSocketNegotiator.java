//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;
import java.util.function.Function;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

public interface WebSocketNegotiator extends FrameHandler.Customizer
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

    static WebSocketNegotiator from(Function<Negotiation, FrameHandler> negotiate, FrameHandler.Customizer customizer)
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
        FrameHandler.Customizer customizer)
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
        final FrameHandler.Customizer customizer;

        public AbstractNegotiator()
        {
            this(null, null);
        }

        public AbstractNegotiator(WebSocketComponents components, FrameHandler.Customizer customizer)
        {
            this.components = components == null ? new WebSocketComponents() : components;
            this.customizer = customizer;
        }

        @Override
        public void customize(FrameHandler.Configuration configurable)
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

        public FrameHandler.Customizer getCustomizer()
        {
            return customizer;
        }
    }
}

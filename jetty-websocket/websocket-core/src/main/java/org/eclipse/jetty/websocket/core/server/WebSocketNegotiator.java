//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

import java.io.IOException;
import java.util.function.Function;

public interface WebSocketNegotiator extends FrameHandler.Customizer
{
    FrameHandler negotiate(Negotiation negotiation) throws IOException;

    WebSocketExtensionRegistry getExtensionRegistry();

    DecoratedObjectFactory getObjectFactory();

    ByteBufferPool getByteBufferPool();

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
        return new AbstractNegotiator(null, null, null, customizer)
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
        WebSocketExtensionRegistry extensionRegistry,
        DecoratedObjectFactory objectFactory,
        ByteBufferPool bufferPool,
        FrameHandler.Customizer customizer)
    {
        return new AbstractNegotiator(extensionRegistry, objectFactory, bufferPool, customizer)
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
        final WebSocketExtensionRegistry extensionRegistry;
        final DecoratedObjectFactory objectFactory;
        final ByteBufferPool bufferPool;
        final FrameHandler.Customizer customizer;

        public AbstractNegotiator()
        {
            this(null, null, null, null);
        }

        public AbstractNegotiator(
            WebSocketExtensionRegistry extensionRegistry,
            DecoratedObjectFactory objectFactory,
            ByteBufferPool bufferPool,
            FrameHandler.Customizer customizer)
        {
            this.extensionRegistry = extensionRegistry == null?new WebSocketExtensionRegistry():extensionRegistry;
            this.objectFactory = objectFactory == null?new DecoratedObjectFactory():objectFactory;
            this.bufferPool = bufferPool;
            this.customizer = customizer;
        }

        @Override
        public void customize(FrameHandler.CoreSession session)
        {
            if (customizer != null)
                customizer.customize(session);
        }

        @Override
        public WebSocketExtensionRegistry getExtensionRegistry()
        {
            return extensionRegistry;
        }

        @Override
        public DecoratedObjectFactory getObjectFactory()
        {
            return objectFactory;
        }

        @Override
        public ByteBufferPool getByteBufferPool()
        {
            return bufferPool;
        }

        public FrameHandler.Customizer getCustomizer()
        {
            return customizer;
        }
    }
}

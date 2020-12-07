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

import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.FrameHandler;

public interface WebSocketNegotiator extends Configuration.Customizer
{
    FrameHandler negotiate(WebSocketNegotiation negotiation) throws IOException;

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

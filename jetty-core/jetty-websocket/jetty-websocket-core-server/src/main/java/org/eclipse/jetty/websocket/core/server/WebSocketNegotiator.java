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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.internal.CreatorNegotiator;

public interface WebSocketNegotiator extends Configuration.Customizer
{
    /**
     * Create a {@link FrameHandler} from the incoming request.
     *
     * <p>If the negotiator returns null it is responsible for completing the {@link Callback} and sending a response.
     * If the negotiator intends to return non-null {@link FrameHandler}, it MUST NOT write content to the response or
     * complete the {@link Callback}.</p>
     *
     * @param request the request details
     * @param response the response details
     * @param callback the callback, should only be completed by the creator if a null WebSocket object is returned.
     * @return the FrameHandler, or null to take responsibility to send error response if no WebSocket is to be created.
     */
    FrameHandler negotiate(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback);

    static WebSocketNegotiator from(WebSocketCreator creator, FrameHandlerFactory factory)
    {
        return from(creator, factory, null);
    }

    static WebSocketNegotiator from(WebSocketCreator creator, FrameHandlerFactory factory, Configuration.Customizer customizer)
    {
        return new CreatorNegotiator(creator, factory, customizer);
    }

    @Override
    default void customize(Configuration configurable)
    {
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

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

package org.eclipse.jetty.websocket.core.internal.compress;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

/**
 * Per Message Deflate Compression extension for WebSocket.
 * <p>
 * Attempts to follow <a href="https://tools.ietf.org/html/rfc7692">Compression Extensions for WebSocket</a>
 */
public class PerMessageDeflateExtension extends CompressExtension
{
    private static final Logger LOG = Log.getLogger(PerMessageDeflateExtension.class);

    private ExtensionConfig configRequested;
    private ExtensionConfig configNegotiated;
    private boolean incomingContextTakeover = true;
    private boolean outgoingContextTakeover = true;

    @Override
    public String getName()
    {
        return "permessage-deflate";
    }

    @Override
    CompressionMode getCompressionMode()
    {
        return CompressionMode.MESSAGE;
    }

    @Override
    protected void nextIncomingFrame(Frame frame, Callback callback)
    {
        if (frame.isFin() && !incomingContextTakeover)
        {
            LOG.debug("Incoming Context Reset");
            releaseInflater();
        }
        super.nextIncomingFrame(frame, callback);
    }

    @Override
    protected void nextOutgoingFrame(Frame frame, Callback callback, boolean batch)
    {
        if (frame.isFin() && !outgoingContextTakeover)
        {
            LOG.debug("Outgoing Context Reset");
            releaseDeflater();
        }
        super.nextOutgoingFrame(frame, callback, batch);
    }

    @Override
    public void init(final ExtensionConfig config, WebSocketComponents components)
    {
        configRequested = new ExtensionConfig(config);
        Map<String, String> paramsNegotiated = new HashMap<>();

        for (String key : config.getParameterKeys())
        {
            key = key.trim();
            switch (key)
            {
                case "client_max_window_bits":
                case "server_max_window_bits":
                {
                    // Not supported by Jetty
                    // Don't negotiate these parameters
                    break;
                }
                case "client_no_context_takeover":
                {
                    paramsNegotiated.put("client_no_context_takeover", null);
                    incomingContextTakeover = false;
                    break;
                }
                case "server_no_context_takeover":
                {
                    paramsNegotiated.put("server_no_context_takeover", null);
                    outgoingContextTakeover = false;
                    break;
                }
                default:
                {
                    throw new IllegalArgumentException();
                }
            }
        }

        configNegotiated = new ExtensionConfig(config.getName(), paramsNegotiated);
        LOG.debug("config: outgoingContextTakover={}, incomingContextTakeover={} : {}", outgoingContextTakeover, incomingContextTakeover, this);

        super.init(configNegotiated, components);
    }

    @Override
    public String toString()
    {
        return String.format("%s[requested=\"%s\", negotiated=\"%s\"]",
            getClass().getSimpleName(),
            configRequested.getParameterizedName(),
            configNegotiated.getParameterizedName());
    }
}

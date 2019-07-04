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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.BadPayloadException;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;
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
    private boolean incomingCompressed;

    @Override
    public String getName()
    {
        return "permessage-deflate";
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        // Incoming frames are always non concurrent because
        // they are read and parsed with a single thread, and
        // therefore there is no need for synchronization.

        // This extension requires the RSV1 bit set only in the first frame.
        // Subsequent continuation frames don't have RSV1 set, but are compressed.
        switch (frame.getOpCode())
        {
            case OpCode.TEXT:
            case OpCode.BINARY:
                incomingCompressed = frame.isRsv1();
                break;

            case OpCode.CONTINUATION:
                if (frame.isRsv1())
                    callback.failed(new ProtocolException("Invalid RSV1 set on permessage-deflate CONTINUATION frame"));
                break;
            default:
                break;
        }

        if (OpCode.isControlFrame(frame.getOpCode()) || !incomingCompressed)
        {
            nextIncomingFrame(frame, callback);
            return;
        }

        //TODO fix this to use long instead of int
        if (getWebSocketCoreSession().getMaxFrameSize() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("maxFrameSize too large for ByteAccumulator");

        ByteAccumulator accumulator = new ByteAccumulator((int)getWebSocketCoreSession().getMaxFrameSize());

        try
        {
            ByteBuffer payload = frame.getPayload();
            decompress(accumulator, payload);
            if (frame.isFin())
            {
                decompress(accumulator, TAIL_BYTES_BUF.slice());
            }

            forwardIncoming(frame, callback, accumulator);
        }
        catch (DataFormatException e)
        {
            throw new BadPayloadException(e);
        }

        if (frame.isFin())
            incomingCompressed = false;
    }

    @Override
    protected void nextIncomingFrame(Frame frame, Callback callback)
    {
        if (frame.isFin() && !incomingContextTakeover)
        {
            LOG.debug("Incoming Context Reset");
            decompressCount.set(0);
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
    int getRsvUseMode()
    {
        return RSV_USE_ONLY_FIRST;
    }

    @Override
    int getTailDropMode()
    {
        return TAIL_DROP_FIN_ONLY;
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

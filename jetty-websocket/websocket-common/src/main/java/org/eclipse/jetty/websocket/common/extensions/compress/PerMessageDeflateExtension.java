//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;

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
    public void incomingFrame(Frame frame)
    {
        // Incoming frames are always non concurrent because
        // they are read and parsed with a single thread, and
        // therefore there is no need for synchronization.

        // This extension requires the RSV1 bit set only in the first frame.
        // Subsequent continuation frames don't have RSV1 set, but are compressed.
        if (frame.getType().isData())
        {
            incomingCompressed = frame.isRsv1();
        }

        if (OpCode.isControlFrame(frame.getOpCode()) || !incomingCompressed)
        {
            nextIncomingFrame(frame);
            return;
        }
        
        ByteAccumulator accumulator = newByteAccumulator();
        
        try 
        {
            ByteBuffer payload = frame.getPayload();
            decompress(accumulator, payload);
            if (frame.isFin())
            {
                decompress(accumulator, TAIL_BYTES_BUF.slice());
            }
            
            forwardIncoming(frame, accumulator);
        }
        catch (DataFormatException e)
        {
            throw new BadPayloadException(e);
        }

        if (frame.isFin())
            incomingCompressed = false;
    }

    @Override
    protected void nextIncomingFrame(Frame frame)
    {
        if (frame.isFin() && !incomingContextTakeover)
        {
            LOG.debug("Incoming Context Reset");
            decompressCount.set(0);
            getInflater().reset();
        }
        super.nextIncomingFrame(frame);
    }

    @Override
    protected void nextOutgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        if (frame.isFin() && !outgoingContextTakeover)
        {
            LOG.debug("Outgoing Context Reset");
            getDeflater().reset();
        }
        super.nextOutgoingFrame(frame, callback, batchMode);
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
    public void setConfig(final ExtensionConfig config)
    {
        configRequested = new ExtensionConfig(config);
        configNegotiated = new ExtensionConfig(config.getName());
        
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
                    configNegotiated.setParameter("client_no_context_takeover");
                    switch (getPolicy().getBehavior())
                    {
                        case CLIENT:
                            incomingContextTakeover = false;
                            break;
                        case SERVER:
                            outgoingContextTakeover = false;
                            break;
                    }
                    break;
                }
                case "server_no_context_takeover":
                {
                    configNegotiated.setParameter("server_no_context_takeover");
                    switch (getPolicy().getBehavior())
                    {
                        case CLIENT:
                            outgoingContextTakeover = false;
                            break;
                        case SERVER:
                            incomingContextTakeover = false;
                            break;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalArgumentException();
                }
            }
        }
        
        LOG.debug("config: outgoingContextTakover={}, incomingContextTakeover={} : {}", outgoingContextTakeover, incomingContextTakeover, this);

        super.setConfig(configNegotiated);
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

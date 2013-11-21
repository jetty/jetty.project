//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;
import org.eclipse.jetty.websocket.common.frames.DataFrame;

/**
 * Per Message Deflate Compression extension for WebSocket.
 * <p>
 * Attempts to follow <a href="https://tools.ietf.org/html/draft-ietf-hybi-permessage-compression-12">draft-ietf-hybi-permessage-compression-12</a>
 */
public class PerMessageDeflateExtension extends AbstractExtension
{
    private static final boolean BFINAL_HACK = Boolean.parseBoolean(System.getProperty("jetty.websocket.bfinal.hack","true"));
    private static final Logger LOG = Log.getLogger(PerMessageDeflateExtension.class);

    private static final int OVERHEAD = 64;
    /** Tail Bytes per Spec */
    private static final byte[] TAIL = new byte[]
    { 0x00, 0x00, (byte)0xFF, (byte)0xFF };
    private int bufferSize = 64 * 1024;
    private Deflater compressor;
    private Inflater decompressor;

    @Override
    public String getName()
    {
        return "permessage-deflate";
    }

    @Override
    public synchronized void incomingFrame(Frame frame)
    {
        if (OpCode.isControlFrame(frame.getOpCode()) || !frame.isRsv1())
        {
            // Cannot modify incoming control frames or ones with RSV1 set.
            nextIncomingFrame(frame);
            return;
        }

        if (!frame.hasPayload())
        {
            // no payload? nothing to do.
            nextIncomingFrame(frame);
            return;
        }

        // Prime the decompressor
        ByteBuffer payload = frame.getPayload();
        int inlen = payload.remaining();
        byte compressed[] = new byte[inlen + TAIL.length];
        payload.get(compressed,0,inlen);
        System.arraycopy(TAIL,0,compressed,inlen,TAIL.length);
        decompressor.setInput(compressed,0,compressed.length);

        // Since we don't track text vs binary vs continuation state, just grab whatever is the greater value.
        int maxSize = Math.max(getPolicy().getMaxTextMessageSize(),getPolicy().getMaxBinaryMessageBufferSize());
        ByteAccumulator accumulator = new ByteAccumulator(maxSize);

        DataFrame out = new DataFrame(frame);
        out.setRsv1(false); // Unset RSV1

        // Perform decompression
        while (decompressor.getRemaining() > 0 && !decompressor.finished())
        {
            byte outbuf[] = new byte[Math.min(inlen * 2,bufferSize)];
            try
            {
                int len = decompressor.inflate(outbuf);
                if (len == 0)
                {
                    if (decompressor.needsInput())
                    {
                        throw new BadPayloadException("Unable to inflate frame, not enough input on frame");
                    }
                    if (decompressor.needsDictionary())
                    {
                        throw new BadPayloadException("Unable to inflate frame, frame erroneously says it needs a dictionary");
                    }
                }
                if (len > 0)
                {
                    accumulator.addBuffer(outbuf,0,len);
                }
            }
            catch (DataFormatException e)
            {
                LOG.warn(e);
                throw new BadPayloadException(e);
            }
        }

        // Forward on the frame
        out.setPayload(accumulator.getByteBuffer(getBufferPool()));
        nextIncomingFrame(out);
    }

    /**
     * Indicates use of RSV1 flag for indicating deflation is in use.
     */
    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    @Override
    public synchronized void outgoingFrame(Frame frame, WriteCallback callback)
    {
        if (OpCode.isControlFrame(frame.getOpCode()))
        {
            // skip, cannot compress control frames.
            nextOutgoingFrame(frame,callback);
            return;
        }

        if (!frame.hasPayload())
        {
            // pass through, nothing to do
            nextOutgoingFrame(frame,callback);
            return;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("outgoingFrame({}, {}) - {}",OpCode.name(frame.getOpCode()),callback != null?callback.getClass().getSimpleName():"<null>",
                    BufferUtil.toDetailString(frame.getPayload()));
        }

        // Prime the compressor
        byte uncompressed[] = BufferUtil.toArray(frame.getPayload());

        // Perform the compression
        if (!compressor.finished())
        {
            compressor.setInput(uncompressed,0,uncompressed.length);
            byte compressed[] = new byte[uncompressed.length + OVERHEAD];

            while (!compressor.needsInput())
            {
                int len = compressor.deflate(compressed,0,compressed.length,Deflater.SYNC_FLUSH);
                ByteBuffer outbuf = getBufferPool().acquire(len,true);
                BufferUtil.clearToFill(outbuf);

                if (len > 0)
                {
                    outbuf.put(compressed,0,len - 4);
                }

                BufferUtil.flipToFlush(outbuf,0);

                if (len > 0 && BFINAL_HACK)
                {
                    /*
                     * Per the spec, it says that BFINAL 1 or 0 are allowed.
                     * 
                     * However, Java always uses BFINAL 1, whereas the browsers Chromium and Safari fail to decompress when it encounters BFINAL 1.
                     * 
                     * This hack will always set BFINAL 0
                     */
                    byte b0 = outbuf.get(0);
                    if ((b0 & 1) != 0) // if BFINAL 1
                    {
                        outbuf.put(0,(b0 ^= 1)); // flip bit to BFINAL 0
                    }
                }

                DataFrame out = new DataFrame(frame);
                out.setRsv1(true);
                out.setBufferPool(getBufferPool());
                out.setPayload(outbuf);

                if (!compressor.needsInput())
                {
                    // this is fragmented
                    out.setFin(false);
                    nextOutgoingFrame(out,null); // non final frames have no callback
                }
                else
                {
                    // pass through the callback
                    nextOutgoingFrame(out,callback);
                }
            }
        }
    }

    @Override
    public void setConfig(final ExtensionConfig config)
    {
        ExtensionConfig negotiated = new ExtensionConfig(config.getName());

        boolean nowrap = true;
        compressor = new Deflater(Deflater.BEST_COMPRESSION,nowrap);
        compressor.setStrategy(Deflater.DEFAULT_STRATEGY);

        decompressor = new Inflater(nowrap);

        for (String key : config.getParameterKeys())
        {
            key = key.trim();
            String value = config.getParameter(key,null);
            switch (key)
            {
                case "c2s_max_window_bits":
                    negotiated.setParameter("s2c_max_window_bits",value);
                    break;
                case "c2s_no_context_takeover":
                    negotiated.setParameter("s2c_no_context_takeover",value);
                    break;
                case "s2c_max_window_bits":
                    negotiated.setParameter("c2s_max_window_bits",value);
                    break;
                case "s2c_no_context_takeover":
                    negotiated.setParameter("c2s_no_context_takeover",value);
                    break;
            }
        }

        super.setConfig(negotiated);
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(this.getClass().getSimpleName());
        str.append('[');
        str.append(']');
        return str.toString();
    }
}

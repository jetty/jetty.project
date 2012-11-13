//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;

/**
 * Deflate Compression Method
 */
public class DeflateCompressionMethod implements CompressionMethod
{
    private static class DeflaterProcess implements CompressionMethod.Process
    {
        private static final boolean BFINAL_HACK = Boolean.parseBoolean(System.getProperty("jetty.websocket.bfinal.hack","true"));

        private final Deflater deflater;
        private int bufferSize = DEFAULT_BUFFER_SIZE;

        public DeflaterProcess(boolean nowrap)
        {
            deflater = new Deflater(Deflater.BEST_COMPRESSION,nowrap);
            deflater.setStrategy(Deflater.DEFAULT_STRATEGY);
        }

        @Override
        public void begin()
        {
            deflater.reset();
        }

        @Override
        public void end()
        {
            deflater.reset();
        }

        @Override
        public void input(ByteBuffer input)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("input: {}",BufferUtil.toDetailString(input));
            }

            // Set the data that is uncompressed to the deflater
            byte raw[] = BufferUtil.toArray(input);
            deflater.setInput(raw,0,raw.length);
            deflater.finish();
        }

        @Override
        public boolean isDone()
        {
            return deflater.finished();
        }

        @Override
        public ByteBuffer process()
        {
            // prepare the output buffer
            ByteBuffer buf = ByteBuffer.allocate(bufferSize);
            BufferUtil.clearToFill(buf);

            while (!deflater.finished())
            {
                byte out[] = new byte[bufferSize];
                int len = deflater.deflate(out,0,out.length,Deflater.SYNC_FLUSH);

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Deflater: finished={}, needsInput={}, len={}",deflater.finished(),deflater.needsInput(),len);
                }

                buf.put(out,0,len);
            }
            BufferUtil.flipToFlush(buf,0);

            if (BFINAL_HACK)
            {
                /*
                 * Per the spec, it says that BFINAL 1 or 0 are allowed.
                 * 
                 * However, Java always uses BFINAL 1, whereas the browsers Chromium and Safari fail to decompress when it encounters BFINAL 1.
                 * 
                 * This hack will always set BFINAL 0
                 */
                byte b0 = buf.get(0);
                if ((b0 & 1) != 0) // if BFINAL 1
                {
                    buf.put(0,(b0 ^= 1)); // flip bit to BFINAL 0
                }
            }
            return buf;
        }

        public void setBufferSize(int bufferSize)
        {
            this.bufferSize = bufferSize;
        }
    }

    private static class InflaterProcess implements CompressionMethod.Process
    {
        /** Tail Bytes per Spec */
        private static final byte[] TAIL = new byte[]
                { 0x00, 0x00, (byte)0xFF, (byte)0xFF };
        private final Inflater inflater;
        private int bufferSize = DEFAULT_BUFFER_SIZE;

        public InflaterProcess(boolean nowrap) {
            inflater = new Inflater(nowrap);
        }

        @Override
        public void begin()
        {
            inflater.reset();
        }

        @Override
        public void end()
        {
            inflater.reset();
        }

        @Override
        public void input(ByteBuffer input)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("inflate: {}",BufferUtil.toDetailString(input));
                LOG.debug("Input Data: {}",TypeUtil.toHexString(BufferUtil.toArray(input)));
            }

            // Set the data that is compressed (+ TAIL) to the inflater
            int len = input.remaining() + 4;
            byte raw[] = new byte[len];
            int inlen = input.remaining();
            input.slice().get(raw,0,inlen);
            System.arraycopy(TAIL,0,raw,inlen,TAIL.length);
            inflater.setInput(raw,0,raw.length);
        }

        @Override
        public boolean isDone()
        {
            return (inflater.getRemaining() <= 0) || inflater.finished();
        }

        @Override
        public ByteBuffer process()
        {
            // Establish place for inflated data
            byte buf[] = new byte[bufferSize];
            try
            {
                int inflated = inflater.inflate(buf);
                if (inflated == 0)
                {
                    return null;
                }

                ByteBuffer ret = BufferUtil.toBuffer(buf,0,inflated);

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("uncompressed={}",BufferUtil.toDetailString(ret));
                }

                return ret;
            }
            catch (DataFormatException e)
            {
                LOG.warn(e);
                throw new BadPayloadException(e);
            }
        }

        public void setBufferSize(int bufferSize)
        {
            this.bufferSize = bufferSize;
        }
    }

    private static final int DEFAULT_BUFFER_SIZE = 61*1024;

    private static final Logger LOG = Log.getLogger(DeflateCompressionMethod.class);

    private int bufferSize = 64 * 1024;
    private final DeflaterProcess compress;
    private final InflaterProcess decompress;

    public DeflateCompressionMethod()
    {
        /*
         * Specs specify that head/tail of deflate are not to be present.
         * 
         * So lets not use the wrapped format of bytes.
         * 
         * Setting nowrap to true prevents the Deflater from writing the head/tail bytes and the Inflater from expecting the head/tail bytes.
         */
        boolean nowrap = true;

        this.compress = new DeflaterProcess(nowrap);
        this.decompress = new InflaterProcess(nowrap);
    }

    @Override
    public Process compress()
    {
        return compress;
    }

    @Override
    public Process decompress()
    {
        return decompress;
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    public void setBufferSize(int size)
    {
        if (size < 64)
        {
            throw new IllegalArgumentException("Buffer Size [" + size + "] cannot be less than 64 bytes");
        }
        this.bufferSize = size;
        this.compress.setBufferSize(bufferSize);
        this.decompress.setBufferSize(bufferSize);
    }
}

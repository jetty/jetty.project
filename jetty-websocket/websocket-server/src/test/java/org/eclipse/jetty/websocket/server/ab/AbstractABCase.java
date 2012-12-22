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

package org.eclipse.jetty.websocket.server.ab;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.server.SimpleServletServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

public abstract class AbstractABCase
{
    protected static final byte FIN = (byte)0x80;
    protected static final byte NOFIN = 0x00;
    private static final byte MASKED_BIT = (byte)0x80;
    protected static final byte[] MASK =
    { 0x12, 0x34, 0x56, 0x78 };

    protected static Generator strictGenerator;
    protected static Generator laxGenerator;
    protected static SimpleServletServer server;

    @BeforeClass
    public static void initGenerators()
    {
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        ByteBufferPool bufferPool = new MappedByteBufferPool();
        strictGenerator = new Generator(policy,bufferPool,true);
        laxGenerator = new Generator(policy,bufferPool,false);
    }

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new ABServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    public static String toUtf8String(byte[] buf)
    {
        String raw = StringUtil.toUTF8String(buf,0,buf.length);
        StringBuilder ret = new StringBuilder();
        int len = raw.length();
        for (int i = 0; i < len; i++)
        {
            int codepoint = raw.codePointAt(i);
            if (Character.isUnicodeIdentifierPart(codepoint))
            {
                ret.append(String.format("\\u%04X",codepoint));
            }
            else
            {
                ret.append(Character.toChars(codepoint));
            }
        }
        return ret.toString();
    }

    @Rule
    public TestName testname = new TestName();

    protected void enableStacks(Class<?> clazz, boolean enabled)
    {
        StdErrLog log = StdErrLog.getLogger(clazz);
        log.setHideStacks(!enabled);
    }

    public Generator getLaxGenerator()
    {
        return laxGenerator;
    }

    public SimpleServletServer getServer()
    {
        return server;
    }

    protected byte[] masked(final byte[] data)
    {
        int len = data.length;
        byte ret[] = new byte[len];
        System.arraycopy(data,0,ret,0,len);
        for (int i = 0; i < len; i++)
        {
            ret[i] ^= MASK[i % 4];
        }
        return ret;
    }

    private void putLength(ByteBuffer buf, int length, boolean masked)
    {
        if (length < 0)
        {
            throw new IllegalArgumentException("Length cannot be negative");
        }
        byte b = (masked?MASKED_BIT:0x00);

        // write the uncompressed length
        if (length > 0xFF_FF)
        {
            buf.put((byte)(b | 0x7F));
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)((length >> 24) & 0xFF));
            buf.put((byte)((length >> 16) & 0xFF));
            buf.put((byte)((length >> 8) & 0xFF));
            buf.put((byte)(length & 0xFF));
        }
        else if (length >= 0x7E)
        {
            buf.put((byte)(b | 0x7E));
            buf.put((byte)(length >> 8));
            buf.put((byte)(length & 0xFF));
        }
        else
        {
            buf.put((byte)(b | length));
        }
    }

    public void putMask(ByteBuffer buf)
    {
        buf.put(MASK);
    }

    public void putPayloadLength(ByteBuffer buf, int length)
    {
        putLength(buf,length,true);
    }
}

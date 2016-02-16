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

package org.eclipse.jetty.websocket.common.test;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Parser;

public class UnitParser extends Parser
{
    public UnitParser()
    {
        this(WebSocketPolicy.newServerPolicy());
    }

    public UnitParser(WebSocketPolicy policy)
    {
        super(policy,new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged()));
    }

    private void parsePartial(ByteBuffer buf, int numBytes)
    {
        int len = Math.min(numBytes,buf.remaining());
        byte arr[] = new byte[len];
        buf.get(arr,0,len);
        this.parse(ByteBuffer.wrap(arr));
    }

    /**
     * Parse a buffer, but do so in a quiet fashion, squelching stacktraces if encountered.
     * <p>
     * Use if you know the parse will cause an exception and just don't wnat to make the test console all noisy.
     * @param buf the buffer to parse
     */
    public void parseQuietly(ByteBuffer buf)
    {
        try (StacklessLogging suppress = new StacklessLogging(Parser.class))
        {
            parse(buf);
        }
        catch (Exception ignore)
        {
            /* ignore */
        }
    }

    public void parseSlowly(ByteBuffer buf, int segmentSize)
    {
        while (buf.remaining() > 0)
        {
            parsePartial(buf,segmentSize);
        }
    }
}

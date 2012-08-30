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

package org.eclipse.jetty.websocket.protocol;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;

public class UnitParser extends Parser
{
    public UnitParser()
    {
        super(WebSocketPolicy.newServerPolicy());
    }

    private void parsePartial(ByteBuffer buf, int numBytes)
    {
        int len = Math.min(numBytes,buf.remaining());
        byte arr[] = new byte[len];
        buf.get(arr,0,len);
        this.parse(ByteBuffer.wrap(arr));
    }

    public void parseSlowly(ByteBuffer buf, int segmentSize)
    {
        while (buf.remaining() > 0)
        {
            parsePartial(buf,segmentSize);
        }
    }
}

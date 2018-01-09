//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.tests.RawFrameBuilder;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;

public class BadCloseSocket extends UntrustedWSEndpoint
{
    private static final Logger LOG = Log.getLogger(BadCloseSocket.class);

    public BadCloseSocket(String id)
    {
        super(id);
        this.setOnTextFunction((untrustedWSSession, message) -> {
            LOG.debug("onTextMessage({})", message);
            try
            {
                byte reason[] = new byte[400];
                Arrays.fill(reason, (byte) 'x');
                ByteBuffer bad = ByteBuffer.allocate(500);
                RawFrameBuilder.putOpFin(bad, OpCode.CLOSE, true);
                RawFrameBuilder.putLength(bad, reason.length + 2, false);
                bad.putShort((short) StatusCode.NORMAL.getCode());
                bad.put(reason);
                BufferUtil.flipToFlush(bad, 0);
                untrustedWSSession.getUntrustedConnection().writeRaw(bad);
            }
            catch (IOException e)
            {
                throw new RuntimeException("Unable to issue bad control frame", e);
            }
            return null;
        });
    }
}

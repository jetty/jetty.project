//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.common.io.http.HttpResponseHeaderParseListener;

public class ClientUpgradeResponse extends UpgradeResponse implements HttpResponseHeaderParseListener
{
    private ByteBuffer remainingBuffer;

    public ClientUpgradeResponse()
    {
        super();
    }

    public ByteBuffer getRemainingBuffer()
    {
        return remainingBuffer;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        throw new UnsupportedOperationException("Not supported on client implementation");
    }

    @Override
    public void setRemainingBuffer(ByteBuffer remainingBuffer)
    {
        this.remainingBuffer = remainingBuffer;
    }
}

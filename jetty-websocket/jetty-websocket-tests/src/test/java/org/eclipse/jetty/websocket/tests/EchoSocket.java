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

package org.eclipse.jetty.websocket.tests;

import java.nio.ByteBuffer;

public class EchoSocket extends EventSocket
{
    @Override
    public void onWebSocketText(String message)
    {
        super.onWebSocketText(message);
        session.getRemote().sendString(message, null);
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int length)
    {
        super.onWebSocketBinary(payload, offset, length);
        session.getRemote().sendBytes(ByteBuffer.wrap(payload, offset, length), null);
    }
}

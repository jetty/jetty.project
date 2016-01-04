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

package org.eclipse.jetty.websocket.common.annotations;

import java.io.InputStream;

import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Invalid Socket: Annotate 2 methods with interest in Binary Messages.
 */
@WebSocket
public class BadDuplicateBinarySocket
{
    /**
     * First method
     * @param payload the payload
     * @param offset the offset
     * @param len the len
     */
    @OnWebSocketMessage
    public void binMe(byte[] payload, int offset, int len)
    {
        /* ignore */
    }

    /**
     * Second method
     * @param stream the input stream
     */
    @OnWebSocketMessage
    public void streamMe(InputStream stream)
    {
        /* ignore */
    }
}

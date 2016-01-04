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

package org.eclipse.jetty.websocket.server.ab;

import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Servlet with bigger message policy sizes, with registered simple echo socket.
 */
@SuppressWarnings("serial")
public class ABServlet extends WebSocketServlet
{
    private static final int KBYTE = 1024;
    private static final int MBYTE = KBYTE * KBYTE;

    @Override
    public void configure(WebSocketServletFactory factory)
    {
        // Test cases 9.x uses BIG frame sizes, let policy handle them.
        int bigFrameSize = 20 * MBYTE;

        factory.getPolicy().setMaxTextMessageSize(bigFrameSize);
        factory.getPolicy().setMaxBinaryMessageSize(bigFrameSize);

        factory.register(ABSocket.class);
    }
}

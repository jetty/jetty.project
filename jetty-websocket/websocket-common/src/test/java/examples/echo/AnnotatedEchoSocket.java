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

package examples.echo;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Example EchoSocket using Annotations.
 */
@WebSocket(maxTextMessageSize = 64 * 1024)
public class AnnotatedEchoSocket
{
    @OnWebSocketMessage
    public void onText(Session session, String message)
    {
        if (session.isOpen())
        {
            System.out.printf("Echoing back message [%s]%n",message);
            // echo the message back
            session.getRemote().sendString(message,null);
        }
    }
}

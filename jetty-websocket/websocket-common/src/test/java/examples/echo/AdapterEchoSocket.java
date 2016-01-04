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

import java.io.IOException;

import org.eclipse.jetty.websocket.api.WebSocketAdapter;

/**
 * Example EchoSocket using Adapter.
 */
public class AdapterEchoSocket extends WebSocketAdapter
{
    @Override
    public void onWebSocketText(String message)
    {
        if (isConnected())
        {
            try
            {
                System.out.printf("Echoing back message [%s]%n",message);
                // echo the message back
                getRemote().sendString(message);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
    }
}

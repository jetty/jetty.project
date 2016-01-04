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

package examples;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.IO;

@ServerEndpoint("/echo")
public class StreamingEchoSocket
{
    @OnMessage
    public void onMessage(Session session, Reader reader)
    {
        try (Writer writer = session.getBasicRemote().getSendWriter())
        {
            IO.copy(reader,writer);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

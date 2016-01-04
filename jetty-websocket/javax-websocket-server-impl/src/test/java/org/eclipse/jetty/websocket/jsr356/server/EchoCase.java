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

package org.eclipse.jetty.websocket.jsr356.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.server.ServerEndpoint;

public class EchoCase
{
    public static class PartialBinary
    {
        ByteBuffer part;

        boolean fin;
        public PartialBinary(ByteBuffer part, boolean fin)
        {
            this.part = part;
            this.fin = fin;
        }
    }

    public static class PartialText
    {
        String part;

        boolean fin;
        public PartialText(String part, boolean fin)
        {
            this.part = part;
            this.fin = fin;
        }
    }

    public static EchoCase add(List<EchoCase[]> data, Class<?> serverPojo)
    {
        EchoCase ecase = new EchoCase();
        ecase.serverPojo = serverPojo;
        data.add(new EchoCase[]
        { ecase });
        ServerEndpoint endpoint = serverPojo.getAnnotation(ServerEndpoint.class);
        ecase.path = endpoint.value();
        return ecase;
    }

    public static EchoCase add(List<EchoCase[]> data, Class<?> serverPojo, String path)
    {
        EchoCase ecase = new EchoCase();
        ecase.serverPojo = serverPojo;
        ecase.path = path;
        data.add(new EchoCase[]
        { ecase });
        return ecase;
    }

    // The websocket server pojo to test against
    public Class<?> serverPojo;
    // The (relative) URL path to hit
    public String path;
    // The messages to transmit
    public List<Object> messages = new ArrayList<>();
    // The expected Strings (that are echoed back)
    public List<String> expectedStrings = new ArrayList<>();

    public EchoCase addMessage(Object msg)
    {
        messages.add(msg);
        return this;
    }

    public EchoCase addSplitMessage(ByteBuffer... parts)
    {
        int len = parts.length;
        for (int i = 0; i < len; i++)
        {
            addMessage(new PartialBinary(parts[i],(i == (len-1))));
        }
        return this;
    }

    public EchoCase addSplitMessage(String... parts)
    {
        int len = parts.length;
        for (int i = 0; i < len; i++)
        {
            addMessage(new PartialText(parts[i],(i == (len-1))));
        }
        return this;
    }

    public EchoCase expect(String message)
    {
        expectedStrings.add(message);
        return this;
    }

    public EchoCase requestPath(String path)
    {
        this.path = path;
        return this;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("EchoCase['");
        str.append(path);
        str.append("',").append(serverPojo.getName());
        str.append(",messages[").append(messages.size());
        str.append("]=");
        boolean delim = false;
        for (Object msg : messages)
        {
            if (delim)
            {
                str.append(",");
            }
            if (msg instanceof String)
            {
                str.append("'").append(msg).append("'");
            }
            else
            {
                str.append("(").append(msg.getClass().getName()).append(")");
                str.append(msg);
            }
            delim = true;
        }
        str.append("]");
        return str.toString();
    }

    public int getMessageCount()
    {
        int messageCount = 0;
        for (Object msg : messages)
        {
            if (msg instanceof PartialText)
            {
                PartialText pt = (PartialText)msg;
                if (pt.fin)
                {
                    messageCount++;
                }
            }
            else if (msg instanceof PartialBinary)
            {
                PartialBinary pb = (PartialBinary)msg;
                if (pb.fin)
                {
                    messageCount++;
                }
            }
            else
            {
                messageCount++;
            }
        }
        
        return messageCount;
    }
}

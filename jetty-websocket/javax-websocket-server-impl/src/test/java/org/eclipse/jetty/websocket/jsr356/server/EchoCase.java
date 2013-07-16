//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.List;

import javax.websocket.server.ServerEndpoint;

public class EchoCase
{
    public static EchoCase add(List<EchoCase[]> data, Class<?> serverPojo, String path)
    {
        EchoCase ecase = new EchoCase();
        ecase.serverPojo = serverPojo;
        ecase.path = path;
        data.add(new EchoCase[]
        { ecase });
        return ecase;
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
        return String.format("EchoCase['%s',%s,messages=%d]",path,serverPojo.getName(),messages.size());
    }
}
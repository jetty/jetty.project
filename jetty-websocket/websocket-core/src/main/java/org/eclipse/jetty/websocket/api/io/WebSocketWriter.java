//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api.io;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.io.WebSocketSession;

public class WebSocketWriter extends Writer
{
    private final Charset charset = StringUtil.__UTF8_CHARSET;
    private final WebSocketSession conn;

    public WebSocketWriter(WebSocketConnection conn)
    {
        if (conn instanceof WebSocketSession)
        {
            this.conn = (WebSocketSession)conn;
        }
        else
        {
            throw new IllegalArgumentException("WebSocketConnection must implement internal WebSocketSession interface");
        }
    }

    @Override
    public void close() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void flush() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException
    {
        // TODO Auto-generated method stub

    }
}

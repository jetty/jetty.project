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

import org.eclipse.jetty.websocket.api.WebSocketConnection;

public class WebSocketWriter extends Writer
{
    private final Charset charset = Charset.forName("UTF-8");
    private final WebSocketConnection conn;

    public WebSocketWriter(WebSocketConnection conn)
    {
        this.conn = conn;
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

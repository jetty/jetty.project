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

package org.eclipse.jetty.client.util;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.UnsupportedCharsetException;

import org.eclipse.jetty.client.api.Response;

public class BufferingResponseListener extends Response.Listener.Empty
{
    private final int maxLength;
    private volatile byte[] buffer = new byte[0];

    public BufferingResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    public BufferingResponseListener(int maxLength)
    {
        this.maxLength = maxLength;
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        long newLength = buffer.length + content.remaining();
        if (newLength > maxLength)
            throw new IllegalStateException("Buffering capacity exceeded");

        byte[] newBuffer = new byte[(int)newLength];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        content.get(newBuffer, buffer.length, content.remaining());
        buffer = newBuffer;
    }

    public byte[] getContent()
    {
        return buffer;
    }

    public String getContent(String encoding)
    {
        try
        {
            return new String(getContent(), encoding);
        }
        catch (UnsupportedEncodingException x)
        {
            throw new UnsupportedCharsetException(encoding);
        }
    }
}

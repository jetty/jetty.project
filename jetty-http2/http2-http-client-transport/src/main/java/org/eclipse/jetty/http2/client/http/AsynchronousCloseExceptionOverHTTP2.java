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


package org.eclipse.jetty.http2.client.http;

import java.io.UnsupportedEncodingException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

public class AsynchronousCloseExceptionOverHTTP2 extends AsynchronousCloseException
{
    private static final long serialVersionUID = 8304961091263163615L;

    private byte[] bytes;
    
    public AsynchronousCloseExceptionOverHTTP2(byte[] bytes) {
        this.bytes = bytes;
    }
    
    public byte[] getContent() {
        return bytes;
    }
    
    public String getContentToString() {
        return new String(getContent(), StandardCharsets.UTF_8);
    }
    
    public String getContentToString(final String encoding) {
        if (encoding == null)
        {
            return getContentToString();
        }
        else
        {
            try
            {
                return new String(getContent(), encoding);
            }
            catch (UnsupportedEncodingException e)
            {
                throw new UnsupportedCharsetException(encoding);
            }
        }
    }
}

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


package org.eclipse.jetty.http;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Arrays;


/* ------------------------------------------------------------ */
/**
 */
public class Http1FieldPreEncoder implements HttpFieldPreEncoder
{
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.HttpFieldPreEncoder#getHttpVersion()
     */
    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_1_0;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.HttpFieldPreEncoder#getEncodedField(org.eclipse.jetty.http.HttpHeader, java.lang.String, java.lang.String)
     */
    @Override
    public byte[] getEncodedField(HttpHeader header, String headerString, String value)
    {
        if (header!=null)
        {
            int cbl=header.getBytesColonSpace().length;
            byte[] bytes=Arrays.copyOf(header.getBytesColonSpace(), cbl+value.length()+2);
            System.arraycopy(value.getBytes(ISO_8859_1),0,bytes,cbl,value.length());
            bytes[bytes.length-2]=(byte)'\r';
            bytes[bytes.length-1]=(byte)'\n';
            return bytes;
        }

        byte[] n=headerString.getBytes(ISO_8859_1);
        byte[] v=value.getBytes(ISO_8859_1);
        byte[] bytes=Arrays.copyOf(n,n.length+2+v.length+2);
        bytes[n.length]=(byte)':';
        bytes[n.length]=(byte)' ';
        bytes[bytes.length-2]=(byte)'\r';
        bytes[bytes.length-1]=(byte)'\n';

        return bytes;
    }
}

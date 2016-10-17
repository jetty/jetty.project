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


/* ------------------------------------------------------------ */
public class CompressedContentFormat
{
    public static final CompressedContentFormat GZIP = new CompressedContentFormat("gzip", ".gz");
    public static final CompressedContentFormat BR = new CompressedContentFormat("br", ".br");
    public static final CompressedContentFormat[] NONE = new CompressedContentFormat[0];

    public final String _encoding;
    public final String _extension;
    public final String _etag;
    public final String _etagQuote;
    public final PreEncodedHttpField _contentEncoding;

    public CompressedContentFormat(String encoding, String extension)
    {
        _encoding = encoding;
        _extension = extension;
        _etag = "--" + encoding;
        _etagQuote = _etag + "\"";
        _contentEncoding = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, encoding);
    }
    
    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof CompressedContentFormat))
            return false;
        CompressedContentFormat ccf = (CompressedContentFormat)o;
        if (_encoding==null && ccf._encoding!=null)
            return false;
        if (_extension==null && ccf._extension!=null)
            return false;
        
        return  _encoding.equalsIgnoreCase(ccf._encoding) && _extension.equalsIgnoreCase(ccf._extension);
    }

    public static boolean tagEquals(String etag, String tag)
    {
        if (etag.equals(tag))
            return true;

        int dashdash = tag.indexOf("--");
        if (dashdash>0)
            return etag.regionMatches(0,tag,0,dashdash-2);
        return false;
    }
}

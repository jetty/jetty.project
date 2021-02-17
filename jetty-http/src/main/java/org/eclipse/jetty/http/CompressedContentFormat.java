//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Objects;

import org.eclipse.jetty.util.StringUtil;

public class CompressedContentFormat
{
    /**
     * The separator within an etag used to indicate a compressed variant. By default the separator is "--"
     * So etag for compressed resource that normally has an etag of <code>W/"28c772d6"</code>
     * is <code>W/"28c772d6--gzip"</code>.  The separator may be changed by the
     * "org.eclipse.jetty.http.CompressedContentFormat.ETAG_SEPARATOR" System property. If changed, it should be changed to a string
     * that will not be found in a normal etag or at least is very unlikely to be a substring of a normal etag.
     */
    public static final String ETAG_SEPARATOR = System.getProperty(CompressedContentFormat.class.getName() + ".ETAG_SEPARATOR", "--");

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
        _encoding = StringUtil.asciiToLowerCase(encoding);
        _extension = StringUtil.asciiToLowerCase(extension);
        _etag = ETAG_SEPARATOR + encoding;
        _etagQuote = _etag + "\"";
        _contentEncoding = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, encoding);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof CompressedContentFormat))
            return false;
        CompressedContentFormat ccf = (CompressedContentFormat)o;
        return Objects.equals(_encoding, ccf._encoding) && Objects.equals(_extension, ccf._extension);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(_encoding, _extension);
    }

    public static boolean tagEquals(String etag, String tag)
    {
        if (etag.equals(tag))
            return true;

        int separator = tag.lastIndexOf(ETAG_SEPARATOR);
        if (separator > 0 && separator == etag.length() - 1)
            return etag.regionMatches(0, tag, 0, separator);
        return false;
    }
}

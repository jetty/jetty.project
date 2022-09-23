//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.util.Objects;

import org.eclipse.jetty.util.StringUtil;

public class CompressedContentFormat
{
    public static final CompressedContentFormat GZIP = new CompressedContentFormat("gzip", ".gz");
    public static final CompressedContentFormat BR = new CompressedContentFormat("br", ".br");
    public static final CompressedContentFormat[] NONE = new CompressedContentFormat[0];

    private final String _encoding;
    private final String _extension;
    private final String _etagSuffix;
    private final String _etagSuffixQuote;
    private final PreEncodedHttpField _contentEncoding;

    public CompressedContentFormat(String encoding, String extension)
    {
        _encoding = StringUtil.asciiToLowerCase(encoding);
        _extension = StringUtil.asciiToLowerCase(extension);
        _etagSuffix = StringUtil.isEmpty(EtagUtils.ETAG_SEPARATOR) ? "" : (EtagUtils.ETAG_SEPARATOR + _encoding);
        _etagSuffixQuote = _etagSuffix + "\"";
        _contentEncoding = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, _encoding);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof CompressedContentFormat ccf))
            return false;
        return Objects.equals(_encoding, ccf._encoding) && Objects.equals(_extension, ccf._extension);
    }

    public String getEncoding()
    {
        return _encoding;
    }

    public String getExtension()
    {
        return _extension;
    }

    public String getEtagSuffix()
    {
        return _etagSuffix;
    }

    public HttpField getContentEncoding()
    {
        return _contentEncoding;
    }

    /** Get an etag with suffix that represents this compressed type.
     * @param etag An etag
     * @return An etag with compression suffix, or the etag itself if no suffix is configured.
     */
    public String etag(String etag)
    {
        if (StringUtil.isEmpty(EtagUtils.ETAG_SEPARATOR))
            return etag;
        int end = etag.length() - 1;
        if (etag.charAt(end) == '"')
            return etag.substring(0, end) + _etagSuffixQuote;
        return etag + _etagSuffix;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(_encoding, _extension);
    }

    public String stripSuffixes(String etagsList)
    {
        if (StringUtil.isEmpty(EtagUtils.ETAG_SEPARATOR))
            return etagsList;

        // This is a poor implementation that ignores list and tag structure
        while (true)
        {
            int i = etagsList.lastIndexOf(_etagSuffix);
            if (i < 0)
                return etagsList;
            etagsList = etagsList.substring(0, i) + etagsList.substring(i + _etagSuffix.length());
        }
    }

    @Override
    public String toString()
    {
        return _encoding;
    }
}

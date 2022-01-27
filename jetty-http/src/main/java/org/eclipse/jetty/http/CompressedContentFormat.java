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

import org.eclipse.jetty.util.QuotedStringTokenizer;
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

    private final String _encoding;
    private final String _extension;
    private final String _etagSuffix;
    private final String _etagSuffixQuote;
    private final PreEncodedHttpField _contentEncoding;

    public CompressedContentFormat(String encoding, String extension)
    {
        _encoding = StringUtil.asciiToLowerCase(encoding);
        _extension = StringUtil.asciiToLowerCase(extension);
        _etagSuffix = StringUtil.isEmpty(ETAG_SEPARATOR) ? "" : (ETAG_SEPARATOR + _encoding);
        _etagSuffixQuote = _etagSuffix + "\"";
        _contentEncoding = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, _encoding);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof CompressedContentFormat))
            return false;
        CompressedContentFormat ccf = (CompressedContentFormat)o;
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
        if (StringUtil.isEmpty(ETAG_SEPARATOR))
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

    /** Check etags for equality, accounting for quoting and compression suffixes.
     * @param etag An etag without a compression suffix
     * @param etagWithSuffix An etag optionally with a compression suffix.
     * @return True if the tags are equal.
     */
    public static boolean tagEquals(String etag, String etagWithSuffix)
    {
        // Handle simple equality
        if (etag.equals(etagWithSuffix))
            return true;

        // If no separator defined, then simple equality is only possible positive
        if (StringUtil.isEmpty(ETAG_SEPARATOR))
            return false;

        // Are both tags quoted?
        boolean etagQuoted = etag.endsWith("\"");
        boolean etagSuffixQuoted = etagWithSuffix.endsWith("\"");

        // Look for a separator
        int separator = etagWithSuffix.lastIndexOf(ETAG_SEPARATOR);

        // If both tags are quoted the same (the norm) then any difference must be the suffix
        if (etagQuoted == etagSuffixQuoted)
            return separator > 0 && etag.regionMatches(0, etagWithSuffix, 0, separator);

        // If either tag is weak then we can't match because weak tags must be quoted
        if (etagWithSuffix.startsWith("W/") || etag.startsWith("W/"))
            return false;

        // compare unquoted strong etags
        etag = etagQuoted ? QuotedStringTokenizer.unquote(etag) : etag;
        etagWithSuffix = etagSuffixQuoted ? QuotedStringTokenizer.unquote(etagWithSuffix) : etagWithSuffix;
        separator = etagWithSuffix.lastIndexOf(ETAG_SEPARATOR);
        if (separator > 0)
            return etag.regionMatches(0, etagWithSuffix, 0, separator);

        return Objects.equals(etag, etagWithSuffix);
    }

    public String stripSuffixes(String etagsList)
    {
        if (StringUtil.isEmpty(ETAG_SEPARATOR))
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

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

import static org.eclipse.jetty.http.CompressedContentFormat.BR;
import static org.eclipse.jetty.http.CompressedContentFormat.GZIP;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class QuotedEncodingQualityCSVTest
{
    private static final CompressedContentFormat[] preferBrotli = { BR, GZIP };
    private static final CompressedContentFormat[] preferGzip = { GZIP, BR };
    private static final CompressedContentFormat[] noFormats = {};

    @Test
    public void testFirefoxContentEncodingWithBrotliPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(preferBrotli);
        values.addValue("gzip, deflate, br");
        assertThat(values, contains("br", "gzip", "deflate"));
    }

    @Test
    public void testFirefoxContentEncodingWithGzipPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(preferGzip);
        values.addValue("gzip, deflate, br");
        assertThat(values, contains("gzip", "br", "deflate"));
    }

    @Test
    public void testFirefoxContentEncodingWithNoPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(noFormats);
        values.addValue("gzip, deflate, br");
        assertThat(values, contains("gzip", "deflate", "br"));
    }

    @Test
    public void testChromeContentEncodingWithBrotliPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(preferBrotli);
        values.addValue("gzip, deflate, sdch, br");
        assertThat(values, contains("br", "gzip", "deflate", "sdch"));
    }

    @Test
    public void testComplexEncodingWithGzipPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(preferGzip);
        values.addValue("gzip;q=0.9, identity;q=0.1, *;q=0.01, deflate;q=0.9, sdch;q=0.7, br;q=0.9");
        assertThat(values, contains("gzip", "br", "deflate", "sdch", "identity", "*"));
    }

    @Test
    public void testComplexEncodingWithBrotliPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(preferBrotli);
        values.addValue("gzip;q=0.9, identity;q=0.1, *;q=0, deflate;q=0.9, sdch;q=0.7, br;q=0.99");
        assertThat(values, contains("br", "gzip", "deflate", "sdch", "identity"));
    }

    @Test
    public void testStarEncodingWithGzipPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(preferGzip);
        values.addValue("br, *");
        assertThat(values, contains("*", "br"));
    }

    @Test
    public void testStarEncodingWithBrotliPreference()
    {
        QuotedEncodingQualityCSV values = new QuotedEncodingQualityCSV(preferBrotli);
        values.addValue("gzip, *");
        assertThat(values, contains("*", "gzip"));
    }
}

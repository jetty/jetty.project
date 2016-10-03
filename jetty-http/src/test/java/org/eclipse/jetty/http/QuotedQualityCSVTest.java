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

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertThat;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class QuotedQualityCSVTest
{

    @Test
    public void test7231_5_3_2_example1()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue(" audio/*; q=0.2, audio/basic");
        Assert.assertThat(values,Matchers.contains("audio/basic","audio/*"));
    }

    @Test
    public void test7231_5_3_2_example2()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("text/plain; q=0.5, text/html,");
        values.addValue("text/x-dvi; q=0.8, text/x-c");
        Assert.assertThat(values,Matchers.contains("text/html","text/x-c","text/x-dvi","text/plain"));
    }
    
    @Test
    public void test7231_5_3_2_example3()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("text/*, text/plain, text/plain;format=flowed, */*");
        Assert.assertThat(values,Matchers.contains("text/plain;format=flowed","text/plain","text/*","*/*"));
    }
    
    @Test
    public void test7231_5_3_2_example4()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("text/*;q=0.3, text/html;q=0.7, text/html;level=1,");
        values.addValue("text/html;level=2;q=0.4, */*;q=0.5");
        Assert.assertThat(values,Matchers.contains(
                "text/html;level=1",
                "text/html",
                "*/*",
                "text/html;level=2",
                "text/*"
                ));
    }
    
    @Test
    public void test7231_5_3_4_example1()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("compress, gzip");
        values.addValue("");
        values.addValue("*");
        values.addValue("compress;q=0.5, gzip;q=1.0");
        values.addValue("gzip;q=1.0, identity; q=0.5, *;q=0");
        
        Assert.assertThat(values,Matchers.contains(
                "compress",
                "gzip",
                "gzip",
                "gzip",
                "*",
                "compress",
                "identity"
                ));
    }

    @Test
    public void testOWS()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("  value 0.5  ;  p = v  ;  q =0.5  ,  value 1.0 ");
        Assert.assertThat(values,Matchers.contains(
                "value 1.0",
                "value 0.5;p=v"));
    }
    
    @Test
    public void testEmpty()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue(",aaaa,  , bbbb ,,cccc,");
        Assert.assertThat(values,Matchers.contains(
                "aaaa",
                "bbbb",
                "cccc"));
    }
        
    @Test
    public void testQuoted()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("  value 0.5  ;  p = \"v  ;  q = \\\"0.5\\\"  ,  value 1.0 \"  ");
        Assert.assertThat(values,Matchers.contains(
                "value 0.5;p=\"v  ;  q = \\\"0.5\\\"  ,  value 1.0 \""));
    }
    
    @Test
    public void testOpenQuote()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("value;p=\"v");
        Assert.assertThat(values,Matchers.contains(
                "value;p=\"v"));
    }
    
    @Test
    public void testQuotedQuality()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("  value 0.5  ;  p = v  ;  q = \"0.5\"  ,  value 1.0 ");
        Assert.assertThat(values,Matchers.contains(
                "value 1.0",
                "value 0.5;p=v"));
    }
    
    @Test
    public void testBadQuality()
    {
        QuotedQualityCSV values = new QuotedQualityCSV();
        values.addValue("value0.5;p=v;q=0.5,value1.0,valueBad;q=X");
        Assert.assertThat(values,Matchers.contains(
                "value1.0",
                "value0.5;p=v"));
    }

    /* ------------------------------------------------------------ */

    private static final String[] preferBrotli = {"br","gzip"};
    private static final String[] preferGzip = {"gzip","br"};
    private static final String[] noFormats = {};

    @Test
    public void testFirefoxContentEncodingWithBrotliPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(preferBrotli);
        values.addValue("gzip, deflate, br");
        assertThat(values, contains("br", "gzip", "deflate"));
    }

    @Test
    public void testFirefoxContentEncodingWithGzipPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(preferGzip);
        values.addValue("gzip, deflate, br");
        assertThat(values, contains("gzip", "br", "deflate"));
    }

    @Test
    public void testFirefoxContentEncodingWithNoPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(noFormats);
        values.addValue("gzip, deflate, br");
        assertThat(values, contains("gzip", "deflate", "br"));
    }

    @Test
    public void testChromeContentEncodingWithBrotliPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(preferBrotli);
        values.addValue("gzip, deflate, sdch, br");
        assertThat(values, contains("br", "gzip", "deflate", "sdch"));
    }

    @Test
    public void testComplexEncodingWithGzipPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(preferGzip);
        values.addValue("gzip;q=0.9, identity;q=0.1, *;q=0.01, deflate;q=0.9, sdch;q=0.7, br;q=0.9");
        assertThat(values, contains("gzip", "br", "deflate", "sdch", "identity", "*"));
    }

    @Test
    public void testComplexEncodingWithBrotliPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(preferBrotli);
        values.addValue("gzip;q=0.9, identity;q=0.1, *;q=0, deflate;q=0.9, sdch;q=0.7, br;q=0.99");
        assertThat(values, contains("br", "gzip", "deflate", "sdch", "identity"));
    }

    @Test
    public void testStarEncodingWithGzipPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(preferGzip);
        values.addValue("br, *");
        assertThat(values, contains("*", "br"));
    }

    @Test
    public void testStarEncodingWithBrotliPreference()
    {
        QuotedQualityCSV values = new QuotedQualityCSV(preferBrotli);
        values.addValue("gzip, *");
        assertThat(values, contains("*", "gzip"));
    }
}

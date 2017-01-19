//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
}

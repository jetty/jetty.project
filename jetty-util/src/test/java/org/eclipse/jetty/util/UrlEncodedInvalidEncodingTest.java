//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class UrlEncodedInvalidEncodingTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    @Parameterized.Parameters(name = "{1} | {0}")
    public static List<Object[]> data()
    {
        ArrayList<Object[]> data = new ArrayList<>();
        
        data.add(new Object[]{ "Name=xx%zzyy", UTF_8, IllegalArgumentException.class });
        data.add(new Object[]{ "Name=%FF%FF%FF", UTF_8, Utf8Appendable.NotUtf8Exception.class });
        data.add(new Object[]{ "Name=%EF%EF%EF", UTF_8, Utf8Appendable.NotUtf8Exception.class });
        data.add(new Object[]{ "Name=%E%F%F", UTF_8, IllegalArgumentException.class });
        data.add(new Object[]{ "Name=x%", UTF_8, Utf8Appendable.NotUtf8Exception.class });
        data.add(new Object[]{ "Name=x%2", UTF_8, Utf8Appendable.NotUtf8Exception.class });
        data.add(new Object[]{ "Name=xxx%", UTF_8, Utf8Appendable.NotUtf8Exception.class });
        data.add(new Object[]{ "name=X%c0%afZ", UTF_8, Utf8Appendable.NotUtf8Exception.class });
        return data;
    }
    
    @Parameterized.Parameter(0)
    public String inputString;
    
    @Parameterized.Parameter(1)
    public Charset charset;
    
    @Parameterized.Parameter(2)
    public Class<? extends Throwable> expectedThrowable;
    
    @Test
    public void testDecode()
    {
        UrlEncoded url_encoded = new UrlEncoded();
        expectedException.expect(expectedThrowable);
        url_encoded.decode(inputString, charset);
    }
    
    @Test
    public void testDecodeUtf8ToMap()
    {
        MultiMap<String> map = new MultiMap<String>();
        expectedException.expect(expectedThrowable);
        UrlEncoded.decodeUtf8To(inputString,map);
    }
    
    @Test
    public void testDecodeTo()
    {
        MultiMap<String> map = new MultiMap<String>();
        expectedException.expect(expectedThrowable);
        UrlEncoded.decodeTo(inputString,map,charset);
    }
}

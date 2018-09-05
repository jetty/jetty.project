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

package org.eclipse.jetty.quickstart;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class AttributeNormalizer_ToCanonicalUriTest
{
    @Parameterized.Parameters(name = "[{index}] {0} - {1}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();

        
        // root without authority
        data.add(new String[]{ "file:/", "file:/" });
        data.add(new String[]{ "file:/F:/", "file:/F:" });
        
        // root with empty authority
        data.add(new String[]{ "file:///", "file:///" });
        data.add(new String[]{ "file:///F:/", "file:///F:" });

        // deep directory - no authority
        data.add(new String[]{ "file:/home/user/code/", "file:/home/user/code" });
        data.add(new String[]{ "file:/C:/code/", "file:/C:/code" });

        // deep directory - with authority
        data.add(new String[]{ "file:///home/user/code/", "file:///home/user/code" });
        data.add(new String[]{ "file:///C:/code/", "file:///C:/code" });

        // Some non-file tests
        data.add(new String[]{ "http://webtide.com/", "http://webtide.com/" });
        data.add(new String[]{ "http://webtide.com/cometd/", "http://webtide.com/cometd" });

        return data;
    }

    @Parameterized.Parameter
    public String input;

    @Parameterized.Parameter(1)
    public String expected;

    @Test
    public void testCanonicalURI()
    {
        URI inputURI = URI.create(input);
        URI actual = AttributeNormalizer.toCanonicalURI(inputURI);
        assertThat(input, actual.toASCIIString(), is(expected));
    }
}

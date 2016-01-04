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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test various invalid UTF8 byte sequences.
 */
@RunWith(Parameterized.class)
public class Utf8StringBuilderInvalidUtfTest
{
    @Parameters
    public static Collection<Object[]> data() {
        List<Object[]> data = new ArrayList<>();
        data.add(new String[]{"c0af"});
        data.add(new String[]{"EDA080"});
        data.add(new String[]{"f08080af"});
        data.add(new String[]{"f8808080af"});
        data.add(new String[]{"e080af"});
        data.add(new String[]{"F4908080"});
        data.add(new String[]{"fbbfbfbfbf"});
        data.add(new String[]{"10FFFF"});
        data.add(new String[]{"CeBaE1BdB9Cf83CeBcCeB5EdA080656469746564"});
        // use of UTF-16 High Surrogates (in codepoint form)
        data.add(new String[]{"da07"});
        data.add(new String[]{"d807"});
        // decoded UTF-16 High Surrogate "\ud807" (in UTF-8 form)
        data.add(new String[]{"EDA087"});
        return data;
    }
    
    private byte[] bytes;
    
    public Utf8StringBuilderInvalidUtfTest(String rawhex)
    {
        bytes = TypeUtil.fromHexString(rawhex);
        System.out.printf("Utf8StringBuilderInvalidUtfTest[] (%s)%n", TypeUtil.toHexString(bytes));
    }
    
    @Test(expected=NotUtf8Exception.class)
    public void testInvalidUTF8()
    {
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        buffer.append(bytes,0,bytes.length);
    }
}

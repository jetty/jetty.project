//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class MuxGeneratorWrite139SizeTest
{
    private static MuxGenerator generator = new MuxGenerator();

    @Parameters
    public static Collection<Object[]> data()
    {
        // Various good 1/3/9 encodings
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        // - 1 byte tests
        data.add(new Object[]{  0L, "00"});
        data.add(new Object[]{  1L, "01"});
        data.add(new Object[]{  2L, "02"});
        data.add(new Object[]{ 55L, "37"});
        data.add(new Object[]{125L, "7D"});

        // - 3 byte tests
        data.add(new Object[]{0x00_80L, "7E0080"});
        data.add(new Object[]{0x00_ABL, "7E00AB"});
        data.add(new Object[]{0x00_FFL, "7E00FF"});
        data.add(new Object[]{0x3F_FFL, "7E3FFF"});

        // - 9 byte tests
        data.add(new Object[]{0x00_00_01_FF_FFL, "7F000000000001FFFF"});
        data.add(new Object[]{0x00_00_FF_FF_FFL, "7F0000000000FFFFFF"});
        data.add(new Object[]{0x00_FF_FF_FF_FFL, "7F00000000FFFFFFFF"});
        data.add(new Object[]{0xFF_FF_FF_FF_FFL, "7F000000FFFFFFFFFF"});
        // @formatter:on

        return data;
    }

    @Rule
    public TestName testname = new TestName();

    private long value;
    private String expectedHex;

    public MuxGeneratorWrite139SizeTest(long value, String expectedHex)
    {
        this.value = value;
        this.expectedHex = expectedHex;
    }

    @Test
    public void testWrite139Size()
    {
        System.err.printf("Running %s.%s - value: %,d%n",this.getClass().getName(),testname.getMethodName(),value);
        ByteBuffer bbuf = ByteBuffer.allocate(10);
        generator.write139Size(bbuf,value);
        BufferUtil.flipToFlush(bbuf,0);
        byte actual[] = BufferUtil.toArray(bbuf);
        String actualHex = TypeUtil.toHexString(actual).toUpperCase(Locale.ENGLISH);
        Assert.assertThat("1/3/9 encoded size of [" + value + "]",actualHex,is(expectedHex));
    }
}

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

import org.eclipse.jetty.util.TypeUtil;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class MuxParserRead139Size_GoodTest
{
    private static MuxParser parser = new MuxParser();

    @Parameters
    public static Collection<Object[]> data()
    {
        // Various good 1/3/9 encodings
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        // - 1 byte tests
        data.add(new Object[]{"00", 0L});
        data.add(new Object[]{"01", 1L});
        data.add(new Object[]{"02", 2L});
        data.add(new Object[]{"37", 55L});
        data.add(new Object[]{"7D", 125L});
        // extra bytes (not related to 1/3/9 size)
        data.add(new Object[]{"37FF", 55L});
        data.add(new Object[]{"0123456789", 0x01L});

        // - 3 byte tests
        data.add(new Object[]{"7E0080", 0x00_80L});
        data.add(new Object[]{"7E00AB", 0x00_ABL});
        data.add(new Object[]{"7E00FF", 0x00_FFL});
        data.add(new Object[]{"7E3FFF", 0x3F_FFL});
        // extra bytes (not related to 1/3/9 size)
        data.add(new Object[]{"7E0123456789", 0x01_23L});

        // - 9 byte tests
        data.add(new Object[]{"7F000000000001FFFF", 0x00_00_01_FF_FFL});
        data.add(new Object[]{"7F0000000000FFFFFF", 0x00_00_FF_FF_FFL});
        data.add(new Object[]{"7F00000000FFFFFFFF", 0x00_FF_FF_FF_FFL});
        data.add(new Object[]{"7F000000FFFFFFFFFF", 0xFF_FF_FF_FF_FFL});

        // @formatter:on
        return data;
    }

    @Rule
    public TestName testname = new TestName();

    private String rawhex;
    private byte buf[];
    private long expected;

    public MuxParserRead139Size_GoodTest(String rawhex, long expected)
    {
        this.rawhex = rawhex;
        this.buf = TypeUtil.fromHexString(rawhex);
        this.expected = expected;
    }

    @Test
    public void testRead139EncodedSize()
    {
        System.err.printf("Running %s.%s - hex: %s%n",this.getClass().getName(),testname.getMethodName(),rawhex);
        ByteBuffer bbuf = ByteBuffer.wrap(buf);
        long actual = parser.read139EncodedSize(bbuf);
        Assert.assertThat("1/3/9 size from buffer [" + rawhex + "]",actual,is(expected));
    }
}

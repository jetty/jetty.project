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

import static org.hamcrest.Matchers.containsString;

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

/**
 * Tests for bad 1/3/9 size encoding.
 */
@RunWith(Parameterized.class)
public class MuxParserRead139Size_BadEncodingTest
{
    private static MuxParser parser = new MuxParser();

    @Parameters
    public static Collection<Object[]> data()
    {
        // Various bad 1/3/9 encodings
        // Violating "minimal number of bytes necessary" rule.
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        // - 1 byte tests
        // all known 1 byte tests are valid

        // - 3 byte tests
        data.add(new Object[]{"7E0000"});
        data.add(new Object[]{"7E0001"});
        data.add(new Object[]{"7E0012"});
        data.add(new Object[]{"7E0059"});
        // extra bytes (not related to 1/3/9 size)
        data.add(new Object[]{"7E0012345678"});

        // - 9 byte tests
        data.add(new Object[]{"7F0000000000000000"});
        data.add(new Object[]{"7F0000000000000001"});
        data.add(new Object[]{"7F0000000000000012"});
        data.add(new Object[]{"7F0000000000001234"});
        data.add(new Object[]{"7F000000000000FFFF"});

        // @formatter:on
        return data;
    }

    @Rule
    public TestName testname = new TestName();

    private String rawhex;
    private byte buf[];

    public MuxParserRead139Size_BadEncodingTest(String rawhex)
    {
        this.rawhex = rawhex;
        this.buf = TypeUtil.fromHexString(rawhex);
    }

    @Test
    public void testRead139EncodedSize()
    {
        System.err.printf("Running %s.%s - hex: %s%n",this.getClass().getName(),testname.getMethodName(),rawhex);
        ByteBuffer bbuf = ByteBuffer.wrap(buf);
        try
        {
            parser.read139EncodedSize(bbuf);
            // unexpected path
            Assert.fail("Should have failed with an invalid parse");
        }
        catch (MuxException e)
        {
            // expected path
            Assert.assertThat(e.getMessage(),containsString("Invalid 1/3/9 length"));
        }
    }
}

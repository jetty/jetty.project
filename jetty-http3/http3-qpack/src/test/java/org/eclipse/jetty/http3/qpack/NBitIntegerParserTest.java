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

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerParser;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class NBitIntegerParserTest
{

    @Test
    public void testParsingOverByteBoundary()
    {
        NBitIntegerParser parser = new NBitIntegerParser();

        String encoded = "FFBA09";
        byte[] bytes = TypeUtil.fromHexString(encoded);
        bytes[0] = (byte)(bytes[0] | 0x80); // set the first bit so it is a section acknowledgement.
        ByteBuffer buffer1 = BufferUtil.toBuffer(bytes, 0, 2);
        ByteBuffer buffer2 = BufferUtil.toBuffer(bytes, 2, 1);

        parser.setPrefix(7);
        int value = parser.decodeInt(buffer1);
        assertThat(value, is(-1));

        value = parser.decodeInt(buffer2);
        assertThat(value, is(1337));
    }
}

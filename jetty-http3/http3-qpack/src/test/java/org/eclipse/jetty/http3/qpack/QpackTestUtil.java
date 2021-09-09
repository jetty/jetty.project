//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class QpackTestUtil
{
    public static Matcher<String> equalsHex(String expectedString)
    {
        expectedString = expectedString.replaceAll("\\s+", "");
        return org.hamcrest.text.IsEqualIgnoringCase.equalToIgnoringCase(expectedString);
    }

    public static ByteBuffer toBuffer(List<Instruction> instructions)
    {
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(new NullByteBufferPool());
        instructions.forEach(i -> i.encode(lease));
        assertThat(lease.getSize(), is(instructions.size()));
        return lease.getByteBuffers().get(0);
    }

    public static ByteBuffer hexToBuffer(String hexString)
    {
        hexString = hexString.replaceAll("\\s+", "");
        return ByteBuffer.wrap(TypeUtil.fromHexString(hexString));
    }

    public static String toHexString(Instruction instruction)
    {
        return BufferUtil.toHexString(toBuffer(List.of(instruction)));
    }
}

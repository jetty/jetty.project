//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matcher;

public class QpackTestUtil
{
    public static ByteBuffer toBuffer(Instruction... instructions)
    {
        ByteBufferPool bufferPool = ByteBufferPool.NON_POOLING;
        RetainableByteBuffer.DynamicCapacity accumulator = new RetainableByteBuffer.DynamicCapacity();
        for (Instruction instruction : instructions)
        {
            instruction.encode(bufferPool, accumulator);
        }
        ByteBuffer combinedBuffer = BufferUtil.allocate(Math.toIntExact(accumulator.size()));
        BufferUtil.clearToFill(combinedBuffer);
        accumulator.putTo(combinedBuffer);
        BufferUtil.flipToFlush(combinedBuffer, 0);
        return combinedBuffer;
    }

    public static Matcher<String> equalsHex(String expectedString)
    {
        expectedString = expectedString.replaceAll("\\s+", "");
        return org.hamcrest.text.IsEqualIgnoringCase.equalToIgnoringCase(expectedString);
    }

    public static ByteBuffer toBuffer(List<Instruction> instructions)
    {
        ByteBufferPool bufferPool = ByteBufferPool.NON_POOLING;
        RetainableByteBuffer.DynamicCapacity accumulator = new RetainableByteBuffer.DynamicCapacity();
        instructions.forEach(i -> i.encode(bufferPool, accumulator));
        ByteBuffer combinedBuffer = BufferUtil.allocate(Math.toIntExact(accumulator.size()), false);
        BufferUtil.clearToFill(combinedBuffer);
        accumulator.putTo(combinedBuffer);
        BufferUtil.flipToFlush(combinedBuffer, 0);
        return combinedBuffer;
    }

    public static ByteBuffer hexToBuffer(String hexString)
    {
        hexString = hexString.replaceAll("\\s+", "");
        return ByteBuffer.wrap(StringUtil.fromHexString(hexString));
    }

    public static String toHexString(Instruction instruction)
    {
        return BufferUtil.toHexString(toBuffer(List.of(instruction)));
    }

    public static ByteBuffer encode(QpackEncoder encoder, long streamId, MetaData metaData) throws QpackException
    {
        ByteBuffer buffer = BufferUtil.allocate(1024);
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer, streamId, metaData);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    public static HttpFields.Mutable toHttpFields(HttpField field)
    {
        return HttpFields.build().add(field);
    }

    public static MetaData toMetaData(String name, String value)
    {
        return toMetaData(toHttpFields(new HttpField(name, value)));
    }

    public static MetaData toMetaData(String method, String path, String scheme)
    {
        return toMetaData(method, path, scheme, (HttpField)null);
    }

    public static MetaData toMetaData(String method, String path, String scheme, HttpField... fields)
    {
        HttpFields.Mutable httpFields = HttpFields.build();
        for (HttpField field : fields)
        {
            httpFields.add(field);
        }

        return toMetaData(method, path, scheme, httpFields);
    }

    public static MetaData toMetaData(String method, String path, String scheme, HttpFields.Mutable fields)
    {
        fields = HttpFields.build()
            .put(":scheme", scheme)
            .put(":method", method)
            .put(":path", path)
            .add(fields);
        return new MetaData(HttpVersion.HTTP_3, fields);
    }

    public static MetaData toMetaData(HttpFields fields)
    {
        return new MetaData(HttpVersion.HTTP_3, fields);
    }

    public static boolean compareMetaData(MetaData m1, MetaData m2)
    {
        if (!Objects.equals(m1.getHttpVersion(), m2.getHttpVersion()))
            return false;
        if (!Objects.equals(m1.getContentLength(), m2.getContentLength()))
            return false;
        if (!Objects.equals(m1.getHttpFields(), m2.getHttpFields()))
            return false;
        return m1.getTrailersSupplier() == null && m2.getTrailersSupplier() == null;
    }
}

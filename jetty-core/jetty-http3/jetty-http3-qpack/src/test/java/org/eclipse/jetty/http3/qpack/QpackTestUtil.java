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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.hamcrest.Matcher;

public class QpackTestUtil
{
    public static RetainableByteBuffer toBuffer(Instruction instruction)
    {
        return toBuffer(List.of(instruction));
    }

    public static RetainableByteBuffer toBuffer(Instruction... instructions)
    {
        return toBuffer(List.of(instructions));
    }

    public static RetainableByteBuffer toBuffer(List<Instruction> instructions)
    {
        WritableBufferPool bufferPool = WritableBufferPool.NON_POOLING;
        List<RetainableByteBuffer> accumulator = new ArrayList<>();
        instructions.forEach(i -> i.encode(bufferPool, accumulator));
        if (accumulator.isEmpty())
            return RetainableByteBuffer.empty();
        if (accumulator.size() == 1)
            return accumulator.getFirst();
        return RetainableByteBuffer.wrap(accumulator);
    }

    public static Matcher<String> equalsHex(String expectedString)
    {
        expectedString = expectedString.replaceAll("\\s+", "");
        return org.hamcrest.text.IsEqualIgnoringCase.equalToIgnoringCase(expectedString);
    }

    public static RetainableByteBuffer hexToBuffer(String hexString)
    {
        hexString = hexString.replaceAll("\\s+", "");
        return RetainableByteBuffer.wrap(StringUtil.fromHexString(hexString));
    }

    public static String toHexString(Instruction instruction)
    {
        return BufferUtil.toHexString(toBuffer(List.of(instruction)));
    }

    public static RetainableByteBuffer encode(QpackEncoder encoder, long streamId, MetaData metaData) throws QpackException
    {
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(1024, false);
        encoder.encode(buffer, streamId, metaData);
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
        HttpURI.Immutable uri = HttpURI.from(scheme, path);
        return new MetaData.Request(method, uri, HttpVersion.HTTP_3, fields);
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

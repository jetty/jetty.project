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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class ClientGenerator extends Generator
{
    // To keep the algorithm simple, and given that the max length of a
    // frame is 0xFF_FF we allow the max length of a name (or value) to be
    // 0x7F_FF - 4 (the 4 is to make room for the name (or value) length).
    public static final int MAX_PARAM_LENGTH = 0x7F_FF - 4;

    public ClientGenerator(ByteBufferPool bufferPool)
    {
        this(bufferPool, true);
    }

    public ClientGenerator(ByteBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        super(bufferPool, useDirectByteBuffers);
    }

    public void generateRequestHeaders(ByteBufferPool.Accumulator accumulator, int request, HttpFields fields)
    {
        request &= 0xFF_FF;

        final Charset utf8 = StandardCharsets.UTF_8;
        List<byte[]> bytes = new ArrayList<>(fields.size() * 2);
        int fieldsLength = 0;
        for (HttpField field : fields)
        {
            String name = field.getName();
            byte[] nameBytes = name.getBytes(utf8);
            if (nameBytes.length > MAX_PARAM_LENGTH)
                throw new IllegalArgumentException("Field name " + name + " exceeds max length " + MAX_PARAM_LENGTH);
            bytes.add(nameBytes);

            String value = field.getValue();
            byte[] valueBytes = value.getBytes(utf8);
            if (valueBytes.length > MAX_PARAM_LENGTH)
                throw new IllegalArgumentException("Field value " + value + " exceeds max length " + MAX_PARAM_LENGTH);
            bytes.add(valueBytes);

            int nameLength = nameBytes.length;
            fieldsLength += bytesForLength(nameLength);

            int valueLength = valueBytes.length;
            fieldsLength += bytesForLength(valueLength);

            fieldsLength += nameLength;
            fieldsLength += valueLength;
        }

        // Worst case FCGI_PARAMS frame: long name + long value - both of MAX_PARAM_LENGTH
        int maxCapacity = 4 + 4 + 2 * MAX_PARAM_LENGTH;

        // One FCGI_BEGIN_REQUEST + N FCGI_PARAMS + one last FCGI_PARAMS

        RetainableByteBuffer beginBuffer = getByteBufferPool().acquire(16, isUseDirectByteBuffers());
        accumulator.append(beginBuffer);
        ByteBuffer beginByteBuffer = beginBuffer.getByteBuffer();
        BufferUtil.clearToFill(beginByteBuffer);

        // Generate the FCGI_BEGIN_REQUEST frame
        beginByteBuffer.putInt(0x01_01_00_00 + request);
        beginByteBuffer.putInt(0x00_08_00_00);
        // Hardcode RESPONDER role and KEEP_ALIVE flag
        beginByteBuffer.putLong(0x00_01_01_00_00_00_00_00L);
        BufferUtil.flipToFlush(beginByteBuffer, 0);

        int index = 0;
        while (fieldsLength > 0)
        {
            int capacity = 8 + Math.min(maxCapacity, fieldsLength);
            RetainableByteBuffer buffer = getByteBufferPool().acquire(capacity, isUseDirectByteBuffers());
            accumulator.append(buffer);
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            BufferUtil.clearToFill(byteBuffer);

            // Generate the FCGI_PARAMS frame
            byteBuffer.putInt(0x01_04_00_00 + request);
            byteBuffer.putShort((short)0);
            byteBuffer.putShort((short)0);
            capacity -= 8;

            int length = 0;
            while (index < bytes.size())
            {
                byte[] nameBytes = bytes.get(index);
                int nameLength = nameBytes.length;
                byte[] valueBytes = bytes.get(index + 1);
                int valueLength = valueBytes.length;

                int required = bytesForLength(nameLength) + bytesForLength(valueLength) + nameLength + valueLength;
                if (required > capacity)
                    break;

                putParamLength(byteBuffer, nameLength);
                putParamLength(byteBuffer, valueLength);
                byteBuffer.put(nameBytes);
                byteBuffer.put(valueBytes);

                length += required;
                fieldsLength -= required;
                capacity -= required;
                index += 2;
            }

            byteBuffer.putShort(4, (short)length);
            BufferUtil.flipToFlush(byteBuffer, 0);
        }

        RetainableByteBuffer lastBuffer = getByteBufferPool().acquire(8, isUseDirectByteBuffers());
        accumulator.append(lastBuffer);
        ByteBuffer lastByteBuffer = lastBuffer.getByteBuffer();
        BufferUtil.clearToFill(lastByteBuffer);

        // Generate the last FCGI_PARAMS frame
        lastByteBuffer.putInt(0x01_04_00_00 + request);
        lastByteBuffer.putInt(0x00_00_00_00);
        BufferUtil.flipToFlush(lastByteBuffer, 0);
    }

    private int putParamLength(ByteBuffer buffer, int length)
    {
        int result = bytesForLength(length);
        if (result == 4)
            buffer.putInt(length | 0x80_00_00_00);
        else
            buffer.put((byte)length);
        return result;
    }

    private int bytesForLength(int length)
    {
        return length > 127 ? 4 : 1;
    }

    public void generateRequestContent(ByteBufferPool.Accumulator accumulator, int request, ByteBuffer content, boolean lastContent)
    {
        generateContent(accumulator, request, content, lastContent, FCGI.FrameType.STDIN);
    }
}

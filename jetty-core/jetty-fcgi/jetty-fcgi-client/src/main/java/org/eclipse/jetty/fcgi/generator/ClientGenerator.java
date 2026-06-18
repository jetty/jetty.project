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

package org.eclipse.jetty.fcgi.generator;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class ClientGenerator extends Generator
{
    // To keep the algorithm simple, and given that the max length of a
    // frame is 0xFF_FF we allow the max length of a name (or value) to be
    // 0x7F_FF - 4 (the 4 is to make room for the name (or value) length).
    public static final int MAX_PARAM_LENGTH = 0x7F_FF - 4;

    public ClientGenerator(WritableBufferPool bufferPool)
    {
        this(bufferPool, true);
    }

    public ClientGenerator(WritableBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        super(bufferPool, useDirectByteBuffers);
    }

    public void generateRequestHeaders(List<ReadableBuffer> accumulator, int request, HttpFields fields)
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

        WritableBuffer beginBuffer = getBufferPool().acquire(16, isUseDirectByteBuffers());

        // Generate the FCGI_BEGIN_REQUEST frame
        beginBuffer.putInt(0x01_01_00_00 + request);
        beginBuffer.putInt(0x00_08_00_00);
        // Hardcode RESPONDER role and KEEP_ALIVE flag
        beginBuffer.putLong(0x00_01_01_00_00_00_00_00L);
        accumulator.add(beginBuffer.toReadable());

        int index = 0;
        while (fieldsLength > 0)
        {
            int capacity = 8 + Math.min(maxCapacity, fieldsLength);
            WritableBuffer buffer = getBufferPool().acquire(capacity, isUseDirectByteBuffers());

            // Generate the FCGI_PARAMS frame
            buffer.putInt(0x01_04_00_00 + request);
            buffer.putShort((short)0);
            buffer.putShort((short)0);
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

                putParamLength(buffer, nameLength);
                putParamLength(buffer, valueLength);
                buffer.putBytes(nameBytes);
                buffer.putBytes(valueBytes);

                length += required;
                fieldsLength -= required;
                capacity -= required;
                index += 2;
            }

            buffer.putShort(4, (short)length);
            accumulator.add(buffer.toReadable());
        }

        WritableBuffer lastBuffer = getBufferPool().acquire(8, isUseDirectByteBuffers());

        // Generate the last FCGI_PARAMS frame
        lastBuffer.putInt(0x01_04_00_00 + request);
        lastBuffer.putInt(0x00_00_00_00);
        accumulator.add(lastBuffer.toReadable());
    }

    private int putParamLength(WritableBuffer buffer, int length)
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

    public void generateRequestContent(List<ReadableBuffer> accumulator, int request, ReadableBuffer content, boolean lastContent)
    {
        generateContent(accumulator, request, content, lastContent, FCGI.FrameType.STDIN);
    }
}

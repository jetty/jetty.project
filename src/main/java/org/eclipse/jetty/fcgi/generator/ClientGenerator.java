//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;

public class ClientGenerator extends Generator
{
    private final ByteBufferPool byteBufferPool;

    public ClientGenerator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    public ByteBuffer generateRequestHeaders(int id, Fields fields)
    {
        id = id & 0xFFFF;

        Charset utf8 = Charset.forName("UTF-8");
        List<byte[]> bytes = new ArrayList<>(fields.size() * 2);
        int fieldsLength = 0;
        for (Fields.Field field : fields)
        {
            String name = field.name();
            byte[] nameBytes = name.getBytes(utf8);
            bytes.add(nameBytes);

            String value = field.value();
            byte[] valueBytes = value.getBytes(utf8);
            bytes.add(valueBytes);

            int nameLength = nameBytes.length;
            ++fieldsLength;
            if (nameLength > 127)
                fieldsLength += 3;

            int valueLength = valueBytes.length;
            ++fieldsLength;
            if (valueLength > 127)
                fieldsLength += 3;

            fieldsLength += nameLength;
            fieldsLength += valueLength;
        }

        if (fieldsLength > 0x7F_FF)
            throw new IllegalArgumentException(); // TODO: improve this ?

        int capacity = 16 + 8 + fieldsLength + 8;
        ByteBuffer buffer = byteBufferPool.acquire(capacity, true);
        BufferUtil.clearToFill(buffer);

        // Generate the FCGI_BEGIN_REQUEST frame
        buffer.putInt(0x01_01_00_00 + id);
        buffer.putInt(0x00_08_00_00);
        buffer.putLong(0x00_01_01_00_00_00_00_00L);

        // Generate the FCGI_PARAMS frame
        buffer.putInt(0x01_04_00_00 + id);
        buffer.putShort((short)fieldsLength);
        buffer.putShort((short)0);

        for (int i = 0; i < bytes.size(); i += 2)
        {
            byte[] nameBytes = bytes.get(i);
            putParamLength(buffer, nameBytes.length);
            byte[] valueBytes = bytes.get(i + 1);
            putParamLength(buffer, valueBytes.length);
            buffer.put(nameBytes);
            buffer.put(valueBytes);
        }

        // Generate the last FCGI_PARAMS frame
        buffer.putInt(0x01_04_00_00 + id);
        buffer.putInt(0x00_00_00_00);

        buffer.flip();

        return buffer;
    }

    private void putParamLength(ByteBuffer buffer, int length)
    {
        if (length > 127)
            buffer.putInt(length | 0x80_00_00_00);
        else
            buffer.put((byte)length);
    }
}

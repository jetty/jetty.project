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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class ServerGenerator extends Generator
{
    private static final byte[] STATUS = new byte[]{'S', 't', 'a', 't', 'u', 's'};
    private static final byte[] COLON = new byte[]{':', ' '};
    private static final byte[] EOL = new byte[]{'\r', '\n'};

    private final boolean sendStatus200;

    public ServerGenerator(WritableBufferPool bufferPool)
    {
        this(bufferPool, true, true);
    }

    public ServerGenerator(WritableBufferPool bufferPool, boolean useDirectByteBuffers, boolean sendStatus200)
    {
        super(bufferPool, useDirectByteBuffers);
        this.sendStatus200 = sendStatus200;
    }

    public void generateResponseHeaders(List<ReadableBuffer> accumulator, int request, int code, String reason, HttpFields fields)
    {
        request &= 0xFF_FF;

        Charset utf8 = StandardCharsets.UTF_8;
        List<byte[]> bytes = new ArrayList<>(fields.size() * 2);
        int length = 0;

        if (code != 200 || sendStatus200)
        {
            // Special 'Status' header
            bytes.add(STATUS);
            length += STATUS.length + COLON.length;
            if (reason == null)
                reason = HttpStatus.getMessage(code);
            byte[] responseBytes = (code + " " + reason).getBytes(utf8);
            bytes.add(responseBytes);
            length += responseBytes.length + EOL.length;
        }

        // Other headers
        for (HttpField field : fields)
        {
            String name = field.getName();
            byte[] nameBytes = name.getBytes(utf8);
            bytes.add(nameBytes);

            String value = field.getValue();
            byte[] valueBytes = value.getBytes(utf8);
            bytes.add(valueBytes);

            length += nameBytes.length + COLON.length;
            length += valueBytes.length + EOL.length;
        }
        // End of headers
        length += EOL.length;

        WritableBuffer buffer = getBufferPool().acquire(length, isUseDirectByteBuffers());
        for (int i = 0; i < bytes.size(); i += 2)
        {
            buffer.putBytes(bytes.get(i));
            buffer.putBytes(COLON);
            buffer.putBytes(bytes.get(i + 1));
            buffer.putBytes(EOL);
        }
        buffer.putBytes(EOL);

        generateContent(accumulator, request, buffer.toReadable(), false, FCGI.FrameType.STDOUT);
        buffer.release();
    }

    public void generateResponseContent(List<ReadableBuffer> accumulator, int request, ReadableBuffer content, boolean lastContent, boolean aborted)
    {
        if (aborted)
        {
            if (lastContent)
                accumulator.add(generateEndRequest(request, true));
            else
                accumulator.add(ReadableBuffer.EMPTY);
        }
        else
        {
            generateContent(accumulator, request, content, lastContent, FCGI.FrameType.STDOUT);
            if (lastContent)
                accumulator.add(generateEndRequest(request, false));
        }
    }

    private ReadableBuffer generateEndRequest(int request, boolean aborted)
    {
        request &= 0xFF_FF;
        WritableBuffer buffer = getBufferPool().acquire(16, isUseDirectByteBuffers());
        buffer.putInt(0x01_03_00_00 + request);
        buffer.putInt(0x00_08_00_00);
        buffer.putInt(aborted ? 1 : 0);
        buffer.putInt(0);
        return buffer.toReadable();
    }
}

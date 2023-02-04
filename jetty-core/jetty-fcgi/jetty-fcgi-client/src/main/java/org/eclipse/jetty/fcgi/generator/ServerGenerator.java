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
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class ServerGenerator extends Generator
{
    private static final byte[] STATUS = new byte[]{'S', 't', 'a', 't', 'u', 's'};
    private static final byte[] COLON = new byte[]{':', ' '};
    private static final byte[] EOL = new byte[]{'\r', '\n'};

    private final boolean sendStatus200;

    public ServerGenerator(ByteBufferPool bufferPool)
    {
        this(bufferPool, true, true);
    }

    public ServerGenerator(ByteBufferPool bufferPool, boolean useDirectByteBuffers, boolean sendStatus200)
    {
        super(bufferPool, useDirectByteBuffers);
        this.sendStatus200 = sendStatus200;
    }

    public void generateResponseHeaders(ByteBufferPool.Accumulator accumulator, int request, int code, String reason, HttpFields fields)
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

        ByteBuffer byteBuffer = BufferUtil.allocate(length, isUseDirectByteBuffers());
        BufferUtil.clearToFill(byteBuffer);

        for (int i = 0; i < bytes.size(); i += 2)
        {
            byteBuffer.put(bytes.get(i)).put(COLON).put(bytes.get(i + 1)).put(EOL);
        }
        byteBuffer.put(EOL);

        BufferUtil.flipToFlush(byteBuffer, 0);

        generateContent(accumulator, request, byteBuffer, false, FCGI.FrameType.STDOUT);
    }

    public void generateResponseContent(ByteBufferPool.Accumulator accumulator, int request, ByteBuffer content, boolean lastContent, boolean aborted)
    {
        if (aborted)
        {
            if (lastContent)
                accumulator.append(generateEndRequest(request, true));
            else
                accumulator.append(RetainableByteBuffer.wrap(BufferUtil.EMPTY_BUFFER));
        }
        else
        {
            generateContent(accumulator, request, content, lastContent, FCGI.FrameType.STDOUT);
            if (lastContent)
                accumulator.append(generateEndRequest(request, false));
        }
    }

    private RetainableByteBuffer generateEndRequest(int request, boolean aborted)
    {
        request &= 0xFF_FF;
        RetainableByteBuffer endRequestBuffer = getByteBufferPool().acquire(16, isUseDirectByteBuffers());
        ByteBuffer byteBuffer = endRequestBuffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);
        byteBuffer.putInt(0x01_03_00_00 + request);
        byteBuffer.putInt(0x00_08_00_00);
        byteBuffer.putInt(aborted ? 1 : 0);
        byteBuffer.putInt(0);
        BufferUtil.flipToFlush(byteBuffer, 0);
        return endRequestBuffer;
    }
}

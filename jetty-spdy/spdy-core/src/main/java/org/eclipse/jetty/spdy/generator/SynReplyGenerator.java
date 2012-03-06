/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.ByteBufferPool;
import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;

public class SynReplyGenerator extends ControlFrameGenerator
{
    private final HeadersBlockGenerator headersBlockGenerator;

    public SynReplyGenerator(ByteBufferPool bufferPool, HeadersBlockGenerator headersBlockGenerator)
    {
        super(bufferPool);
        this.headersBlockGenerator = headersBlockGenerator;
    }

    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        SynReplyFrame synReply = (SynReplyFrame)frame;
        short version = synReply.getVersion();

        ByteBuffer headersBuffer = headersBlockGenerator.generate(version, synReply.getHeaders());

        int frameBodyLength = getFrameDataLength(version);

        int frameLength = frameBodyLength + headersBuffer.remaining();
        if (frameLength > 0xFF_FF_FF)
        {
            // Too many headers, but unfortunately we have already modified the compression
            // context, so we have no other choice than tear down the connection.
            throw new SessionException(SessionStatus.PROTOCOL_ERROR, "Too many headers");
        }

        int totalLength = ControlFrame.HEADER_LENGTH + frameLength;

        ByteBuffer buffer = getByteBufferPool().acquire(totalLength, true);
        generateControlFrameHeader(synReply, frameLength, buffer);

        buffer.putInt(synReply.getStreamId() & 0x7F_FF_FF_FF);
        writeAdditional(version, buffer);

        buffer.put(headersBuffer);

        buffer.flip();
        return buffer;
    }

    private int getFrameDataLength(short version)
    {
        switch (version)
        {
            case SPDY.V2:
                return 6;
            case SPDY.V3:
                return 4;
            default:
                // Here the version is trusted to be correct; if it's not
                // then it's a bug rather than an application error
                throw new IllegalStateException();
        }
    }

    private void writeAdditional(short version, ByteBuffer buffer)
    {
        switch (version)
        {
            case SPDY.V2:
                buffer.putShort((short)0);
                break;
            case SPDY.V3:
                break;
            default:
                // Here the version is trusted to be correct; if it's not
                // then it's a bug rather than an application error
                throw new IllegalStateException();
        }
    }
}

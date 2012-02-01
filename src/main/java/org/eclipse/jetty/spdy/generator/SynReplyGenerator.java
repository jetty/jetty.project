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

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;

public class SynReplyGenerator extends ControlFrameGenerator
{
    private final HeadersBlockGenerator headersBlockGenerator;

    public SynReplyGenerator(HeadersBlockGenerator headersBlockGenerator)
    {
        this.headersBlockGenerator = headersBlockGenerator;
    }

    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        SynReplyFrame synReply = (SynReplyFrame)frame;
        short version = synReply.getVersion();

        ByteBuffer headersBuffer = headersBlockGenerator.generate(version, synReply.getHeaders());

        int frameBodyLength = getFrameDataLength(version);

        int frameLength = frameBodyLength + headersBuffer.remaining();
        if (frameLength > 0xFF_FF_FF)
            throw new StreamException(StreamStatus.PROTOCOL_ERROR, "Too many headers");

        int totalLength = ControlFrame.HEADER_LENGTH + frameLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(synReply, frameLength, buffer);

        buffer.putInt(synReply.getStreamId() & 0x7F_FF_FF_FF);
        writeAdditional(version, buffer);

        buffer.put(headersBuffer);

        buffer.flip();
        return buffer;
    }

    private int getFrameDataLength(short version) throws StreamException
    {
        switch (version)
        {
            case 2:
                return 6;
            case 3:
                return 4;
            default:
                // Here the version is trusted to be correct; if it's not
                // then it's a bug rather than an application error
                throw new IllegalStateException();
        }
    }

    private void writeAdditional(short version, ByteBuffer buffer) throws StreamException
    {
        switch (version)
        {
            case 2:
                buffer.putShort((short)0);
                break;
            case 3:
                break;
            default:
                // Here the version is trusted to be correct; if it's not
                // then it's a bug rather than an application error
                throw new IllegalStateException();
        }
    }
}

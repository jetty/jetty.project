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

package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.zip.ZipException;

import org.eclipse.jetty.spdy.CompressionDictionary;
import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.StreamStatus;

public abstract class HeadersBlockParser
{
    private final CompressionFactory.Decompressor decompressor;
    private byte[] data;
    private boolean needsDictionary = true;

    protected HeadersBlockParser(CompressionFactory.Decompressor decompressor)
    {
        this.decompressor = decompressor;
    }

    public boolean parse(int streamId, short version, int length, ByteBuffer buffer)
    {
        // Need to be sure that all the compressed data has arrived
        // Because SPDY uses SYNC_FLUSH mode, and the Java API
        // does not expose when decompression is finished with this mode
        // (but only when using NO_FLUSH), then we need to
        // accumulate the compressed bytes until we have all of them

        boolean accumulated = accumulate(length, buffer);
        if (!accumulated)
            return false;

        byte[] compressedHeaders = data;
        data = null;
        ByteBuffer decompressedHeaders = decompress(version, compressedHeaders);

        Charset iso1 = Charset.forName("ISO-8859-1");

        // We know the decoded bytes contain the full headers,
        // so optimize instead of looping byte by byte
        int count = readCount(version, decompressedHeaders);
        for (int i = 0; i < count; ++i)
        {
            int nameLength = readNameLength(version, decompressedHeaders);
            if (nameLength == 0)
                throw new StreamException(streamId, StreamStatus.PROTOCOL_ERROR, "Invalid header name length");
            byte[] nameBytes = new byte[nameLength];
            decompressedHeaders.get(nameBytes);
            String name = new String(nameBytes, iso1);

            int valueLength = readValueLength(version, decompressedHeaders);
            if (valueLength == 0)
                throw new StreamException(streamId, StreamStatus.PROTOCOL_ERROR, "Invalid header value length");
            byte[] valueBytes = new byte[valueLength];
            decompressedHeaders.get(valueBytes);
            String value = new String(valueBytes, iso1);
            // Multi valued headers are separate by NUL
            String[] values = value.split("\u0000");
            // Check if there are multiple NULs (section 2.6.9)
            for (String v : values)
                if (v.length() == 0)
                    throw new StreamException(streamId, StreamStatus.PROTOCOL_ERROR, "Invalid multi valued header");

            onHeader(name, values);
        }

        return true;
    }

    private boolean accumulate(int length, ByteBuffer buffer)
    {
        int remaining = buffer.remaining();
        if (data == null)
        {
            if (remaining < length)
            {
                data = new byte[remaining];
                buffer.get(data);
                return false;
            }
            else
            {
                data = new byte[length];
                buffer.get(data);
                return true;
            }
        }
        else
        {
            int accumulated = data.length;
            int needed = length - accumulated;
            if (remaining < needed)
            {
                byte[] local = new byte[accumulated + remaining];
                System.arraycopy(data, 0, local, 0, accumulated);
                buffer.get(local, accumulated, remaining);
                data = local;
                return false;
            }
            else
            {
                byte[] local = new byte[length];
                System.arraycopy(data, 0, local, 0, accumulated);
                buffer.get(local, accumulated, needed);
                data = local;
                return true;
            }
        }
    }

    private int readCount(int version, ByteBuffer buffer)
    {
        switch (version)
        {
            case SPDY.V2:
                return buffer.getShort();
            case SPDY.V3:
                return buffer.getInt();
            default:
                throw new IllegalStateException();
        }
    }

    private int readNameLength(int version, ByteBuffer buffer)
    {
        return readCount(version, buffer);
    }

    private int readValueLength(int version, ByteBuffer buffer)
    {
        return readCount(version, buffer);
    }

    protected abstract void onHeader(String name, String[] values);

    private ByteBuffer decompress(short version, byte[] compressed)
    {
        // Differently from compression, decompression always happens
        // non-concurrently because we read and parse with a single
        // thread, and therefore there is no need for synchronization.

        try
        {
            byte[] decompressed = null;
            byte[] buffer = new byte[compressed.length * 2];
            decompressor.setInput(compressed);

            while (true)
            {
                int count = decompressor.decompress(buffer);
                if (count == 0)
                {
                    if (decompressed != null)
                    {
                        return ByteBuffer.wrap(decompressed);
                    }
                    else if (needsDictionary)
                    {
                        decompressor.setDictionary(CompressionDictionary.get(version));
                        needsDictionary = false;
                    }
                    else
                    {
                        throw new IllegalStateException();
                    }
                }
                else
                {
                    if (count < buffer.length)
                    {
                        if (decompressed == null)
                        {
                            // Only one pass was needed to decompress
                            return ByteBuffer.wrap(buffer, 0, count);
                        }
                        else
                        {
                            // Last pass needed to decompress, merge decompressed bytes
                            byte[] result = new byte[decompressed.length + count];
                            System.arraycopy(decompressed, 0, result, 0, decompressed.length);
                            System.arraycopy(buffer, 0, result, decompressed.length, count);
                            return ByteBuffer.wrap(result);
                        }
                    }
                    else
                    {
                        if (decompressed == null)
                        {
                            decompressed = buffer;
                            buffer = new byte[buffer.length];
                        }
                        else
                        {
                            byte[] result = new byte[decompressed.length + buffer.length];
                            System.arraycopy(decompressed, 0, result, 0, decompressed.length);
                            System.arraycopy(buffer, 0, result, decompressed.length, buffer.length);
                            decompressed = result;
                        }
                    }
                }
            }
        }
        catch (ZipException x)
        {
            // We had a compression problem, and since the compression context
            // is per-connection, we need to tear down the connection
            throw new SessionException(SessionStatus.PROTOCOL_ERROR, x);
        }
    }
}

//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.Name;

public class GzipRequestCustomizer implements HttpConfiguration.Customizer
{
    public static final String GZIP = "gzip";
    private static final HttpField X_CE_GZIP = new HttpField("X-Content-Encoding","gzip");
    private static final Pattern COMMA_GZIP = Pattern.compile(".*, *gzip");

    private final int _compressedBufferSize;
    private final int _inflatedBufferSize;

    public GzipRequestCustomizer()
    {
        this(-1, -1);
    }

    public GzipRequestCustomizer(@Name("compressedBufferSize") int compressedBufferSize, @Name("inflatedBufferSize") int inflatedBufferSize)
    {
        _compressedBufferSize = compressedBufferSize<=0?4*1024:compressedBufferSize;
        _inflatedBufferSize = inflatedBufferSize<=0?16*1024:inflatedBufferSize;
    }

    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        ByteBufferPool bufferPool = request.getHttpChannel().getByteBufferPool();

        try
        {
            HttpFields fields = request.getHttpFields();
            String content_encoding = fields.get(HttpHeader.CONTENT_ENCODING);
            if (content_encoding == null)
                return;

            if (content_encoding.equalsIgnoreCase("gzip"))
            {
                fields.remove(HttpHeader.CONTENT_ENCODING);
            }
            else if (COMMA_GZIP.matcher(content_encoding).matches())
            {
                fields.remove(HttpHeader.CONTENT_ENCODING);
                fields.add(HttpHeader.CONTENT_ENCODING, content_encoding.substring(0, content_encoding.lastIndexOf(',')));
            }
            else
            {
                return;
            }

            fields.add(X_CE_GZIP);

            // Read all the compressed content into a queue of buffers
            final HttpInput input = request.getHttpInput();
            Queue<ByteBuffer> compressed = new ArrayQueue<>();
            ByteBuffer buffer = null;
            while (true)
            {
                if (buffer==null || BufferUtil.isFull(buffer))
                {
                    buffer = bufferPool.acquire(_compressedBufferSize,false);
                    compressed.add(buffer);
                }
                int l = input.read(buffer.array(), buffer.arrayOffset()+buffer.limit(), BufferUtil.space(buffer));
                if (l<0)
                    break;
                buffer.limit(buffer.limit()+l);
            }
            input.recycle();


            // Handle no content
            if (compressed.size()==1 && BufferUtil.isEmpty(buffer))
            {
                input.eof();
                return;
            }

            input.addContent(new InflatingContent(bufferPool, input, compressed));

        }
        catch(Throwable t)
        {
            throw new BadMessageException(400,"Bad compressed request",t);
        }
    }
    
    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE, END
    }
    
    private class InflatingContent extends HttpInput.Content
    {
        final ByteBufferPool _bufferPool;
        final HttpInput _input;
        final Queue<ByteBuffer> _compressed;
        private final Inflater _inflater = new Inflater(true);
        private State _state = State.INITIAL;
        private int _size;
        private int _value;
        private byte _flags;

        public InflatingContent(ByteBufferPool bufferPool, HttpInput input, Queue<ByteBuffer> compressed)
        {
            super(bufferPool.acquire(_inflatedBufferSize,false));
            _bufferPool = bufferPool;
            _input = input;
            _compressed = compressed;

            inflate();
        }

        @Override
        public void succeeded()
        {
            BufferUtil.clear(getContent());
            inflate();
            if (BufferUtil.isEmpty(getContent()) && _state==State.END)
            {
                _bufferPool.release(getContent());
                _input.eof();
            }
            else
            {
                _input.addContent(this);
            }
        }

        @Override
        public void failed(Throwable x)
        {
            _input.failed(x);
        }

        protected void inflate()
        {
            try
            {
                while (true)
                {
                    switch (_state)
                    {
                        case INITIAL:
                        {
                            _state = State.ID;
                            break;
                        }

                        case FLAGS:
                        {
                            if ((_flags & 0x04) == 0x04)
                            {
                                _state = State.EXTRA_LENGTH;
                                _size = 0;
                                _value = 0;
                            }
                            else if ((_flags & 0x08) == 0x08)
                                _state = State.NAME;
                            else if ((_flags & 0x10) == 0x10)
                                _state = State.COMMENT;
                            else if ((_flags & 0x2) == 0x2)
                            {
                                _state = State.HCRC;
                                _size = 0;
                                _value = 0;
                            }
                            else
                            {
                                _state = State.DATA;
                                continue;
                            }
                            break;
                        }

                        case DATA:
                        {
                            while (true)
                            {
                                ByteBuffer buffer = getContent();

                                if (BufferUtil.isFull(buffer))
                                    return;

                                try
                                {
                                    int length = _inflater.inflate(buffer.array(), buffer.arrayOffset() + buffer.position(), BufferUtil.space(buffer));
                                    buffer.limit(buffer.limit()+length);
                                }
                                catch (DataFormatException x)
                                {
                                    throw new ZipException(x.getMessage());
                                }

                                if (_inflater.needsInput())
                                {
                                    ByteBuffer data = _compressed.peek();
                                    while(data!=null && BufferUtil.isEmpty(data))
                                    {
                                        _bufferPool.release(_compressed.poll());
                                        data = _compressed.peek();
                                    }
                                    if (data==null)
                                        return;

                                    _inflater.setInput(data.array(), data.arrayOffset() + data.position(), data.remaining());
                                    data.position(data.limit());
                                }
                                else if (_inflater.finished())
                                {
                                    ByteBuffer data = _compressed.peek();
                                    int remaining = _inflater.getRemaining();
                                    data.position(data.limit() - remaining);
                                    _state = State.CRC;
                                    _size = 0;
                                    _value = 0;
                                    break;
                                }
                            }
                            continue;
                        }

                        default:
                            break;
                    }

                    ByteBuffer data = _compressed.peek();
                    if (BufferUtil.isEmpty(data))
                        break;

                    byte currByte = data.get();
                    switch (_state)
                    {
                        case ID:
                        {
                            _value += (currByte & 0xFF) << 8 * _size;
                            ++_size;
                            if (_size == 2)
                            {
                                if (_value != 0x8B1F)
                                    throw new ZipException("Invalid gzip bytes");
                                _state = State.CM;
                            }
                            break;
                        }
                        case CM:
                        {
                            if ((currByte & 0xFF) != 0x08)
                                throw new ZipException("Invalid gzip compression method");
                            _state = State.FLG;
                            break;
                        }
                        case FLG:
                        {
                            _flags = currByte;
                            _state = State.MTIME;
                            _size = 0;
                            _value = 0;
                            break;
                        }
                        case MTIME:
                        {
                            // Skip the 4 MTIME bytes
                            ++_size;
                            if (_size == 4)
                                _state = State.XFL;
                            break;
                        }
                        case XFL:
                        {
                            // Skip XFL
                            _state = State.OS;
                            break;
                        }
                        case OS:
                        {
                            // Skip OS
                            _state = State.FLAGS;
                            break;
                        }
                        case EXTRA_LENGTH:
                        {
                            _value += (currByte & 0xFF) << 8 * _size;
                            ++_size;
                            if (_size == 2)
                                _state = State.EXTRA;
                            break;
                        }
                        case EXTRA:
                        {
                            // Skip EXTRA bytes
                            --_value;
                            if (_value == 0)
                            {
                                // Clear the EXTRA flag and loop on the flags
                                _flags &= ~0x04;
                                _state = State.FLAGS;
                            }
                            break;
                        }
                        case NAME:
                        {
                            // Skip NAME bytes
                            if (currByte == 0)
                            {
                                // Clear the NAME flag and loop on the flags
                                _flags &= ~0x08;
                                _state = State.FLAGS;
                            }
                            break;
                        }
                        case COMMENT:
                        {
                            // Skip COMMENT bytes
                            if (currByte == 0)
                            {
                                // Clear the COMMENT flag and loop on the flags
                                _flags &= ~0x10;
                                _state = State.FLAGS;
                            }
                            break;
                        }
                        case HCRC:
                        {
                            // Skip HCRC
                            ++_size;
                            if (_size == 2)
                            {
                                // Clear the HCRC flag and loop on the flags
                                _flags &= ~0x02;
                                _state = State.FLAGS;
                            }
                            break;
                        }
                        case CRC:
                        {
                            _value += (currByte & 0xFF) << 8 * _size;
                            ++_size;
                            if (_size == 4)
                            {
                                // From RFC 1952, compliant decoders need not to verify the CRC
                                _state = State.ISIZE;
                                _size = 0;
                                _value = 0;
                            }
                            break;
                        }
                        case ISIZE:
                        {
                            _value += (currByte & 0xFF) << 8 * _size;
                            ++_size;
                            if (_size == 4)
                            {
                                if (_value != _inflater.getBytesWritten())
                                    throw new ZipException("Invalid input size");

                                _inflater.reset();
                                _state = State.END;
                                return;
                            }
                            break;
                        }
                        default:
                            throw new ZipException();
                    }
                }
            }
            catch (ZipException x)
            {
                throw new RuntimeException(x);
            }
        }
    }
}

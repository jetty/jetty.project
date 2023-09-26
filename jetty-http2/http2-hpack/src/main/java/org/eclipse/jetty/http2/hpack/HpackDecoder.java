//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTokens;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.compression.EncodingException;
import org.eclipse.jetty.http.compression.HuffmanDecoder;
import org.eclipse.jetty.http.compression.NBitIntegerDecoder;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Hpack Decoder
 * <p>This is not thread safe and may only be called by 1 thread at a time.</p>
 */
public class HpackDecoder
{
    public static final Logger LOG = Log.getLogger(HpackDecoder.class);
    public static final HttpField.LongValueHttpField CONTENT_LENGTH_0 =
        new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, 0L);

    private final HpackContext _context;
    private final MetaDataBuilder _builder;
    private final HuffmanDecoder _huffmanDecoder;
    private final NBitIntegerDecoder _integerDecoder;
    private int _maxTableCapacity;

    @Deprecated
    public HpackDecoder(int localMaxDynamicTableSize, int maxHeaderSize)
    {
        this(maxHeaderSize);
    }

    /**
     * @param maxHeaderSize The maximum allowed size of a decoded headers block,
     * expressed as total of all name and value bytes, plus 32 bytes per field
     */
    public HpackDecoder(int maxHeaderSize)
    {
        _context = new HpackContext(HpackContext.DEFAULT_MAX_TABLE_CAPACITY);
        _builder = new MetaDataBuilder(maxHeaderSize);
        _huffmanDecoder = new HuffmanDecoder();
        _integerDecoder = new NBitIntegerDecoder();
        setMaxTableCapacity(HpackContext.DEFAULT_MAX_TABLE_CAPACITY);
    }

    public HpackContext getHpackContext()
    {
        return _context;
    }

    public int getMaxTableCapacity()
    {
        return _maxTableCapacity;
    }

    /**
     * <p>Sets the limit for the capacity of the dynamic header table.</p>
     * <p>This value acts as a limit for the values received from the
     * remote peer via the HPACK dynamic table size update instruction.</p>
     * <p>After calling this method, a SETTINGS frame must be sent to the other
     * peer, containing the {@code SETTINGS_HEADER_TABLE_SIZE} setting with
     * the value passed as argument to this method.</p>
     *
     * @param maxTableCapacity the limit for capacity of the dynamic header table
     */
    public void setMaxTableCapacity(int maxTableCapacity)
    {
        _maxTableCapacity = maxTableCapacity;
    }

    /**
     * @param maxTableSizeLimit the local dynamic table max size
     * @deprecated use {@link #setMaxTableCapacity(int)} instead
     */
    @Deprecated
    public void setLocalMaxDynamicTableSize(int maxTableSizeLimit)
    {
        setMaxTableCapacity(maxTableSizeLimit);
    }

    public int getMaxHeaderListSize()
    {
        return _builder.getMaxSize();
    }

    public void setMaxHeaderListSize(int maxHeaderListSize)
    {
        _builder.setMaxSize(maxHeaderListSize);
    }

    public MetaData decode(ByteBuffer buffer) throws HpackException.SessionException, HpackException.StreamException
    {
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("CtxTbl[%x] decoding %d octets", _context.hashCode(), buffer.remaining()));

        // If the buffer is larger than the max headers size, don't even start decoding it.
        int maxSize = _builder.getMaxSize();
        if (maxSize > 0 && buffer.remaining() > maxSize)
            throw new HpackException.SessionException("Header fields size too large");

        boolean emitted = false;
        while (buffer.hasRemaining())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode {}", BufferUtil.toHexString(buffer));

            byte b = buffer.get();
            if (b < 0)
            {
                // 7.1 indexed if the high bit is set
                int index = integerDecode(buffer, 7);
                Entry entry = _context.get(index);
                if (entry == null)
                    throw new HpackException.SessionException("Unknown index %d", index);

                if (entry.isStatic())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("decode IdxStatic {}", entry);
                    // emit field
                    emitted = true;
                    _builder.emit(entry.getHttpField());

                    // TODO copy and add to reference set if there is room
                    // _context.add(entry.getHttpField());
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("decode Idx {}", entry);
                    // emit
                    emitted = true;
                    _builder.emit(entry.getHttpField());
                }
            }
            else
            {
                // look at the first nibble in detail
                byte f = (byte)((b & 0xF0) >> 4);
                String name;
                HttpHeader header;
                String value;

                boolean indexed;
                int nameIndex;

                switch (f)
                {
                    case 2: // 7.3
                    case 3: // 7.3
                        // change table size
                        int size = integerDecode(buffer, 5);
                        if (LOG.isDebugEnabled())
                            LOG.debug("decode resize={}", size);
                        if (size > getMaxTableCapacity())
                            throw new HpackException.CompressionException("Dynamic table resize exceeded max limit");
                        if (emitted)
                            throw new HpackException.CompressionException("Dynamic table resize after fields");
                        _context.resize(size);
                        continue;

                    case 0: // 7.2.2
                    case 1: // 7.2.3
                        indexed = false;
                        nameIndex = integerDecode(buffer, 4);
                        break;

                    case 4: // 7.2.1
                    case 5: // 7.2.1
                    case 6: // 7.2.1
                    case 7: // 7.2.1
                        indexed = true;
                        nameIndex = integerDecode(buffer, 6);
                        break;

                    default:
                        throw new IllegalStateException();
                }

                boolean huffmanName = false;

                // decode the name
                if (nameIndex > 0)
                {
                    Entry nameEntry = _context.get(nameIndex);
                    name = nameEntry.getHttpField().getName();
                    header = nameEntry.getHttpField().getHeader();
                }
                else
                {
                    huffmanName = (buffer.get() & 0x80) == 0x80;
                    int length = integerDecode(buffer, 7);
                    if (huffmanName)
                        name = huffmanDecode(buffer, length);
                    else
                        name = toISO88591String(buffer, length);
                    check:
                    for (int i = name.length(); i-- > 0; )
                    {
                        char c = name.charAt(i);
                        if (c > 0xff)
                        {
                            _builder.streamException("Illegal header name %s", name);
                            break;
                        }
                        HttpTokens.Token token = HttpTokens.TOKENS[0xFF & c];
                        switch (token.getType())
                        {
                            case ALPHA:
                                if (c >= 'A' && c <= 'Z')
                                {
                                    _builder.streamException("Uppercase header name %s", name);
                                    break check;
                                }
                                break;

                            case COLON:
                            case TCHAR:
                            case DIGIT:
                                break;

                            default:
                                _builder.streamException("Illegal header name %s", name);
                                break check;
                        }
                    }
                    header = HttpHeader.CACHE.get(name);
                }

                // decode the value
                boolean huffmanValue = (buffer.get() & 0x80) == 0x80;
                int length = integerDecode(buffer, 7);
                if (huffmanValue)
                    value = huffmanDecode(buffer, length);
                else
                    value = toISO88591String(buffer, length);

                // Make the new field
                HttpField field;
                if (header == null)
                {
                    // just make a normal field and bypass header name lookup
                    field = new HttpField(null, name, value);
                }
                else
                {
                    // might be worthwhile to create a value HttpField if it is indexed
                    // and/or of a type that may be looked up multiple times.
                    switch (header)
                    {
                        case C_STATUS:
                            if (indexed)
                                field = new HttpField.IntValueHttpField(header, name, value);
                            else
                                field = new HttpField(header, name, value);
                            break;

                        case C_AUTHORITY:
                            field = new AuthorityHttpField(value);
                            break;

                        case CONTENT_LENGTH:
                            if ("0".equals(value))
                                field = CONTENT_LENGTH_0;
                            else
                                field = new HttpField.LongValueHttpField(header, name, value);
                            break;

                        default:
                            field = new HttpField(header, name, value);
                            break;
                    }
                }

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("decoded '{}' by {}/{}/{}",
                        field,
                        nameIndex > 0 ? "IdxName" : (huffmanName ? "HuffName" : "LitName"),
                        huffmanValue ? "HuffVal" : "LitVal",
                        indexed ? "Idx" : "");
                }

                // emit the field
                emitted = true;
                _builder.emit(field);

                // if indexed add to dynamic table
                if (indexed)
                    _context.add(field);
            }
        }

        return _builder.build();
    }

    private int integerDecode(ByteBuffer buffer, int prefix) throws HpackException.CompressionException
    {
        try
        {
            if (prefix != 8)
                buffer.position(buffer.position() - 1);

            _integerDecoder.setPrefix(prefix);
            int decodedInt = _integerDecoder.decodeInt(buffer);
            if (decodedInt < 0)
                throw new EncodingException("invalid integer encoding");
            return decodedInt;
        }
        catch (EncodingException e)
        {
            HpackException.CompressionException compressionException = new HpackException.CompressionException(e.getMessage());
            compressionException.initCause(e);
            throw compressionException;
        }
        finally
        {
            _integerDecoder.reset();
        }
    }

    private String huffmanDecode(ByteBuffer buffer, int length) throws HpackException.CompressionException
    {
        try
        {
            _huffmanDecoder.setLength(length);
            String decoded = _huffmanDecoder.decode(buffer);
            if (decoded == null)
                throw new HpackException.CompressionException("invalid string encoding");
            return decoded;
        }
        catch (EncodingException e)
        {
            HpackException.CompressionException compressionException = new HpackException.CompressionException(e.getMessage());
            compressionException.initCause(e);
            throw compressionException;
        }
        finally
        {
            _huffmanDecoder.reset();
        }
    }

    public static String toISO88591String(ByteBuffer buffer, int length)
    {
        CharsetStringBuilder.Iso88591StringBuilder builder = new CharsetStringBuilder.Iso88591StringBuilder();
        for (int i = 0; i < length; ++i)
        {
            builder.append(HttpTokens.sanitizeFieldVchar((char)buffer.get()));
        }
        return builder.build();
    }

    @Override
    public String toString()
    {
        return String.format("HpackDecoder@%x{%s}", hashCode(), _context);
    }
}

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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.compression.HuffmanEncoder;
import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.http.compression.NBitStringEncoder;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.http2.hpack.HpackContext.StaticEntry;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HpackEncoder
{
    private static final Logger LOG = Log.getLogger(HpackEncoder.class);
    private static final HttpField[] STATUSES = new HttpField[599];
    static final EnumSet<HttpHeader> DO_NOT_HUFFMAN =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.PROXY_AUTHENTICATE,
            HttpHeader.PROXY_AUTHORIZATION);
    static final EnumSet<HttpHeader> DO_NOT_INDEX =
        EnumSet.of(
            // HttpHeader.C_PATH,  // TODO more data needed
            // HttpHeader.DATE,    // TODO more data needed
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.CONTENT_RANGE,
            HttpHeader.ETAG,
            HttpHeader.IF_MODIFIED_SINCE,
            HttpHeader.IF_UNMODIFIED_SINCE,
            HttpHeader.IF_NONE_MATCH,
            HttpHeader.IF_RANGE,
            HttpHeader.IF_MATCH,
            HttpHeader.LOCATION,
            HttpHeader.RANGE,
            HttpHeader.RETRY_AFTER,
            // HttpHeader.EXPIRES,
            HttpHeader.LAST_MODIFIED,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);
    static final EnumSet<HttpHeader> NEVER_INDEX =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);
    private static final EnumSet<HttpHeader> IGNORED_HEADERS = EnumSet.of(HttpHeader.CONNECTION, HttpHeader.KEEP_ALIVE,
        HttpHeader.PROXY_CONNECTION, HttpHeader.TRANSFER_ENCODING, HttpHeader.UPGRADE);
    private static final PreEncodedHttpField TE_TRAILERS = new PreEncodedHttpField(HttpHeader.TE, "trailers");
    private static final PreEncodedHttpField C_SCHEME_HTTP = new PreEncodedHttpField(HttpHeader.C_SCHEME, "http");
    private static final PreEncodedHttpField C_SCHEME_HTTPS = new PreEncodedHttpField(HttpHeader.C_SCHEME, "https");
    private static final EnumMap<HttpMethod, PreEncodedHttpField> C_METHODS = new EnumMap<>(HttpMethod.class);

    static
    {
        for (HttpStatus.Code code : HttpStatus.Code.values())
        {
            STATUSES[code.getCode()] = new PreEncodedHttpField(HttpHeader.C_STATUS, Integer.toString(code.getCode()));
        }
        for (HttpMethod method : HttpMethod.values())
        {
            C_METHODS.put(method, new PreEncodedHttpField(HttpHeader.C_METHOD, method.asString()));
        }
    }

    private final HpackContext _context;
    private final boolean _debug;
    private int _maxTableCapacity;
    private int _tableCapacity;
    private int _maxHeaderListSize;
    private int _headerListSize;
    private boolean _validateEncoding = true;

    @Deprecated
    public HpackEncoder(int localMaxDynamicTableSize)
    {
        this(localMaxDynamicTableSize, 4096, -1);
    }

    @Deprecated
    public HpackEncoder(int localMaxDynamicTableSize, int remoteMaxDynamicTableSize)
    {
        this(localMaxDynamicTableSize, remoteMaxDynamicTableSize, -1);
    }

    @Deprecated
    public HpackEncoder(int localMaxDynamicTableSize, int remoteMaxDynamicTableSize, int maxHeaderListSize)
    {
        this();
        setLocalMaxDynamicTableSize(localMaxDynamicTableSize);
        setRemoteMaxDynamicTableSize(remoteMaxDynamicTableSize);
        setMaxHeaderListSize(maxHeaderListSize);
    }

    public HpackEncoder()
    {
        _context = new HpackContext(0);
        _debug = LOG.isDebugEnabled();
        setMaxTableCapacity(HpackContext.DEFAULT_MAX_TABLE_CAPACITY);
        setTableCapacity(HpackContext.DEFAULT_MAX_TABLE_CAPACITY);
    }

    public int getMaxTableCapacity()
    {
        return _maxTableCapacity;
    }

    /**
     * <p>Sets the limit for the capacity of the dynamic header table.</p>
     * <p>This value is set by the remote peer via the
     * {@code SETTINGS_HEADER_TABLE_SIZE} setting.</p>
     *
     * @param maxTableSizeLimit the limit for capacity of the dynamic header table
     */
    public void setMaxTableCapacity(int maxTableSizeLimit)
    {
        _maxTableCapacity = maxTableSizeLimit;
    }

    public int getTableCapacity()
    {
        return _tableCapacity;
    }

    /**
     * <p>Sets the capacity of the dynamic header table.</p>
     * <p>The value of the capacity may be changed from {@code 0}
     * up to {@link #getMaxTableCapacity()}.
     * An HPACK instruction with the new capacity value will
     * be sent to the decoder when the next call to
     * {@link #encode(ByteBuffer, MetaData)} is made.</p>
     *
     * @param tableCapacity the capacity of the dynamic header table
     */
    public void setTableCapacity(int tableCapacity)
    {
        if (tableCapacity > getMaxTableCapacity())
            throw new IllegalArgumentException("Max table capacity exceeded");
        _tableCapacity = tableCapacity;
    }

    public int getMaxHeaderListSize()
    {
        return _maxHeaderListSize;
    }

    public void setMaxHeaderListSize(int maxHeaderListSize)
    {
        _maxHeaderListSize = maxHeaderListSize;
    }

    public HpackContext getHpackContext()
    {
        return _context;
    }

    @Deprecated
    public void setRemoteMaxDynamicTableSize(int maxTableSize)
    {
        setTableCapacity(maxTableSize);
    }

    @Deprecated
    public void setLocalMaxDynamicTableSize(int maxTableSizeLimit)
    {
        setMaxTableCapacity(maxTableSizeLimit);
    }

    public boolean isValidateEncoding()
    {
        return _validateEncoding;
    }

    public void setValidateEncoding(boolean validateEncoding)
    {
        _validateEncoding = validateEncoding;
    }

    public void encode(ByteBuffer buffer, MetaData metadata) throws HpackException
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("CtxTbl[%x] encoding", _context.hashCode()));

            HttpFields fields = metadata.getFields();
            // Verify that we can encode without errors.
            if (isValidateEncoding() && fields != null)
            {
                for (HttpField field : fields)
                {
                    String name = field.getName();
                    char firstChar = name.charAt(0);
                    if (firstChar <= ' ' || firstChar == ':')
                        throw new HpackException.StreamException("Invalid header name: '%s'", name);
                }
            }

            _headerListSize = 0;
            int pos = buffer.position();

            // If max table size changed, send the correspondent instruction.
            int tableCapacity = getTableCapacity();
            if (tableCapacity != _context.getMaxDynamicTableSize())
                encodeMaxDynamicTableSize(buffer, tableCapacity);

            // Add Request/response meta fields
            if (metadata.isRequest())
            {
                MetaData.Request request = (MetaData.Request)metadata;

                String scheme = request.getURI().getScheme();
                encode(buffer, HttpScheme.HTTPS.is(scheme) ? C_SCHEME_HTTPS : C_SCHEME_HTTP);
                String method = request.getMethod();
                HttpMethod httpMethod = method == null ? null : HttpMethod.fromString(method);
                HttpField methodField = C_METHODS.get(httpMethod);
                encode(buffer, methodField == null ? new HttpField(HttpHeader.C_METHOD, method) : methodField);
                encode(buffer, new HttpField(HttpHeader.C_AUTHORITY, request.getURI().getAuthority()));
                encode(buffer, new HttpField(HttpHeader.C_PATH, request.getURI().getPathQuery()));
            }
            else if (metadata.isResponse())
            {
                MetaData.Response response = (MetaData.Response)metadata;
                int code = response.getStatus();
                HttpField status = code < STATUSES.length ? STATUSES[code] : null;
                if (status == null)
                    status = new HttpField.IntValueHttpField(HttpHeader.C_STATUS, code);
                encode(buffer, status);
            }

            // Remove fields as specified in RFC 7540, 8.1.2.2.
            if (fields != null)
            {
                // Remove the headers specified in the Connection header,
                // for example: Connection: Close, TE, Upgrade, Custom.
                Set<String> hopHeaders = null;
                for (String value : fields.getCSV(HttpHeader.CONNECTION, false))
                {
                    if (hopHeaders == null)
                        hopHeaders = new HashSet<>();
                    hopHeaders.add(StringUtil.asciiToLowerCase(value));
                }

                boolean contentLengthEncoded = false;
                for (HttpField field : fields)
                {
                    HttpHeader header = field.getHeader();
                    if (header != null && IGNORED_HEADERS.contains(header))
                        continue;
                    if (header == HttpHeader.TE)
                    {
                        if (field.contains("trailers"))
                            encode(buffer, TE_TRAILERS);
                        continue;
                    }
                    String name = field.getLowerCaseName();
                    if (hopHeaders != null && hopHeaders.contains(name))
                        continue;
                    if (header == HttpHeader.CONTENT_LENGTH)
                        contentLengthEncoded = true;
                    encode(buffer, field);
                }

                if (!contentLengthEncoded)
                {
                    long contentLength = metadata.getContentLength();
                    if (contentLength >= 0)
                        encode(buffer, new HttpField(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength)));
                }
            }

            int maxHeaderListSize = getMaxHeaderListSize();
            if (maxHeaderListSize > 0 && _headerListSize > maxHeaderListSize)
                throw new HpackException.SessionException("Header size %d > %d", _headerListSize, maxHeaderListSize);

            if (LOG.isDebugEnabled())
                LOG.debug(String.format("CtxTbl[%x] encoded %d octets", _context.hashCode(), buffer.position() - pos));
        }
        catch (HpackException x)
        {
            throw x;
        }
        catch (Throwable x)
        {
            HpackException.SessionException failure = new HpackException.SessionException("Could not hpack encode %s", metadata);
            failure.initCause(x);
            throw failure;
        }
    }

    public void encodeMaxDynamicTableSize(ByteBuffer buffer, int maxTableSize)
    {
        buffer.put((byte)0x20);
        NBitIntegerEncoder.encode(buffer, 5, maxTableSize);
        _context.resize(maxTableSize);
    }

    public void encode(ByteBuffer buffer, HttpField field)
    {
        if (field.getValue() == null)
            field = new HttpField(field.getHeader(), field.getName(), "");

        int fieldSize = field.getName().length() + field.getValue().length();
        _headerListSize += fieldSize + 32;

        String encoding = null;

        // Is there an index entry for the field?
        Entry entry = _context.get(field);
        if (entry != null)
        {
            // This is a known indexed field, send as static or dynamic indexed.
            if (entry.isStatic())
            {
                buffer.put(((StaticEntry)entry).getEncodedField());
                if (_debug)
                    encoding = "IdxFieldS1";
            }
            else
            {
                int index = _context.index(entry);
                buffer.put((byte)0x80);
                NBitIntegerEncoder.encode(buffer, 7, index);
                if (_debug)
                    encoding = "IdxField" + (entry.isStatic() ? "S" : "") + NBitIntegerEncoder.octetsNeeded(7, index);
            }
        }
        else
        {
            // Unknown field entry, so we will have to send literally, but perhaps add an index.
            final boolean indexed;

            // Do we know its name?
            HttpHeader header = field.getHeader();

            // Select encoding strategy
            if (header == null)
            {
                // Select encoding strategy for unknown header names
                Entry name = _context.get(field.getName());

                if (field instanceof PreEncodedHttpField)
                {
                    int i = buffer.position();
                    ((PreEncodedHttpField)field).putTo(buffer, HttpVersion.HTTP_2);
                    byte b = buffer.get(i);
                    indexed = b < 0 || b >= 0x40;
                    if (_debug)
                        encoding = indexed ? "PreEncodedIdx" : "PreEncoded";
                }
                else if (name == null && fieldSize < _context.getMaxDynamicTableSize())
                {
                    // unknown name and value that will fit in dynamic table, so let's index
                    // this just in case it is the first time we have seen a custom name or a
                    // custom field.  Unless the name is once only, this is worthwhile
                    indexed = true;
                    encodeName(buffer, (byte)0x40, 6, field.getName(), null);
                    encodeValue(buffer, true, field.getValue());
                    if (_debug)
                        encoding = "LitHuffNHuffVIdx";
                }
                else
                {
                    // Known name, but different value.
                    // This is probably a custom field with changing value, so don't index.
                    indexed = false;
                    encodeName(buffer, (byte)0x00, 4, field.getName(), null);
                    encodeValue(buffer, true, field.getValue());
                    if (_debug)
                        encoding = "LitHuffNHuffV!Idx";
                }
            }
            else
            {
                // Select encoding strategy for known header names
                Entry name = _context.get(header);

                if (field instanceof PreEncodedHttpField)
                {
                    // Preencoded field
                    int i = buffer.position();
                    ((PreEncodedHttpField)field).putTo(buffer, HttpVersion.HTTP_2);
                    byte b = buffer.get(i);
                    indexed = b < 0 || b >= 0x40;
                    if (_debug)
                        encoding = indexed ? "PreEncodedIdx" : "PreEncoded";
                }
                else if (DO_NOT_INDEX.contains(header))
                {
                    // Non indexed field
                    indexed = false;
                    boolean neverIndex = NEVER_INDEX.contains(header);
                    boolean huffman = !DO_NOT_HUFFMAN.contains(header);
                    encodeName(buffer, neverIndex ? (byte)0x10 : (byte)0x00, 4, header.asString(), name);
                    encodeValue(buffer, huffman, field.getValue());

                    if (_debug)
                        encoding = "Lit" +
                            ((name == null) ? "HuffN" : ("IdxN" + (name.isStatic() ? "S" : "") + (1 + NBitIntegerEncoder.octetsNeeded(4, _context.index(name))))) +
                            (huffman ? "HuffV" : "LitV") +
                            (neverIndex ? "!!Idx" : "!Idx");
                }
                else if (fieldSize >= _context.getMaxDynamicTableSize() || header == HttpHeader.CONTENT_LENGTH && !"0".equals(field.getValue()))
                {
                    // The field is too large or a non-zero content length, so do not index.
                    indexed = false;
                    encodeName(buffer, (byte)0x00, 4, header.asString(), name);
                    encodeValue(buffer, true, field.getValue());
                    if (_debug)
                        encoding = "Lit" +
                            ((name == null) ? "HuffN" : "IdxNS" + (1 + NBitIntegerEncoder.octetsNeeded(4, _context.index(name)))) +
                            "HuffV!Idx";
                }
                else
                {
                    // indexed
                    indexed = true;
                    boolean huffman = !DO_NOT_HUFFMAN.contains(header);
                    encodeName(buffer, (byte)0x40, 6, header.asString(), name);
                    encodeValue(buffer, huffman, field.getValue());
                    if (_debug)
                        encoding = ((name == null) ? "LitHuffN" : ("LitIdxN" + (name.isStatic() ? "S" : "") + (1 + NBitIntegerEncoder.octetsNeeded(6, _context.index(name))))) +
                            (huffman ? "HuffVIdx" : "LitVIdx");
                }
            }

            // If we want the field referenced, then we add it to our table and reference set.
            if (indexed)
                _context.add(field);
        }

        if (_debug)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("encode {}:'{}' to '{}'", encoding, field, BufferUtil.toHexString((ByteBuffer)buffer.duplicate().flip()));
        }
    }

    private void encodeName(ByteBuffer buffer, byte mask, int bits, String name, Entry entry)
    {
        buffer.put(mask);
        if (entry == null)
        {
            // leave name index bits as 0
            // Encode the name always with lowercase huffman
            buffer.put((byte)0x80);
            NBitIntegerEncoder.encode(buffer, 7, HuffmanEncoder.octetsNeededLowerCase(name));
            HuffmanEncoder.encodeLowerCase(buffer, name);
        }
        else
        {
            NBitIntegerEncoder.encode(buffer, bits, _context.index(entry));
        }
    }

    static void encodeValue(ByteBuffer buffer, boolean huffman, String value)
    {
        NBitStringEncoder.encode(buffer, 8, value, huffman);
    }
}

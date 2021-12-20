//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack.internal.metadata;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.QpackException;

import static org.eclipse.jetty.http3.qpack.QpackException.H3_GENERAL_PROTOCOL_ERROR;

public class MetaDataBuilder
{
    private final int _maxSize;
    private final HttpFields.Mutable _fields = HttpFields.build();
    private int _size;
    private Integer _status;
    private String _method;
    private HttpScheme _scheme;
    private HostPortHttpField _authority;
    private String _path;
    private String _protocol;
    private long _contentLength = Long.MIN_VALUE;
    private QpackException.StreamException _streamException;
    private boolean _request;
    private boolean _response;

    /**
     * @param maxHeadersSize The maximum size of the headers, expressed as total name and value characters.
     */
    public MetaDataBuilder(int maxHeadersSize)
    {
        _maxSize = maxHeadersSize;
    }

    /**
     * Get the maxSize.
     *
     * @return the maxSize
     */
    public int getMaxSize()
    {
        return _maxSize;
    }

    /**
     * Get the size.
     *
     * @return the current size in bytes
     */
    public int getSize()
    {
        return _size;
    }

    public void emit(HttpField field) throws QpackException.SessionException
    {
        HttpHeader header = field.getHeader();
        String name = field.getName();
        if (name == null || name.length() == 0)
            throw new QpackException.SessionException(QpackException.QPACK_DECOMPRESSION_FAILED, "Header size 0");
        String value = field.getValue();
        int fieldSize = name.length() + (value == null ? 0 : value.length());
        _size += fieldSize + 32;
        if (_size > _maxSize)
            throw new QpackException.SessionException(QpackException.QPACK_DECOMPRESSION_FAILED, String.format("Header size %d > %d", _size, _maxSize));

        if (field instanceof StaticTableHttpField)
        {
            StaticTableHttpField staticField = (StaticTableHttpField)field;
            switch (header)
            {
                case C_STATUS:
                    if (checkPseudoHeader(header, _status))
                        _status = staticField.getIntValue();
                    _response = true;
                    break;

                case C_METHOD:
                    if (checkPseudoHeader(header, _method))
                        _method = value;
                    _request = true;
                    break;

                case C_SCHEME:
                    if (checkPseudoHeader(header, _scheme))
                        _scheme = (HttpScheme)staticField.getStaticValue();
                    _request = true;
                    break;

                default:
                    throw new IllegalArgumentException(name);
            }
        }
        else if (header != null)
        {
            switch (header)
            {
                case C_STATUS:
                    if (checkPseudoHeader(header, _status))
                        _status = field.getIntValue();
                    _response = true;
                    break;

                case C_METHOD:
                    if (checkPseudoHeader(header, _method))
                        _method = value;
                    _request = true;
                    break;

                case C_SCHEME:
                    if (checkPseudoHeader(header, _scheme) && value != null)
                        _scheme = HttpScheme.CACHE.get(value);
                    _request = true;
                    break;

                case C_AUTHORITY:
                    if (checkPseudoHeader(header, _authority))
                    {
                        if (field instanceof HostPortHttpField)
                            _authority = (HostPortHttpField)field;
                        else if (value != null)
                            _authority = new AuthorityHttpField(value);
                    }
                    _request = true;
                    break;

                case C_PATH:
                    if (checkPseudoHeader(header, _path))
                    {
                        if (value != null && value.length() > 0)
                            _path = value;
                        else
                            streamException("No Path");
                    }
                    _request = true;
                    break;

                case C_PROTOCOL:
                    if (checkPseudoHeader(header, _protocol))
                        _protocol = value;
                    _request = true;
                    break;

                case HOST:
                    _fields.add(field);
                    break;

                case CONTENT_LENGTH:
                    _contentLength = field.getLongValue();
                    _fields.add(field);
                    break;

                case TE:
                    if ("trailers".equalsIgnoreCase(value))
                        _fields.add(field);
                    else
                        streamException("Unsupported TE value '%s'", value);
                    break;

                case CONNECTION:
                    if ("TE".equalsIgnoreCase(value))
                        _fields.add(field);
                    else
                        streamException("Connection specific field '%s'", header);
                    break;

                default:
                    if (name.charAt(0) == ':')
                        streamException("Unknown pseudo header '%s'", name);
                    else
                        _fields.add(field);
                    break;
            }
        }
        else
        {
            if (name.charAt(0) == ':')
                streamException("Unknown pseudo header '%s'", name);
            else
                _fields.add(field);
        }
    }

    protected void streamException(String messageFormat, Object... args)
    {
        QpackException.StreamException stream = new QpackException.StreamException(QpackException.QPACK_DECOMPRESSION_FAILED, String.format(messageFormat, args));
        if (_streamException == null)
            _streamException = stream;
        else
            _streamException.addSuppressed(stream);
    }

    protected boolean checkPseudoHeader(HttpHeader header, Object value)
    {
        if (_fields.size() > 0)
        {
            streamException("Pseudo header %s after fields", header.asString());
            return false;
        }
        if (value == null)
            return true;
        streamException("Duplicate pseudo header %s", header.asString());
        return false;
    }

    public MetaData build() throws QpackException.StreamException
    {
        if (_streamException != null)
        {
            _streamException.addSuppressed(new Throwable());
            throw _streamException;
        }

        if (_request && _response)
            throw new QpackException.StreamException(H3_GENERAL_PROTOCOL_ERROR, "Request and Response headers");

        HttpFields.Mutable fields = _fields;
        try
        {
            if (_request)
            {
                if (_method == null)
                    throw new QpackException.StreamException(H3_GENERAL_PROTOCOL_ERROR, "No Method");
                boolean isConnect = HttpMethod.CONNECT.is(_method);
                if (!isConnect || _protocol != null)
                {
                    if (_scheme == null)
                        throw new QpackException.StreamException(H3_GENERAL_PROTOCOL_ERROR, "No Scheme");
                    if (_path == null)
                        throw new QpackException.StreamException(H3_GENERAL_PROTOCOL_ERROR, "No Path");
                }
                if (isConnect)
                    return new MetaData.ConnectRequest(_scheme, _authority, _path, fields, _protocol);
                else
                    return new MetaData.Request(
                        _method,
                        _scheme.asString(),
                        _authority,
                        _path,
                        HttpVersion.HTTP_3,
                        fields,
                        _contentLength);
            }
            if (_response)
            {
                if (_status == null)
                    throw new QpackException.StreamException(H3_GENERAL_PROTOCOL_ERROR, "No Status");
                return new MetaData.Response(HttpVersion.HTTP_3, _status, fields, _contentLength);
            }

            return new MetaData(HttpVersion.HTTP_3, fields, _contentLength);
        }
        finally
        {
            _fields.clear();
            _request = false;
            _response = false;
            _status = null;
            _method = null;
            _scheme = null;
            _authority = null;
            _path = null;
            _protocol = null;
            _size = 0;
            _contentLength = Long.MIN_VALUE;
        }
    }
}

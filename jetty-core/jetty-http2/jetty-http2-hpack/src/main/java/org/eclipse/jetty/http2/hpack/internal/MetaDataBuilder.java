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

package org.eclipse.jetty.http2.hpack.internal;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.hpack.HpackException.SessionException;
import org.eclipse.jetty.util.NanoTime;

public class MetaDataBuilder
{
    private final HttpFields.Mutable _fields = HttpFields.build();
    private int _maxSize;
    private int _size;
    private Integer _status;
    private String _method;
    private HttpScheme _scheme;
    private HostPortHttpField _authority;
    private String _path;
    private String _protocol;
    private long _contentLength = -1;
    private HpackException.StreamException _streamException;
    private boolean _request;
    private boolean _response;
    private long _beginNanoTime = Long.MIN_VALUE;

    /**
     * @param maxHeadersSize The maximum size of the headers, expressed as total name and value characters.
     */
    public MetaDataBuilder(int maxHeadersSize)
    {
        _maxSize = maxHeadersSize;
    }

    /**
     * Get the maxSize.
     * @return the maxSize
     */
    public int getMaxSize()
    {
        return _maxSize;
    }

    public void setMaxSize(int maxSize)
    {
        _maxSize = maxSize;
    }

    public void setBeginNanoTime(long beginNanoTime)
    {
        if (beginNanoTime == Long.MIN_VALUE)
            beginNanoTime++;
        _beginNanoTime = beginNanoTime;
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

    public void emit(HttpField field) throws SessionException
    {
        HttpHeader header = field.getHeader();
        String name = field.getName();
        if (name == null || name.isEmpty())
            throw new SessionException("Header size 0");
        String value = field.getValue();
        int fieldSize = name.length() + (value == null ? 0 : value.length());
        _size += fieldSize + 32;
        int maxSize = getMaxSize();
        if (maxSize > 0 && _size > maxSize)
            throw new SessionException("Header size %d > %d", _size, maxSize);

        if (field instanceof StaticTableHttpField staticField)
        {
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
                        if (value != null && !value.isEmpty())
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

    public void streamException(String messageFormat, Object... args)
    {
        HpackException.StreamException stream = new HpackException.StreamException(_request, _response, messageFormat, args);
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

    public MetaData build() throws HpackException.StreamException
    {
        if (_streamException != null)
        {
            _streamException.addSuppressed(new Throwable());
            throw _streamException;
        }

        if (_request && _response)
            throw new HpackException.StreamException(true, true, "Request and Response headers");

        HttpFields.Mutable fields = _fields;
        try
        {
            if (_request)
            {
                if (_method == null)
                    throw new HpackException.StreamException(true, false, "No Method");
                boolean isConnect = HttpMethod.CONNECT.is(_method);
                if (!isConnect || _protocol != null)
                {
                    if (_scheme == null)
                        throw new HpackException.StreamException(true, false, "No Scheme");
                    if (_path == null)
                        throw new HpackException.StreamException(true, false, "No Path");
                }
                long nanoTime = _beginNanoTime == Long.MIN_VALUE ? NanoTime.now() : _beginNanoTime;
                _beginNanoTime = Long.MIN_VALUE;

                if (isConnect)
                {
                    return new MetaData.ConnectRequest(nanoTime, _scheme, _authority, _path, fields, _protocol);
                }
                else
                {
                    return new MetaData.Request(
                        nanoTime,
                        _method,
                        newHttpURI(),
                        HttpVersion.HTTP_2,
                        fields,
                        _contentLength,
                        null);
                }
            }

            if (_response)
            {
                if (_status == null)
                    throw new HpackException.StreamException(false, true, "No Status");
                return new MetaData.Response(_status, null, HttpVersion.HTTP_2, fields, _contentLength);
            }

            return new MetaData(HttpVersion.HTTP_2, fields, _contentLength);
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
            _contentLength = -1;
        }
    }

    private HttpURI newHttpURI() throws HpackException.StreamException
    {
        try
        {
            return HttpURI.build()
                .scheme(_scheme)
                .host(_authority == null ? null : _authority.getHost())
                .port(_authority == null ? -1 : _authority.getPort())
                .pathQuery(_path);
        }
        catch (Throwable x)
        {
            throw new HpackException.StreamException(x, true, false, "Invalid URI");
        }
    }
}

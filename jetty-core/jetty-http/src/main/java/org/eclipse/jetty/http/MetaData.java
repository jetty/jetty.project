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

package org.eclipse.jetty.http;

import java.util.Collections;
import java.util.Iterator;
import java.util.function.Supplier;

public class MetaData implements Iterable<HttpField>
{
    private static final long UNKNOWN_CONTENT_LENGTH = Long.MIN_VALUE;

    /**
     * <p>Returns whether the given HTTP request method and HTTP response status code
     * identify a successful HTTP CONNECT tunnel.</p>
     *
     * @param method the HTTP request method
     * @param status the HTTP response status code
     * @return whether method and status identify a successful HTTP CONNECT tunnel
     */
    public static boolean isTunnel(String method, int status)
    {
        return HttpMethod.CONNECT.is(method) && HttpStatus.isSuccess(status);
    }

    private final HttpVersion _httpVersion;
    private final HttpFields _fields;
    private final long _contentLength;
    private final Supplier<HttpFields> _trailerSupplier;

    public MetaData(HttpVersion version, HttpFields fields)
    {
        this(version, fields, -1);
    }

    public MetaData(HttpVersion version, HttpFields fields, long contentLength)
    {
        this(version, fields, contentLength, null);
    }

    public MetaData(HttpVersion version, HttpFields fields, long contentLength, Supplier<HttpFields> trailerSupplier)
    {
        _httpVersion = version;
        _fields = fields == null ? null : fields.asImmutable();

        _contentLength = contentLength > UNKNOWN_CONTENT_LENGTH ? contentLength : _fields == null ? -1 : _fields.getLongField(HttpHeader.CONTENT_LENGTH);

        _trailerSupplier = trailerSupplier;
    }

    public boolean isRequest()
    {
        return false;
    }

    public boolean isResponse()
    {
        return false;
    }

    /**
     * @return the HTTP version of this MetaData object
     */
    public HttpVersion getHttpVersion()
    {
        return _httpVersion;
    }

    /**
     * @return the HTTP fields of this MetaData object
     */
    public HttpFields getFields()
    {
        return _fields;
    }

    public Supplier<HttpFields> getTrailerSupplier()
    {
        return _trailerSupplier;
    }

    public long getContentLength()
    {
        return _contentLength;
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        if (_fields == null)
            return Collections.emptyIterator();
        return _fields.iterator();
    }

    @Override
    public String toString()
    {
        StringBuilder out = new StringBuilder();
        for (HttpField field : this)
        {
            out.append(field).append(System.lineSeparator());
        }
        return out.toString();
    }

    public static class Request extends MetaData
    {
        private final String _method;
        private final HttpURI _uri;

        public Request(HttpFields fields)
        {
            this(null, null, null, fields);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields fields)
        {
            this(method, uri, version, fields, UNKNOWN_CONTENT_LENGTH);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields fields, long contentLength)
        {
            super(version, fields, contentLength);
            _method = method;
            _uri = uri.asImmutable();
        }

        public Request(String method, String scheme, HostPortHttpField authority, String uri, HttpVersion version, HttpFields fields, long contentLength)
        {
            this(method,
                HttpURI.build().scheme(scheme).host(authority == null ? null : authority.getHost()).port(authority == null ? -1 : authority.getPort()).pathQuery(uri),
                version, fields, contentLength);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields fields, long contentLength, Supplier<HttpFields> trailers)
        {
            super(version, fields, contentLength, trailers);
            _method = method;
            _uri = uri;
        }

        @Override
        public boolean isRequest()
        {
            return true;
        }

        /**
         * @return the HTTP method
         */
        public String getMethod()
        {
            return _method;
        }

        /**
         * @return the HTTP URI
         */
        public HttpURI getURI()
        {
            return _uri;
        }

        /**
         * @return the HTTP URI in string form
         */
        public String getURIString()
        {
            return _uri == null ? null : _uri.toString();
        }

        public String getProtocol()
        {
            return null;
        }

        @Override
        public String toString()
        {
            HttpFields fields = getFields();
            return String.format("%s{u=%s,%s,h=%d,cl=%d,p=%s}",
                    getMethod(), getURI(), getHttpVersion(), fields == null ? -1 : fields.size(), getContentLength(), getProtocol());
        }
    }

    public static class ConnectRequest extends Request
    {
        private final String _protocol;

        public ConnectRequest(HttpScheme scheme, HostPortHttpField authority, String path, HttpFields fields, String protocol)
        {
            this(scheme == null ? null : scheme.asString(), authority, path, fields, protocol);
        }

        public ConnectRequest(String scheme, HostPortHttpField authority, String path, HttpFields fields, String protocol)
        {
            super(HttpMethod.CONNECT.asString(),
                HttpURI.build().scheme(scheme).host(authority == null ? null : authority.getHost()).port(authority == null ? -1 : authority.getPort()).pathQuery(path),
                HttpVersion.HTTP_2, fields, UNKNOWN_CONTENT_LENGTH, null);
            _protocol = protocol;
        }

        @Override
        public String getProtocol()
        {
            return _protocol;
        }
    }

    public static class Response extends MetaData
    {
        private final int _status;
        private final String _reason;

        public Response(HttpVersion version, int status, HttpFields fields)
        {
            this(version, status, fields, UNKNOWN_CONTENT_LENGTH);
        }

        public Response(HttpVersion version, int status, HttpFields fields, long contentLength)
        {
            this(version, status, null, fields, contentLength);
        }

        public Response(HttpVersion version, int status, String reason, HttpFields fields, long contentLength)
        {
            this(version, status, reason, fields, contentLength, null);
        }

        public Response(HttpVersion version, int status, String reason, HttpFields fields, long contentLength, Supplier<HttpFields> trailers)
        {
            super(version, fields, contentLength, trailers);
            _reason = reason;
            _status = status;
        }

        @Override
        public boolean isResponse()
        {
            return true;
        }

        /**
         * @return the HTTP status
         */
        public int getStatus()
        {
            return _status;
        }

        /**
         * @return the HTTP reason
         */
        public String getReason()
        {
            return _reason;
        }

        @Override
        public String toString()
        {
            HttpFields fields = getFields();
            return String.format("%s{s=%d,h=%d,cl=%d}", getHttpVersion(), getStatus(), fields == null ? -1 : fields.size(), getContentLength());
        }
    }
}

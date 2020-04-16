//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.util.Iterator;
import java.util.function.Supplier;

public class MetaData implements Iterable<HttpField>
{
    private static final Supplier<HttpFieldList> SELF_SUPPLIED_TRAILORS = () -> null;
    private static final HttpFieldList SUPPLIED_TRAILERS = new HttpFields().asImmutable();
    private final HttpVersion _httpVersion;
    private final HttpFieldList _fields;
    private final long _contentLengthKnown;
    private final long _contentLengthField;
    private final Supplier<HttpFieldList> _trailerSupplier;
    private HttpFieldList _trailers;

    public MetaData(HttpVersion version, HttpFieldList fields)
    {
        this(version, fields, Long.MIN_VALUE);
    }

    public MetaData(HttpVersion version, HttpFieldList fields, long contentLengthKnown)
    {
        this(version, fields, contentLengthKnown, SELF_SUPPLIED_TRAILORS);
    }

    public MetaData(HttpVersion version, HttpFieldList fields, long contentLengthKnown, Supplier<HttpFieldList> trailers)
    {
        _httpVersion = version;
        _fields = fields == null ? null : fields.asImmutable();
        _contentLengthKnown = contentLengthKnown;
        _contentLengthField = _fields == null ? -1 : _fields.getLongField(HttpHeader.CONTENT_LENGTH);
        if (trailers == SELF_SUPPLIED_TRAILORS)
            _trailerSupplier = () -> _trailers;
        else
        {
            _trailerSupplier = trailers;
            if (trailers != null)
                _trailers = SUPPLIED_TRAILERS;
        }
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
    public HttpFieldList getFields()
    {
        return _fields;
    }

    public boolean mayHaveTrailers()
    {
        return _trailers != null;
    }

    public boolean hasTrailerSupplier()
    {
        return _trailers == SUPPLIED_TRAILERS;
    }

    public Supplier<HttpFieldList> getTrailerSupplier()
    {
        return _trailerSupplier;
    }

    public void setTrailers(HttpFieldList trailers)
    {
        if (_trailers != null)
            throw new IllegalStateException();
        _trailers = trailers;
    }

    public long getContentLengthField()
    {
        return _contentLengthField;
    }

    public long getContentLength()
    {
        return _contentLengthKnown;
    }

    @Override
    public Iterator<HttpField> iterator()
    {
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

        public Request(HttpFieldList fields)
        {
            this(null, null, null, fields);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFieldList fields)
        {
            this(method, uri, version, fields, Long.MIN_VALUE);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFieldList fields, long contentLength)
        {
            super(version, fields, contentLength);
            _method = method;
            _uri = uri;
        }

        public Request(String method, HttpScheme scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFieldList fields)
        {
            this(method, scheme, hostPort, uri, version, fields, Long.MIN_VALUE);
        }

        public Request(String method, HttpScheme scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFieldList fields, long contentLength)
        {
            this(method, scheme == null ? null : scheme.asString(), hostPort, uri, version, fields, contentLength);
        }

        public Request(String method, String scheme, HostPortHttpField hostPort, String uri, HttpVersion version, HttpFieldList fields, long contentLength)
        {
            this(method, new HttpURI.Builder(scheme,
                hostPort == null ? null : hostPort.getHost(),
                hostPort == null ? -1 : hostPort.getPort(),
                uri).build(), version, fields, contentLength);
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
            HttpFieldList fields = getFields();
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
            super(HttpMethod.CONNECT.asString(), scheme, authority, path, HttpVersion.HTTP_2, fields, Long.MIN_VALUE);
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
            this(version, status, fields, Long.MIN_VALUE);
        }

        public Response(HttpVersion version, int status, HttpFields fields, long contentLength)
        {
            this(version, status, null, fields, contentLength);
        }

        public Response(HttpVersion version, int status, String reason, HttpFields fields, long contentLength)
        {
            this(version, status, reason, fields, contentLength, null);
        }

        public Response(HttpVersion version, int status, String reason, HttpFields fields, long contentLength, Supplier<HttpFieldList> trailers)
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
            HttpFieldList fields = getFields();
            return String.format("%s{s=%d,h=%d,cl=%d}", getHttpVersion(), getStatus(), fields == null ? -1 : fields.size(), getContentLength());
        }
    }
}

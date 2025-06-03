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

package org.eclipse.jetty.http;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.jetty.util.NanoTime;

/**
 * <p>Immutable common HTTP information for requests and responses.</p>
 * <p>Specific HTTP request information is captured by {@link Request}.</p>
 * <p>Specific HTTP response information is captured by {@link Response}.</p>
 * <p>HTTP trailers information is captured by {@link MetaData}.</p>
 */
public class MetaData implements Iterable<HttpField>
{
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
    private final HttpFields _httpFields;
    private final long _contentLength;
    private final Supplier<HttpFields> _trailers;

    public MetaData(HttpVersion version, HttpFields fields)
    {
        this(version, fields, -1);
    }

    public MetaData(HttpVersion version, HttpFields fields, long contentLength)
    {
        this(version, fields, contentLength, null);
    }

    public MetaData(HttpVersion version, HttpFields headers, long contentLength, Supplier<HttpFields> trailersSupplier)
    {
        _httpVersion = Objects.requireNonNull(version);
        _httpFields = headers == null ? HttpFields.EMPTY : headers.asImmutable();
        _contentLength = contentLength;
        _trailers = trailersSupplier;
    }

    /**
     * @return whether this object is a {@link Request}
     */
    public boolean isRequest()
    {
        return false;
    }

    /**
     * @return whether this object is a {@link Response}
     */
    public boolean isResponse()
    {
        return false;
    }

    /**
     * Get the HTTP protocol version.
     * @return the HTTP protocol version
     */
    public HttpVersion getHttpVersion()
    {
        return _httpVersion;
    }

    /**
     * Get the HTTP headers or HTTP trailers.
     * @return the HTTP headers or HTTP trailers
     */
    public HttpFields getHttpFields()
    {
        return _httpFields;
    }

    /**
     * @return a supplier for the HTTP trailers
     */
    public Supplier<HttpFields> getTrailersSupplier()
    {
        return _trailers;
    }

    /**
     * Get the length of the content in bytes.
     * @return the length of the content in bytes
     */
    public long getContentLength()
    {
        return _contentLength;
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return _httpFields.iterator();
    }

    @Override
    public String toString()
    {
        return _httpFields.toString();
    }

    /**
     * <p>Immutable HTTP request information.</p>
     */
    public static class Request extends MetaData
    {
        private final String _method;
        private final HttpURI _uri;
        private final long _beginNanoTime;

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields headers)
        {
            this(NanoTime.now(), method, uri, version, headers, -1);
        }

        public Request(long beginNanoTime, String method, HttpURI uri, HttpVersion version, HttpFields headers)
        {
            this(beginNanoTime, method, uri, version, headers, -1);
        }

        public Request(String method, String scheme, HostPortHttpField authority, String uri, HttpVersion version, HttpFields headers, long contentLength)
        {
            this(NanoTime.now(), method,
                HttpURI.build().scheme(scheme).host(authority == null ? null : authority.getHost()).port(authority == null ? -1 : authority.getPort()).pathQuery(uri),
                version, headers, contentLength);
        }

        public Request(long beginNanoTime, String method, String scheme, HostPortHttpField authority, String uri, HttpVersion version, HttpFields headers, long contentLength)
        {
            this(beginNanoTime, method,
                HttpURI.build().scheme(scheme).host(authority == null ? null : authority.getHost()).port(authority == null ? -1 : authority.getPort()).pathQuery(uri),
                version, headers, contentLength);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields headers, long contentLength)
        {
            this(NanoTime.now(), method, uri, version, headers, contentLength, null);
        }

        public Request(long beginNanoTime, String method, HttpURI uri, HttpVersion version, HttpFields headers, long contentLength)
        {
            this(beginNanoTime, method, uri, version, headers, contentLength, null);
        }

        public Request(String method, HttpURI uri, HttpVersion version, HttpFields headers, long contentLength, Supplier<HttpFields> trailers)
        {
            this(NanoTime.now(), method, uri, version, headers, contentLength, trailers);
        }

        public Request(long beginNanoTime, String method, HttpURI uri, HttpVersion version, HttpFields headers, long contentLength, Supplier<HttpFields> trailers)
        {
            super(version, headers, contentLength, trailers);
            _method = Objects.requireNonNull(method);
            _uri = Objects.requireNonNull(uri);
            _beginNanoTime = beginNanoTime;
        }

        @Override
        public boolean isRequest()
        {
            return true;
        }

        public long getBeginNanoTime()
        {
            return _beginNanoTime;
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
        public HttpURI getHttpURI()
        {
            return _uri;
        }

        /**
         * @return the protocol associated with {@link #isTunnel(String, int) tunnel} requests, if any
         */
        public String getProtocol()
        {
            return null;
        }

        public boolean is100ContinueExpected()
        {
            return getHttpFields().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
        }

        @Override
        public String toString()
        {
            HttpFields headers = getHttpFields();
            return String.format("%s{u=%s,%s,h=%d,cl=%d,p=%s}",
                    getMethod(), getHttpURI(), getHttpVersion(), headers.size(), getContentLength(), getProtocol());
        }
    }

    /**
     * <p>Immutable HTTP CONNECT request information.</p>
     */
    public static class ConnectRequest extends Request
    {
        private final String _protocol;

        public ConnectRequest(HttpScheme scheme, HostPortHttpField authority, String pathQuery, HttpFields headers, String protocol)
        {
            this(scheme == null ? null : scheme.asString(), authority, pathQuery, headers, protocol);
        }

        public ConnectRequest(long beginNanoTime, HttpScheme scheme, HostPortHttpField authority, String pathQuery, HttpFields headers, String protocol)
        {
            this(beginNanoTime, scheme == null ? null : scheme.asString(), authority, pathQuery, headers, protocol);
        }

        public ConnectRequest(String scheme, HostPortHttpField authority, String pathQuery, HttpFields headers, String protocol)
        {
            this(NanoTime.now(), scheme, authority, pathQuery, headers, protocol);
        }

        public ConnectRequest(long beginNanoTime, String scheme, HostPortHttpField authority, String pathQuery, HttpFields headers, String protocol)
        {
            super(beginNanoTime,
                HttpMethod.CONNECT.asString(),
                HttpURI.build()
                    .scheme(scheme)
                    .host(authority == null ? null : authority.getHost())
                    .port(authority == null ? -1 : authority.getPort())
                    .pathQuery(pathQuery),
                HttpVersion.HTTP_2,
                headers,
                -1,
                null
            );
            _protocol = protocol;
        }

        @Override
        public String getProtocol()
        {
            return _protocol;
        }
    }

    /**
     * <p>Immutable HTTP response information.</p>
     */
    public static class Response extends MetaData
    {
        private final int _status;
        private final String _reason;

        public Response(int status, String reason, HttpVersion version, HttpFields headers)
        {
            this(status, reason, version, headers, -1);
        }

        public Response(int status, String reason, HttpVersion version, HttpFields headers, long contentLength)
        {
            this(status, reason, version, headers, contentLength, null);
        }

        public Response(int status, String reason, HttpVersion version, HttpFields headers, long contentLength, Supplier<HttpFields> trailers)
        {
            super(version, headers, contentLength, trailers);
            _status = status;
            _reason = reason;
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
            HttpFields headers = getHttpFields();
            return String.format("%s{s=%d,h=%d,cl=%d}", getHttpVersion(), getStatus(), headers.size(), getContentLength());
        }
    }

    /**
     * <p>Marker interface to signal that a {@link MetaData} could not be created,
     * for example due to an invalid URI or invalid headers.</p>
     */
    public interface Failed
    {
        /**
         * <p>Inspects the given {@link MetaData}, and if it is an instance
         * of this interface return its failure, otherwise returns {@code null}.</p>
         *
         * @param metaData the {@link MetaData} to inspect
         * @return the failure if the given {@link MetaData} is an instance of this
         * interface, otherwise {@code null}
         */
        static Throwable getFailure(MetaData metaData)
        {
            return metaData instanceof Failed f ? f.getFailure() : null;
        }

        /**
         * <p>Creates a new failed {@link MetaData.Request}, with method {@code GET},
         * empty {@link HttpURI} and empty {@link HttpFields}, with the given
         * {@link HttpVersion} and failure.</p>
         *
         * @param httpVersion the HTTP version
         * @param failure the failure cause
         * @return a new failed  {@link MetaData.Request}
         */
        static MetaData.Request newFailedMetaDataRequest(HttpVersion httpVersion, Throwable failure)
        {
            return new FailedMetaDataRequest(httpVersion, failure);
        }

        /**
         * <p>Creates a new failed {@link MetaData.Response}, with status {@code 0},
         * no reason, empty {@link HttpFields}, with the given {@link HttpVersion}
         * and failure.</p>
         *
         * @param httpVersion the HTTP version
         * @param failure the failure cause
         * @return a new failed  {@link MetaData.Response}
         */
        static MetaData.Response newFailedMetaDataResponse(HttpVersion httpVersion, Throwable failure)
        {
            return new FailedMetaDataResponse(httpVersion, failure);
        }

        /**
         * <p>Creates a new failed {@link MetaData}, with empty {@link HttpFields},
         * with the given {@link HttpVersion} and failure.</p>
         *
         * @param httpVersion the HTTP version
         * @param failure the failure cause
         * @return a new failed  {@link MetaData}
         */
        static MetaData newFailedMetaData(HttpVersion httpVersion, Throwable failure)
        {
            return new FailedMetaData(httpVersion, failure);
        }

        /**
         * @return the failure cause
         */
        Throwable getFailure();
    }

    private static class FailedMetaDataRequest extends MetaData.Request implements Failed
    {
        private final Throwable failure;

        private FailedMetaDataRequest(HttpVersion httpVersion, Throwable failure)
        {
            super("GET", HttpURI.build(), httpVersion, HttpFields.EMPTY);
            this.failure = Objects.requireNonNull(failure);
        }

        @Override
        public Throwable getFailure()
        {
            return failure;
        }
    }

    private static class FailedMetaDataResponse extends MetaData.Response implements Failed
    {
        private final Throwable failure;

        private FailedMetaDataResponse(HttpVersion httpVersion, Throwable failure)
        {
            super(0, null, httpVersion, HttpFields.EMPTY);
            this.failure = Objects.requireNonNull(failure);
        }

        @Override
        public Throwable getFailure()
        {
            return failure;
        }
    }

    private static class FailedMetaData extends MetaData implements Failed
    {
        private final Throwable failure;

        private FailedMetaData(HttpVersion httpVersion, Throwable failure)
        {
            super(httpVersion, HttpFields.EMPTY);
            this.failure = Objects.requireNonNull(failure);
        }

        @Override
        public Throwable getFailure()
        {
            return failure;
        }
    }
}

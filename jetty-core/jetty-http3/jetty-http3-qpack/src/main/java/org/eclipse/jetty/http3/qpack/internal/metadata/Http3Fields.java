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

package org.eclipse.jetty.http3.qpack.internal.metadata;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.util.StringUtil;

public class Http3Fields implements Iterable<HttpField>
{
    public static final HttpField[] STATUSES = new HttpField[599];
    private static final EnumSet<HttpHeader> IGNORED_HEADERS = EnumSet.of(HttpHeader.CONNECTION, HttpHeader.KEEP_ALIVE,
        HttpHeader.PROXY_CONNECTION, HttpHeader.TRANSFER_ENCODING, HttpHeader.UPGRADE);
    public static final PreEncodedHttpField TE_TRAILERS = new PreEncodedHttpField(HttpHeader.TE, "trailers");
    public static final PreEncodedHttpField C_SCHEME_HTTP = new PreEncodedHttpField(HttpHeader.C_SCHEME, "http");
    public static final PreEncodedHttpField C_SCHEME_HTTPS = new PreEncodedHttpField(HttpHeader.C_SCHEME, "https");
    public static final EnumMap<HttpMethod, PreEncodedHttpField> C_METHODS = new EnumMap<>(HttpMethod.class);

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

    private final List<HttpField> pseudoHeaders = new ArrayList<>(8);
    private final HttpFields httpFields;
    private Set<String> hopHeaders;
    private HttpField contentLengthHeader;

    public Http3Fields(MetaData metadata)
    {
        httpFields = metadata.getHttpFields();
        if (metadata.isRequest())
        {
            MetaData.Request request = (MetaData.Request)metadata;
            String method = request.getMethod();
            HttpMethod httpMethod = method == null ? null : HttpMethod.fromString(method);
            HttpField methodField = C_METHODS.get(httpMethod);
            pseudoHeaders.add(methodField == null ? new HttpField(HttpHeader.C_METHOD, method) : methodField);
            pseudoHeaders.add(new HttpField(HttpHeader.C_AUTHORITY, request.getHttpURI().getAuthority()));

            boolean isConnect = HttpMethod.CONNECT.is(request.getMethod());
            String protocol = request.getProtocol();
            if (!isConnect || protocol != null)
            {
                pseudoHeaders.add(HttpScheme.HTTPS.is(request.getHttpURI().getScheme()) ? C_SCHEME_HTTPS : C_SCHEME_HTTP);
                pseudoHeaders.add(new HttpField(HttpHeader.C_PATH, request.getHttpURI().getPathQuery()));

                if (protocol != null)
                    pseudoHeaders.add(new HttpField(HttpHeader.C_PROTOCOL, protocol));
            }
        }
        else if (metadata.isResponse())
        {
            MetaData.Response response = (MetaData.Response)metadata;
            int code = response.getStatus();
            HttpField status = code < STATUSES.length ? STATUSES[code] : null;
            if (status == null)
                status = new HttpField.IntValueHttpField(HttpHeader.C_STATUS, code);
            pseudoHeaders.add(status);
        }

        if (httpFields != null)
        {
            // Remove the headers specified in the Connection header,
            // for example: Connection: Close, TE, Upgrade, Custom.
            for (String value : httpFields.getCSV(HttpHeader.CONNECTION, false))
            {
                if (hopHeaders == null)
                    hopHeaders = new HashSet<>();
                hopHeaders.add(StringUtil.asciiToLowerCase(value));
            }

            // If the HttpFields doesn't have content-length we will add it at the end from the metadata.
            if (httpFields.getField(HttpHeader.CONTENT_LENGTH) == null)
            {
                long contentLength = metadata.getContentLength();
                if (contentLength >= 0)
                    contentLengthHeader = new HttpField(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength));
            }
        }
    }

    private HttpField filterIgnored(HttpField field)
    {
        HttpHeader header = field.getHeader();

        // If the header is specifically ignored skip it (Connection Specific Headers).
        if (header != null && IGNORED_HEADERS.contains(header))
            return null;

        // Remove the headers nominated by the Connection header field.
        String name = field.getLowerCaseName();
        if (hopHeaders != null && hopHeaders.contains(name))
            return null;

        // If this is the TE header field it can only have the value "trailers".
        if (header == HttpHeader.TE)
        {
            if ("trailers".equalsIgnoreCase(field.getValue()))
                return TE_TRAILERS;
            else
                return null;
        }

        return field;
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return new Iterator<>()
        {
            private final Iterator<HttpField> _pseudoHeaderIterator = pseudoHeaders.iterator();
            private final Iterator<HttpField> _httpFieldsIterator = (httpFields == null) ? null : httpFields.iterator();
            private boolean _contentLengthDone;
            private HttpField _next;

            @Override
            public boolean hasNext()
            {
                if (_next != null)
                    return true;

                if (_pseudoHeaderIterator.hasNext())
                {
                    _next = _pseudoHeaderIterator.next();
                    return true;
                }

                if (_httpFieldsIterator != null)
                {
                    while (_httpFieldsIterator.hasNext())
                    {
                        HttpField field = _httpFieldsIterator.next();
                        field = filterIgnored(field);
                        if (field != null)
                        {
                            _next = field;
                            return true;
                        }
                    }
                }

                if (!_contentLengthDone && contentLengthHeader != null)
                {
                    _contentLengthDone = true;
                    _next = contentLengthHeader;
                    return true;
                }

                return false;
            }

            @Override
            public HttpField next()
            {
                if (!hasNext())
                    throw new NoSuchElementException();
                HttpField field = _next;
                _next = null;
                return field;
            }
        };
    }
}

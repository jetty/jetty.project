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

package org.eclipse.jetty.ee9.nested;

import java.util.Set;

import jakarta.servlet.AsyncContext;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Attributes;

/**
 * An implementation of Attributes that supports the standard SSL and async attributes.
 */
public class ServletAttributes extends Attributes.Synthetic
{
    private static final Set<String> ATTRIBUTES =
        Set.of(
            Request.SSL_CIPHER_SUITE,
            Request.SSL_KEY_SIZE,
            Request.SSL_SESSION_ID,
            Request.X_509_CERTIFICATES,
            AsyncContext.ASYNC_REQUEST_URI,
            AsyncContext.ASYNC_CONTEXT_PATH,
            AsyncContext.ASYNC_SERVLET_PATH,
            AsyncContext.ASYNC_PATH_INFO,
            AsyncContext.ASYNC_QUERY_STRING,
            AsyncContext.ASYNC_MAPPING
        );

    private record Async(
        String requestURI,
        String contextPath,
        String pathInContext,
        ServletPathMapping mapping,
        String queryString)
    {
    }

    private Async _async;

    ServletAttributes(Attributes attributes)
    {
        super(attributes);
    }

    @Override
    protected Object getSyntheticAttribute(String name)
    {
        return switch (name)
        {
            case Request.SSL_CIPHER_SUITE -> getWrapped().getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData sslSessionData ? sslSessionData.cipherSuite() : null;
            case Request.SSL_KEY_SIZE -> getWrapped().getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData sslSessionData ? sslSessionData.keySize() : null;
            case Request.SSL_SESSION_ID -> getWrapped().getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData sslSessionData ? sslSessionData.sessionId() : null;
            case Request.X_509_CERTIFICATES -> getWrapped().getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData sslSessionData ? sslSessionData.peerCertificates() : null;
            case AsyncContext.ASYNC_REQUEST_URI -> _async == null ? null : _async.requestURI;
            case AsyncContext.ASYNC_CONTEXT_PATH -> _async == null ? null : _async.contextPath;
            case AsyncContext.ASYNC_SERVLET_PATH -> _async == null ? null : _async.mapping == null ? null : _async.mapping.getServletPath();
            case AsyncContext.ASYNC_PATH_INFO -> _async == null ? null : _async.mapping == null ? _async.pathInContext : _async.mapping.getPathInfo();
            case AsyncContext.ASYNC_QUERY_STRING -> _async == null ? null : _async.queryString;
            case AsyncContext.ASYNC_MAPPING -> _async == null ? null : _async.mapping;
            default -> null;
        };
    }

    @Override
    protected Set<String> getSyntheticNameSet()
    {
        return ATTRIBUTES;
    }

    public void setAsyncAttributes(String requestURI, String contextPath, String pathInContext, ServletPathMapping servletPathMapping, String queryString)
    {
        _async = new Async(requestURI, contextPath, pathInContext, servletPathMapping, queryString);
    }
}

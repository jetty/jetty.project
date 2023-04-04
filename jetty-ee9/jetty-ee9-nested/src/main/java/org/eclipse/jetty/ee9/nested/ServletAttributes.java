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

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;

/**
 * An implementation of Attributes that supports the standard async attributes.
 *
 * This implementation delegates to an internal {@link AttributesMap} instance, which
 * can optionally be wrapped with a {@link AsyncAttributes} instance. This allows async
 * attributes to be applied underneath any other attribute wrappers.
 */
public class ServletAttributes implements Attributes
{
    private final Attributes _attributes;
    private AsyncAttributes _asyncAttributes;

    ServletAttributes(Attributes attributes)
    {
        _attributes = new SSLAttributes(attributes);
    }

    public void setAsyncAttributes(String requestURI, String contextPath, String pathInContext, ServletPathMapping servletPathMapping, String queryString)
    {
        _asyncAttributes = new AsyncAttributes(_attributes, requestURI, contextPath, pathInContext, servletPathMapping, queryString);
    }

    private Attributes getAttributes()
    {
        return (_asyncAttributes == null) ? _attributes : _asyncAttributes;
    }

    @Override
    public Object removeAttribute(String name)
    {
        return getAttributes().removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return getAttributes().setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return getAttributes().getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return getAttributes().getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        getAttributes().clearAttributes();
        _asyncAttributes = null;
    }

    private static class SSLAttributes extends Attributes.Wrapper
    {
        public SSLAttributes(Attributes wrapped)
        {
            super(wrapped);
        }

        @Override
        public Object getAttribute(String name)
        {
            return switch (name)
            {
                case "jakarta.servlet.request.cipher_suite" -> super.getAttribute(SecureRequestCustomizer.CIPHER_SUITE_ATTRIBUTE);
                case "jakarta.servlet.request.key_size" -> super.getAttribute(SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE);
                case "jakarta.servlet.request.ssl_session_id" -> super.getAttribute(SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE);
                case "jakarta.servlet.request.X509Certificate" -> super.getAttribute(SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE);
                default -> super.getAttribute(name);
            };
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(super.getAttributeNameSet());
            if (names.contains(SecureRequestCustomizer.CIPHER_SUITE_ATTRIBUTE))
                names.add("jakarta.servlet.request.cipher_suite");
            if (names.contains(SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE))
                names.add("jakarta.servlet.request.key_size");
            if (names.contains(SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE))
                names.add("jakarta.servlet.request.ssl_session_id");
            if (names.contains(SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE))
                names.add("jakarta.servlet.request.X509Certificate");
            return names;
        }
    }
}

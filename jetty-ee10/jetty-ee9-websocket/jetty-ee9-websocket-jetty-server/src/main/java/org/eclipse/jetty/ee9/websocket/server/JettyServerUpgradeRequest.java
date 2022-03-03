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

package org.eclipse.jetty.ee9.websocket.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee9.websocket.api.UpgradeRequest;

public interface JettyServerUpgradeRequest extends UpgradeRequest
{
    /**
     * Access the Servlet HTTP Session (if present)
     * <p>
     * Note: Never present on a Client UpgradeRequest.
     *
     * @return the Servlet HTTPSession on server side UpgradeRequests
     */
    Object getSession();

    /**
     * @return The {@link X509Certificate} instance at request attribute "jakarta.servlet.request.X509Certificate" or null.
     */
    X509Certificate[] getCertificates();

    /**
     * @return Immutable version of {@link HttpServletRequest}
     */
    HttpServletRequest getHttpServletRequest();

    /**
     * @return The requested Locale
     * @see HttpServletRequest#getLocale()
     */
    Locale getLocale();

    /**
     * @return The requested Locales
     * @see HttpServletRequest#getLocales()
     */
    Enumeration<Locale> getLocales();

    /**
     * @return The local requested address, which is typically an {@link InetSocketAddress}, but may be another derivation of {@link SocketAddress}
     * @see ServletRequest#getLocalAddr()
     * @see ServletRequest#getLocalPort()
     */
    SocketAddress getLocalSocketAddress();

    /**
     * @return The remote request address, which is typically an {@link InetSocketAddress}, but may be another derivation of {@link SocketAddress}
     * @see ServletRequest#getRemoteAddr()
     * @see ServletRequest#getRemotePort()
     */
    SocketAddress getRemoteSocketAddress();

    /**
     * @return The request URI path within the context
     */
    String getRequestPath();

    /**
     * @param name Attribute name
     * @return Attribute value or null
     * @see ServletRequest#getAttribute(String)
     */
    Object getServletAttribute(String name);

    /**
     * @return Request attribute map
     */
    Map<String, Object> getServletAttributes();

    /**
     * @return Request parameters
     * @see ServletRequest#getParameterMap()
     */
    Map<String, List<String>> getServletParameters();

    /**
     * @param role The user role
     * @return True if the requests user has the role
     * @see HttpServletRequest#isUserInRole(String)
     */
    boolean isUserInRole(String role);

    /**
     * @param name Attribute name
     * @param value Attribute value to set
     * @see ServletRequest#setAttribute(String, Object)
     */
    void setServletAttribute(String name, Object value);
}

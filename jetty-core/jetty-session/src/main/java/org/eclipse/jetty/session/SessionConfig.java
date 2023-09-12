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

package org.eclipse.jetty.session;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

// TODO some methods need renaming
// TODO the managed attribute descriptions need review
public interface SessionConfig
{
    /**
     * Session cookie name.
     * Defaults to <code>JSESSIONID</code>, but can be set with the
     * <code>org.eclipse.jetty.session.SessionCookie</code> context init parameter.
     */
    String __SessionCookieProperty = "org.eclipse.jetty.session.SessionCookie";
    String __DefaultSessionCookie = "JSESSIONID";
    /**
     * Session id path parameter name.
     * Defaults to <code>jsessionid</code>, but can be set with the
     * <code>org.eclipse.jetty.session.SessionIdPathParameterName</code> context init parameter.
     * If context init param is "none", or setSessionIdPathParameterName is called with null or "none",
     * no URL rewriting will be done.
     */
    String __SessionIdPathParameterNameProperty = "org.eclipse.jetty.session.SessionIdPathParameterName";
    String __DefaultSessionIdPathParameterName = "jsessionid";
    String __CheckRemoteSessionEncodingProperty = "org.eclipse.jetty.session.CheckingRemoteSessionIdEncoding";
    /**
     * Session Domain.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the domain for session cookies. If it is not set, then
     * no domain is specified for the session cookie.
     */
    String __SessionDomainProperty = "org.eclipse.jetty.session.SessionDomain";
    String __DefaultSessionDomain = null;
    /**
     * Session Path.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the path for the session cookie.  If it is not set, then
     * the context path is used as the path for the cookie.
     */
    String __SessionPathProperty = "org.eclipse.jetty.servlet.SessionPath";
    /**
     * Session Max Age.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the max age for the session cookie.  If it is not set, then
     * a max age of -1 is used.
     */
    String __MaxAgeProperty = "org.eclipse.jetty.session.MaxAge";

    @ManagedAttribute("if greater the zero, the time in seconds a session cookie will last for")
    int getMaxCookieAge();

    @ManagedAttribute("default maximum time a session may be idle for (in s)")
    int getMaxInactiveInterval();

    @ManagedAttribute("time before a session cookie is re-set (in s)")
    int getRefreshCookieAge();

    @ManagedAttribute("the session cookie sameSite mode")
    HttpCookie.SameSite getSameSite();

    @ManagedAttribute("the cookie comment to use when setting session tracking cookies")
    String getSessionComment();

    @ManagedAttribute("the cookie name used to track sessions with cookies")
    String getSessionCookie();

    @ManagedAttribute("domain of the session cookie, or null for the default")
    String getSessionDomain();

    @ManagedAttribute("parameter name to use for URL session tracking")
    String getSessionIdPathParameterName();

    String getSessionIdPathParameterNamePrefix();

    @ManagedAttribute("path of the session cookie, or null for default")
    String getSessionPath();

    @ManagedAttribute("check remote session id encoding")
    boolean isCheckingRemoteSessionIdEncoding();

    @ManagedAttribute("true if cookies use the http only flag")
    boolean isHttpOnly();

    @ManagedAttribute("if true, secure cookie flag is set on session cookies")
    boolean isSecureCookies();

    @ManagedAttribute("true if ???? TODO") // TODO is this different to isSecureCookies
    boolean isSecureRequestOnly();

    @ManagedAttribute("true if sessions are tracked with cookies")
    boolean isUsingCookies();

    @ManagedAttribute("true if sessions are tracked with URLs")
    boolean isUsingURLs();

    interface Mutable extends SessionConfig
    {
        // TODO should we add getter to the interface

        void setCheckingRemoteSessionIdEncoding(boolean value);

        void setHttpOnly(boolean value);

        void setMaxCookieAge(int value);

        void setMaxInactiveInterval(int value);

        void setRefreshCookieAge(int value);

        void setSameSite(HttpCookie.SameSite sameSite);

        void setSecureCookies(boolean value);

        void setSecureRequestOnly(boolean value);

        void setSessionComment(String sessionComment);

        void setSessionCookie(String value);

        void setSessionDomain(String value);

        void setSessionIdPathParameterName(String value);

        void setSessionPath(String value);

        void setUsingCookies(boolean value);

        void setUsingURLs(boolean value);

        void setSessionCache(SessionCache cache);

        void setSessionIdManager(SessionIdManager sessionIdManager);
    }
}

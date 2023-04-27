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

import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * SessionManager
 * Non-servlet spec specific contract implemented by all SessionHandlers.
 */
public interface SessionManager extends LifeCycle, SessionConfig
{
    // TODO break this interface into multiple interfaces:
    //       - the configuration interface used to configure the manager
    //       - the contract between the request and the manager
    //       - maybe the contract with the ID managers?
    /**
     * Session cookie name.
     * Defaults to <code>JSESSIONID</code>, but can be set with the
     * <code>org.eclipse.jetty.servlet.SessionCookie</code> context init parameter.
     */
    String __SessionCookieProperty = "org.eclipse.jetty.servlet.SessionCookie";
    String __DefaultSessionCookie = "JSESSIONID";

    /**
     * Session id path parameter name.
     * Defaults to <code>jsessionid</code>, but can be set with the
     * <code>org.eclipse.jetty.servlet.SessionIdPathParameterName</code> context init parameter.
     * If context init param is "none", or setSessionIdPathParameterName is called with null or "none",
     * no URL rewriting will be done.
     */
    String __SessionIdPathParameterNameProperty = "org.eclipse.jetty.servlet.SessionIdPathParameterName";
    String __DefaultSessionIdPathParameterName = "jsessionid";
    String __CheckRemoteSessionEncoding = "org.eclipse.jetty.servlet.CheckingRemoteSessionIdEncoding";

    /**
     * Session Domain.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the domain for session cookies. If it is not set, then
     * no domain is specified for the session cookie.
     */
    String __SessionDomainProperty = "org.eclipse.jetty.servlet.SessionDomain";
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
    String __MaxAgeProperty = "org.eclipse.jetty.servlet.MaxAge";

    ManagedSession getManagedSession(String id) throws Exception;

    void newSession(Request request, String requestedSessionId, Consumer<ManagedSession> consumer);

    ManagedSession getManagedSession(Request request);

    Session.API newSessionAPIWrapper(ManagedSession session);

    void sessionTimerExpired(ManagedSession session, long now);

    void commit(ManagedSession session);

    void complete(ManagedSession session);

    void invalidate(String id) throws Exception;

    void scavenge() throws Exception;

    boolean isIdInUse(String id) throws Exception;

    HttpCookie getSessionCookie(ManagedSession session, boolean requestIsSecure);

    void renewSessionId(String oldId, String oldExtendedId, String newId, String newExtendedId) throws Exception;

    long calculateInactivityTimeout(String id, long timeRemaining, long maxInactiveMs);

    SessionInactivityTimer newSessionInactivityTimer(ManagedSession session);

    Context getContext();

    SessionIdManager getSessionIdManager();

    void setSessionIdManager(SessionIdManager idManager);

    SessionCache getSessionCache();

    void setSessionCache(SessionCache cache);

    void recordSessionTime(ManagedSession session);

    int getSessionsCreated();

    String encodeURI(Request request, String uri, boolean cookiesInUse);

    default void onSessionIdChanged(Session session, String oldId)
    {
    }

    default void onSessionCreated(Session session)
    {
    }

    default void onSessionDestroyed(Session session)
    {
    }

    default void onSessionAttributeUpdate(Session session, String name, Object oldValue, Object newValue)
    {
    }

    default void onSessionActivation(Session session)
    {
    }

    default void onSessionPassivation(Session session)
    {
    }

    double getSessionTimeStdDev();
    
    double getSessionTimeMean();
    
    long getSessionTimeTotal();
}

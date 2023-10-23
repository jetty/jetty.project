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

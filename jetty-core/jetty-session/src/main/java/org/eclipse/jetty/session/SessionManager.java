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

package org.eclipse.jetty.session;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * 
 *
 */
public interface SessionManager extends LifeCycle
{
    Session getSession(String id) throws Exception;
    
    Session newSession(Request request, String requestedSessionId);
    
    void sessionExpired(Session session, long now);
    
    void invalidate(String id) throws Exception;
    
    void scavenge() throws Exception;
    
    boolean isIdInUse(String id) throws Exception;

    boolean isUsingCookies();
    
    void renewSessionId(String oldId, String oldExtendedId, String newId, String newExtendedId) throws Exception;
    
    HttpCookie getSessionCookie(Session session, String contextPath, boolean requestIsSecure);
    
    long calculateInactivityTimeout(String id, long timeRemaining, long maxInactiveMs);
    
    SessionInactivityTimer newSessionInactivityTimer(Session session);
    
    SessionIdManager getSessionIdManager();
    
    Context getContext();
    
    SessionCache getSessionCache();
    
    void setMaxInactiveInterval(int msec);
    
    int getMaxInactiveInterval();
    
    void callSessionIdListeners(Session session, String oldId);
   
    void callSessionCreatedListeners(Session session);
    
    void callSessionDestroyedListeners(Session session);
    
    void callSessionAttributeListeners(Session session, String name, Object old, Object value);
    
    void callUnboundBindingListener(Session session, String name, Object value);
    
    void callBoundBindingListener(Session session, String name, Object value);
    
    void callSessionActivationListener(Session session, String name, Object value);
    
    void callSessionPassivationListener(Session session, String name, Object value);
    
    void recordSessionTime(Session session);
    
    int getSessionsCreated();
    
    double getSessionTimeStdDev();
    
    double getSessionTimeMean();
    
    long getSessionTimeTotal();
}

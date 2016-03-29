//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.server.session;

import java.util.Set;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * SessionStore
 *
 * A store of Session objects.  This store of Session objects can be backed by
 * a SessionDataStore to persist/distribute the data contained in the Session objects.
 * 
 * This store of Session objects ensures that all threads within the same context on
 * the same node with the same session id will share exactly the same Session object.
 */
public interface SessionStore extends LifeCycle
{
    void initialize(SessionContext context);
    SessionManager getSessionManager();
    Session newSession (HttpServletRequest request, String id,  long time, long maxInactiveMs);
    Session newSession (SessionData data);
    Session renewSessionId (String oldId, String newId) throws Exception;
    Session get(String id) throws Exception;
    void put(String id, Session session) throws Exception;
    boolean exists (String id) throws Exception;
    Session delete (String id) throws Exception;
    void shutdown ();
    Set<String> checkExpiration (Set<String> candidates);
    void setIdlePassivationTimeoutSec(int sec);
    int getIdlePassivationTimeoutSec();
    SessionDataStore getSessionDataStore();
    void passivateIdleSession(String id);
}

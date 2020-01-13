//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.util.Set;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.session.HouseKeeper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * Session ID Manager.
 *
 * Manages session IDs across multiple contexts.
 */
public interface SessionIdManager extends LifeCycle
{

    /**
     * @param id The plain session ID (ie no workername extension)
     * @return True if the session ID is in use by at least one context.
     */
    boolean isIdInUse(String id);

    /**
     * Expire all sessions on all contexts that share the same id.
     *
     * @param id The session ID without any cluster node extension
     */
    void expireAll(String id);

    /**
     * Invalidate all sessions on all contexts that share the same id.
     *
     * @param id the session id
     */
    void invalidateAll(String id);

    /**
     * Create a new Session ID.
     *
     * @param request the request with the sesion
     * @param created the timestamp for when the session was created
     * @return the new session id
     */
    String newSessionId(HttpServletRequest request, long created);

    /**
     * @return the unique name of this server instance
     */
    String getWorkerName();

    /**
     * Get just the session id from an id that includes the worker name
     * as a suffix.
     *
     * Strip node identifier from a located session ID.
     *
     * @param qualifiedId the session id including the worker name
     * @return the cluster id
     */
    String getId(String qualifiedId);

    /**
     * Get an extended id for a session. An extended id contains
     * the workername as a suffix.
     *
     * @param id The id of the session
     * @param request The request that for the session (or null)
     * @return The session id qualified with the worker name
     */
    String getExtendedId(String id, HttpServletRequest request);

    /**
     * Change the existing session id.
     *
     * @param oldId the old plain session id
     * @param oldExtendedId the old fully qualified id
     * @param request the request containing the session
     * @return the new session id
     */
    String renewSessionId(String oldId, String oldExtendedId, HttpServletRequest request);

    /**
     * Get the set of all session handlers for this node
     *
     * @return the set of session handlers
     */
    Set<SessionHandler> getSessionHandlers();

    /**
     * @param houseKeeper the housekeeper for doing scavenging
     */
    void setSessionHouseKeeper(HouseKeeper houseKeeper);

    /**
     * @return the housekeeper for doing scavenging
     */
    HouseKeeper getSessionHouseKeeper();
}

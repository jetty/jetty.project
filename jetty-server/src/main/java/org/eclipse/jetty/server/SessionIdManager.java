//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.util.component.LifeCycle;

/** Session ID Manager.
 * Manages session IDs across multiple contexts.
 */
public interface SessionIdManager extends LifeCycle
{
    /**
     * @param id The session ID without any cluster node extension
     * @return True if the session ID is in use by at least one context.
     */
    public boolean idInUse(String id);
    
    /**
     * Add a session to the list of known sessions for a given ID.
     * @param session The session
     */
    public void addSession(HttpSession session);
    
    /**
     * Remove session from the list of known sessions for a given ID.
     * @param session
     */
    public void removeSession(HttpSession session);
    
    /**
     * Call {@link HttpSession#invalidate()} on all known sessions for the given id.
     * @param id The session ID without any cluster node extension
     */
    public void invalidateAll(String id);
    
    /**
     * @param request
     * @param created
     * @return the new session id
     */
    public String newSessionId(HttpServletRequest request,long created);
    
    public String getWorkerName();
    
    
    /* ------------------------------------------------------------ */
    /** Get a cluster ID from a node ID.
     * Strip node identifier from a located session ID.
     * @param nodeId
     * @return the cluster id
     */
    public String getClusterId(String nodeId);
    
    /* ------------------------------------------------------------ */
    /** Get a node ID from a cluster ID and a request
     * @param clusterId The ID of the session
     * @param request The request that for the session (or null)
     * @return The session ID qualified with the node ID.
     */
    public String getNodeId(String clusterId,HttpServletRequest request);
    
}

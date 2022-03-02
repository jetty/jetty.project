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

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * SessionDataMap
 *
 * A map style access to SessionData keyed by the session id.
 */
public interface SessionDataMap extends LifeCycle
{
    /**
     * Initialize this data map for the
     * given context. A SessionDataMap can only
     * be used by one context(/session manager).
     *
     * @param context context associated
     * @throws Exception if unable to initialize the
     */
    void initialize(SessionContext context) throws Exception;

    /**
     * Read in session data.
     *
     * @param id identity of session to load
     * @return the SessionData matching the id
     * @throws Exception if unable to load session data
     */
    public SessionData load(String id) throws Exception;

    /**
     * Store the session data.
     *
     * @param id identity of session to store
     * @param data info of session to store
     * @throws Exception if unable to write session data
     */
    public void store(String id, SessionData data) throws Exception;

    /**
     * Delete session data
     *
     * @param id identity of session to delete
     * @return true if the session was deleted
     * @throws Exception if unable to delete session data
     */
    public boolean delete(String id) throws Exception;
}

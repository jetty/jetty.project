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

package org.eclipse.jetty.server;

import org.eclipse.jetty.util.Attributes;

/**
 * <p>The interface to a generic session associated with a request.
 * This interface may be used directly, or it may be wrapped by an API specific
 * representation of a session. Such wrappers must implement the {@link API}
 * sub interface.</p>
 * <p>Depending on the implementation the attribute values may be stored locally in memory or in a remote
 * database. The instances of the attribute values may be different to those passed to {@link #setAttribute(String, Object)}
 * or to those returned in previous {@link #getAttribute(String)} calls.</p>
 */
public interface Session extends Attributes
{
    /**
     * <p>Get the session associated with an API session wrapper.</p>
     *
     * @param session The API wrapper of the session
     * @return The associated Session.
     */
    static Session getSession(Object session)
    {
        if (session instanceof API wrapper)
            return wrapper.getSession();
        return null;
    }

    /**
     * <p>An API wrapper of the core session.</p>
     * <p>API wrappers of the {@link Session} must implement this interface and return
     * the {@link Session} that they wrap.</p>
     */
    interface API
    {
        Session getSession();
    }

    /**
     * @param <T> The type of the API wrapper.
     * @return An API wrapper of this session or null if there is none.
     */
    @SuppressWarnings("unchecked")
    <T extends API> T getApi();

    /**
     * @return true if the session is has not been invalidated nor expired due to inactivity.
     */
    boolean isValid();

    /**
     * @return A identifier for the session. Depending on the implementation may be unique within the context, the server, the
     * JVM or a cluster.
     * @see #getExtendedId()
     */
    String getId();

    /**
     * @return The session identifier as returned by {@link #getId()} extended with additional routing information. The
     * additional information does not form part of the identity, but may be used by implementations and associated
     * infrastructure to help route request for the same session to the same server.
     */
    String getExtendedId();

    /**
     * @return the time, as represented by {@link System#currentTimeMillis()}, that the session was last accessed by a request.
     */
    long getLastAccessedTime();

    /**
     * @param secs The period in secs in which the session will remain valid (unless explicitly invalidated)
     * when not accessed by any requests.
     */
    void setMaxInactiveInterval(int secs);

    /**
     * @return The period in secs in which the session will remain valid (unless explicitly invalidated)
     * when not accessed by any requests.
     */
    int getMaxInactiveInterval();

    /**
     * Renew the identity of the session. Typically, this is done after a change of authentication or confidentiality.
     *
     * @param request The request from which the session was obtained.
     * @param response The response which may be updated with the new session identity.
     */
    void renewId(Request request, Response response);

    /**
     * <p>Invalidate this session in the current context and all sessions of the same identity with associated contexts.</p>
     */
    void invalidate();

    /**
     * @return {@code true} if the session has been newly created within the scope of the current request.
     */
    boolean isNew() throws IllegalStateException;

    String encodeURI(Request request, String uri, boolean cookiesInUse);

    /**
     * Listener interface that if implemented by a value of an attribute of an enclosing {@link Context} at start, will be
     * notified of session lifecycle events.
     */
    interface LifeCycleListener
    {
        default void onSessionIdChanged(Session session, String oldId)
        {
        }

        default void onSessionCreated(Session session)
        {
        }

        default void onSessionDestroyed(Session session)
        {
        }
    }

    /**
     * Listener interface that if implemented by a session attribute value, will be notified of
     * session value events.
     */
    interface ValueListener
    {
        default void onSessionAttributeUpdate(Session session, String name, Object oldValue, Object newValue)
        {
        }

        default void onSessionActivation(Session session)
        {
        }

        default void onSessionPassivation(Session session)
        {
        }
    }
}

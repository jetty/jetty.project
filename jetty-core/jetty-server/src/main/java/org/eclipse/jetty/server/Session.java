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

package org.eclipse.jetty.server;

import java.util.Set;

/**
 * <p>The interface to a generic session associated with a request.
 * This interface may be used directly, or it may be wrapped by an API specific
 * representation of a session. Such wrappers must implement the {@link API}
 * sub interface.</p>
 */
public interface Session
{
    static Session getSession(Object session)
    {
        if (session instanceof API wrapper)
            return wrapper.getSession();
        return null;
    }

    /**
     * <p>API wrappers of the {@link Session} must implement this interface and return
     * the {@link Session} that they wrap.</p>
     */
    interface API
    {
        Session getSession();
    }

    @SuppressWarnings("unchecked")
    <T extends API> T getAPISession();

    boolean isValid();

    String getId();

    String getExtendedId();

    String getContextPath();

    long getLastAccessedTime();

    void setMaxInactiveInterval(int secs);

    int getMaxInactiveInterval();

    Object getAttribute(String name);

    int getAttributes();

    Set<String> getNames();

    void setAttribute(String name, Object value);

    void removeAttribute(String name);

    void renewId(Request request, Response response);

    void invalidate();

    boolean isNew() throws IllegalStateException;
}

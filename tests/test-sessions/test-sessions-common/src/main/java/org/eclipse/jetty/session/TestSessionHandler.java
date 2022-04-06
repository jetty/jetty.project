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

import java.util.ArrayList;

import org.eclipse.jetty.session.Session.APISession;

/**
 * TestSessionHandler
 *
 * For testing convenience.
 */
public class TestSessionHandler extends AbstractSessionManager
{
    java.util.Collection<String> _sessionIdListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionCreatedListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionDestroyedListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionAttributeListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionUnboundListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionBoundListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionActivationListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionPassivationListenersCalled = new ArrayList<>();

    @Override
    public APISession newSessionAPIWrapper(Session session)
    {
        return null;
    }

    @Override
    public void callSessionIdListeners(Session session, String oldId)
    {
        _sessionIdListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionCreatedListeners(Session session)
    {
        _sessionCreatedListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionDestroyedListeners(Session session)
    {
        _sessionDestroyedListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionAttributeListeners(Session session, String name, Object old, Object value)
    {
        _sessionAttributeListenersCalled.add(session.getId());
    }

    @Override
    public void callUnboundBindingListener(Session session, String name, Object value)
    {
        _sessionUnboundListenersCalled.add(session.getId());
    }

    @Override
    public void callBoundBindingListener(Session session, String name, Object value)
    {
        _sessionBoundListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionActivationListener(Session session, String name, Object value)
    {
        _sessionActivationListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionPassivationListener(Session session, String name, Object value)
    {
        _sessionPassivationListenersCalled.add(session.getId());
    }

    @Override
    protected void configureCookies()
    {
    }
}

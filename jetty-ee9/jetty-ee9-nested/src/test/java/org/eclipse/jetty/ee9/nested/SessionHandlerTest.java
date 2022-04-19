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

package org.eclipse.jetty.ee9.nested;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SessionHandlerTest
{
    @Test
    public void testSessionTrackingMode()
    {
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionTrackingModes(new HashSet<>(Arrays.asList(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)));
        sessionHandler.setSessionTrackingModes(Collections.singleton(SessionTrackingMode.SSL));
        assertThrows(IllegalArgumentException.class, () -> sessionHandler.setSessionTrackingModes(new HashSet<>(Arrays.asList(SessionTrackingMode.SSL, SessionTrackingMode.URL))));
    }

    @Test
    public void testSessionListenerOrdering()
        throws Exception
    {
        final StringBuffer result = new StringBuffer();

        class Listener1 implements HttpSessionListener
        {

            @Override
            public void sessionCreated(HttpSessionEvent se)
            {
                result.append("Listener1 create;");
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                result.append("Listener1 destroy;");
            }
        }

        class Listener2 implements HttpSessionListener
        {

            @Override
            public void sessionCreated(HttpSessionEvent se)
            {
                result.append("Listener2 create;");
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                result.append("Listener2 destroy;");
            }

        }

        Server server = new Server();
        SessionHandler sessionHandler = new SessionHandler();
        try
        {
            sessionHandler.addEventListener(new Listener1());
            sessionHandler.addEventListener(new Listener2());
            sessionHandler.setServer(server);
            sessionHandler.start();

            Session session = new Session(sessionHandler.getSessionManager(), new SessionData("aa", "_", "0.0", 0, 0, 0, 0));
            sessionHandler.getSessionManager().callSessionCreatedListeners(session);
            sessionHandler.getSessionManager().callSessionDestroyedListeners(session);
            assertEquals("Listener1 create;Listener2 create;Listener2 destroy;Listener1 destroy;", result.toString());
        }
        finally
        {
            sessionHandler.stop();
        }
    }
}

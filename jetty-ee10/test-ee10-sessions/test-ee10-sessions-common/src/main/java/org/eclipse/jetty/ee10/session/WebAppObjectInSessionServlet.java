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

package org.eclipse.jetty.ee10.session;

import java.io.IOException;
import java.io.Serializable;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

/**
 * WebAppObjectInSessionServlet
 */
public class WebAppObjectInSessionServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
    {
        try
        {
            String action = request.getParameter("action");
            if ("set".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("staticAttribute", new TestSharedStatic());

                Object staticAttribute = session.getAttribute("staticAttribute");
                assertThat(staticAttribute, instanceOf(TestSharedStatic.class));

//                session.setAttribute("objectAttribute", new TestSharedNonStatic());

                // The session itself is not shareable, since the implementation class
                // refers to the session manager via the hidden field this$0, and
                // it seems there is no way to mark the hidden field as transient.
//                session.setAttribute("sessionAttribute", session);
            }
            else if ("get".equals(action))
            {
                HttpSession session = request.getSession(false);
                Object staticAttribute = session.getAttribute("staticAttribute");
                assertThat(staticAttribute, instanceOf(TestSharedStatic.class));

//                Object objectAttribute = session.getAttribute("objectAttribute");
//                assertTrue(objectAttribute instanceof TestSharedNonStatic);

//                Object sessionAttribute = session.getAttribute("sessionAttribute");
//                assertTrue(sessionAttribute instanceof HttpSession);
            }
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            httpServletResponse.sendError(500, e.toString());
            throw new ServletException(e);
        }
    }

    // Non static inner classes are not shareable, because even if this class is portable,
    // the hidden field this$0 refers to the servlet, which is a non portable class.
    public class TestSharedNonStatic implements Serializable
    {
    }

    // Static inner classes are shareable
    public static class TestSharedStatic implements Serializable
    {
    }
}

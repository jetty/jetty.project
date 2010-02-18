// ========================================================================
// Copyright 2004-2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;


/**
 * WebAppObjectInSessionServlet
 *
 *
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
                assert staticAttribute instanceof TestSharedStatic;
//                Object objectAttribute = session.getAttribute("objectAttribute");
//                assert objectAttribute instanceof TestSharedNonStatic;
//                Object sessionAttribute = session.getAttribute("sessionAttribute");
//                assert sessionAttribute instanceof HttpSession;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
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

//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.lang.reflect.Proxy;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;


    
public class TestFooServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String action = request.getParameter("action");
        
        if ("create".equals(action))
        {
            HttpSession session = request.getSession(true);
            TestFoo testFoo = new TestFoo();
            testFoo.setInt(33);
            FooInvocationHandler handler = new FooInvocationHandler(testFoo);
            Foo foo = (Foo)Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] {Foo.class}, handler);
            session.setAttribute("foo", foo);
        }
        else if ("test".equals(action))
        {
            HttpSession session = request.getSession(false);
            if (session == null)
                response.sendError(500, "Session not activated");
            Foo foo = (Foo)session.getAttribute("foo");
            if (foo == null || foo.getInt() != 33)
                response.sendError(500, "Foo not deserialized");
        }
        
    }
}

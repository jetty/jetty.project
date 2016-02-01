//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.cdi.core.NamedLiteral;

@SuppressWarnings("serial")
@WebServlet("/req-info")
public class RequestInfoServlet extends HttpServlet
{
    @Inject
    @Any
    private Instance<Dumper> dumpers;
    
    @Inject
    @Named("params")
    private Dumper defaultDumper;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.setContentType("text/plain");
        PrintWriter out = resp.getWriter();
        
        Dumper dumper = defaultDumper;
        
        String dumperId = req.getParameter("dumperId");
        
        if (dumperId != null)
        {
            Instance<Dumper> inst = dumpers.select(new NamedLiteral(dumperId));
            if (!inst.isAmbiguous() && !inst.isUnsatisfied())
            {
                dumper = inst.get();
            }
        }
        
        dumper.dump(out);
    }
}

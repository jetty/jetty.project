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

package org.example;

import java.io.IOException;
import javax.naming.InitialContext;
import javax.sql.DataSource;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.UserTransaction;

/**
 * JNDITest
 *
 * Use JNDI from within Jetty.
 *
 * Also, use servlet spec 2.5 resource injection and lifecycle callbacks from within the web.xml
 * to set up some of the JNDI resources.
 *
 */
public class JNDITest extends HttpServlet
{
    private DataSource myDS;
    private Double wiggle;
    private Integer woggle;
    private Double gargle;
    private String svr;

    private String resourceNameMappingInjectionResult;
    private String envEntryOverrideResult;
    private String postConstructResult = "PostConstruct method called: <span class=\"fail\">FALSE</span>";
    private String preDestroyResult = "PreDestroy method called: <span class=\"pass\">NOT YET</span>";
    private String envEntryGlobalScopeResult;
    private String envEntryWebAppScopeResult;
    private String userTransactionResult;
    private String svrResult;

    public void setMyDatasource(DataSource ds)
    {
        myDS = ds;
    }

    private void postConstruct()
    {
        resourceNameMappingInjectionResult = "Injection of resource to locally mapped name (java:comp/env/mydatasource as java:comp/env/mydatasource1): " + (myDS != null ? "<span class=\"pass\">PASS</span>" : "<span class=\"fail\">FAIL</span>");
        envEntryOverrideResult = "Override of EnvEntry in jetty-env.xml (java:comp/env/wiggle): " + (wiggle == 55.0 ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL(expected 55.0, got " + wiggle + ")") + "</span>";
        postConstructResult = "PostConstruct method called: <span class=\"pass\">PASS</span>";
    }

    private void preDestroy()
    {
        preDestroyResult = "PreDestroy method called: <span class=\"pass\">PASS</span>";
    }

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        try
        {
            InitialContext ic = new InitialContext();
            woggle = (Integer)ic.lookup("java:comp/env/woggle");
            envEntryGlobalScopeResult = "EnvEntry defined in context xml lookup result (java:comp/env/woggle): " + (woggle == 4000 ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL(expected 4000, got " + woggle + ")") + "</span>";
            gargle = (Double)ic.lookup("java:comp/env/gargle");
            svr = (String)ic.lookup("java:comp/env/svr");
            svrResult = "Ref to Server in jetty-env.xml result: " + (svr != null ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span>";

            envEntryWebAppScopeResult = "EnvEntry defined in jetty-env.xml lookup result (java:comp/env/gargle): " + (gargle == 100.0 ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL(expected 100, got " + gargle + ")") + "</span>";
            UserTransaction utx = (UserTransaction)ic.lookup("java:comp/UserTransaction");
            userTransactionResult = "UserTransaction lookup result (java:comp/UserTransaction): " + (utx != null ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span>";
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<head><link rel=\"stylesheet\" type=\"text/css\" href=\"stylesheet.css\"/></head>");
            out.println("<h1>Jetty JNDI Tests</h1>");
            out.println("<body>");

            out.println("<h2>Injection and JNDI Lookup Results</h2>");
            out.println("<p>" + resourceNameMappingInjectionResult + "</p>");
            out.println("<p>" + envEntryOverrideResult + "</p>");
            out.println("<p>" + postConstructResult + "</p>");
            out.println("<p>" + preDestroyResult + "</p>");
            out.println("<p>" + envEntryGlobalScopeResult + "</p>");
            out.println("<p>" + envEntryWebAppScopeResult + "</p>");
            out.println("<p>" + svrResult + "</p>");
            out.println("<p>" + userTransactionResult + "</p>");

            out.println("</body>");
            out.println("</html>");
            out.flush();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public void destroy()
    {
    }
}

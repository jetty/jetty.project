//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package com.acme;

import java.io.IOException;
import javax.mail.Session;
import javax.naming.InitialContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import javax.transaction.UserTransaction;

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

    private Session myMailSession;
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
    private String mailSessionResult;
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
            myMailSession = (Session)ic.lookup("java:comp/env/mail/Session");
            mailSessionResult = "Mail Session lookup result (java:comp/env/mail/Session): " + (myMailSession != null ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span>";
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
        String mailTo = request.getParameter("mailto");
        String mailFrom = request.getParameter("mailfrom");

        if (mailTo != null)
            mailTo = mailTo.trim();

        if (mailFrom != null)
            mailFrom = mailFrom.trim();

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
            out.println("<p>" + mailSessionResult + "</p>");

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

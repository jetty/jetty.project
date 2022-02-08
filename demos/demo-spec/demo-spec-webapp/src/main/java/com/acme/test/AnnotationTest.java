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

package com.acme.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RunAs;
import javax.naming.InitialContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import javax.transaction.UserTransaction;

/**
 * AnnotationTest
 *
 * Use Annotations from within Jetty.
 *
 * Also, use servlet spec 2.5 resource injection and lifecycle callbacks from within the web.xml
 * to set up some of the JNDI resources.
 */

@RunAs("special")
@WebServlet(urlPatterns = {"/", "/test/*"}, name = "AnnotationTest", initParams = {
    @WebInitParam(name = "fromAnnotation", value = "xyz")
})
@DeclareRoles({"user", "client"})
public class AnnotationTest extends HttpServlet
{
    static List<String> __HandlesTypes;
    private String postConstructResult = "";
    private String dsResult = "";
    private String envResult = "";
    private String envLookupResult = "";
    private String envResult2 = "";
    private String envLookupResult2 = "";
    private String envResult3 = "";
    private String envLookupResult3 = "";
    private String dsLookupResult = "";
    private String txResult = "";
    private String txLookupResult = "";
    private DataSource myDS;
    private ServletConfig config;

    @Resource(mappedName = "UserTransaction")
    private UserTransaction myUserTransaction;

    @Resource(mappedName = "maxAmount")
    private Double maxAmount;

    @Resource(name = "someAmount")
    private Double minAmount;

    @Resource
    private Double avgAmount;

    @Resource(mappedName = "jdbc/mydatasource")
    public void setMyDatasource(DataSource ds)
    {
        myDS = ds;
    }

    @PostConstruct
    private void myPostConstructMethod()
    {
        postConstructResult = "<span class=\"pass\">PASS</span>";
        try
        {
            dsResult = (myDS == null ? "<span class=\"fail\">FAIL</span>" : "<span class=\"pass\">myDS=" + myDS.toString() + "</span>");
        }
        catch (Exception e)
        {
            dsResult = "<span class=\"fail\">FAIL:</span> " + e;
        }

        envResult = (maxAmount == null ? "FAIL</span>" : "<span class=\"pass\">maxAmount=" + maxAmount.toString() + "</span>");

        try
        {
            InitialContext ic = new InitialContext();
            envLookupResult = "java:comp/env/com.acme.test.AnnotationTest/maxAmount=" + ic.lookup("java:comp/env/com.acme.test.AnnotationTest/maxAmount");
        }
        catch (Exception e)
        {
            envLookupResult = "<span class=\"fail\">FAIL:</span> " + e;
        }

        envResult2 = (minAmount == null ? "<span class=\"fail\">FAIL</span>" : "<span class=\"pass\">minAmount=" + minAmount.toString() + "</span>");
        try
        {
            InitialContext ic = new InitialContext();
            envLookupResult2 = "java:comp/env/someAmount=" + ic.lookup("java:comp/env/someAmount");
        }
        catch (Exception e)
        {
            envLookupResult2 = "<span class=\"fail\">FAIL:</span> " + e;
        }
        envResult3 = (minAmount == null ? "<span class=\"fail\">FAIL</span>" : "<span class=\"pass\">avgAmount=" + avgAmount.toString() + "</span>");
        try
        {
            InitialContext ic = new InitialContext();
            envLookupResult3 = "java:comp/env/com.acme.test.AnnotationTest/avgAmount=" + ic.lookup("java:comp/env/com.acme.test.AnnotationTest/avgAmount");
        }
        catch (Exception e)
        {
            envLookupResult3 = "<span class=\"fail\">FAIL:</span> " + e;
        }

        try
        {
            InitialContext ic = new InitialContext();
            dsLookupResult = "java:comp/env/com.acme.test.AnnotationTest/myDatasource=" + ic.lookup("java:comp/env/com.acme.test.AnnotationTest/myDatasource");
        }
        catch (Exception e)
        {
            dsLookupResult = "<span class=\"fail\">FAIL:</span> " + e;
        }

        txResult = (myUserTransaction == null ? "<span class=\"fail\">FAIL</span>" : "<span class=\"pass\">myUserTransaction=" + myUserTransaction + "</span>");
        try
        {
            InitialContext ic = new InitialContext();
            txLookupResult = "java:comp/env/com.acme.test.AnnotationTest/myUserTransaction=" + ic.lookup("java:comp/env/com.acme.test.AnnotationTest/myUserTransaction");
        }
        catch (Exception e)
        {
            txLookupResult = "<span class=\"fail\">FAIL:</span> " + e;
        }
    }

    @PreDestroy
    private void myPreDestroyMethod()
    {
    }

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        this.config = config;
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
            out.println("<head><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></head>");
            out.println("<body>");
            out.println("<h1>Results</h1>");

            out.println("<h2>Context Defaults</h2>");
            out.println("<p><b>default-context-path: " +
                (request.getServletContext().getAttribute("default-context-path") != null ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") +
                "</span></p>");
            out.println("<p><b>request-character-encoding: " +
                ("utf-8".equals(request.getServletContext().getAttribute("request-character-encoding")) ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") +
                "</span></p>");
            out.println("<p><b>response-character-encoding: " +
                ("utf-8".equals(request.getServletContext().getAttribute("response-character-encoding")) ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") +
                "</span></p>");

            out.println("<h2>Init Params from Annotation</h2>");
            out.println("<pre>");
            out.println("initParams={@WebInitParam(name=\"fromAnnotation\", value=\"xyz\")}");
            out.println("</pre>");
            out.println("<p><b>Result: " + ("xyz".equals(config.getInitParameter("fromAnnotation")) ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></p>");

            out.println("<h2>Init Params from web-fragment</h2>");
            out.println("<pre>");
            out.println("extra1=123, extra2=345");
            out.println("</pre>");
            boolean fragInitParamResult = "123".equals(config.getInitParameter("extra1")) && "345".equals(config.getInitParameter("extra2"));
            out.println("<p><b>Result: " + (fragInitParamResult ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></p>");

            __HandlesTypes = Arrays.asList("javax.servlet.GenericServlet",
                "javax.servlet.http.HttpServlet",
                "com.acme.test.AsyncListenerServlet",
                "com.acme.test.ClassLoaderServlet",
                "com.acme.test.AnnotationTest",
                "com.acme.test.RoleAnnotationTest",
                "com.acme.test.MultiPartTest",
                "com.acme.fragment.FragmentServlet",
                "com.acme.test.TestListener",
                "com.acme.test.SecuredServlet",
                "com.acme.test.Bar");
            out.println("<h2>@ContainerInitializer</h2>");
            out.println("<pre>");
            out.println("@HandlesTypes({javax.servlet.Servlet.class, Foo.class})");
            out.println("</pre>");
            out.print("<p><b>Result: ");
            List<Class> classes = (List<Class>)config.getServletContext().getAttribute("com.acme.Foo");
            List<String> classNames = new ArrayList<String>();
            if (classes != null)
            {
                for (Class c : classes)
                {
                    classNames.add(c.getName());
                    out.print(c.getName() + " ");
                }

                if (classNames.size() != __HandlesTypes.size())
                    out.println("<br/><span class=\"fail\">FAIL</span>");
                else if (!classNames.containsAll(__HandlesTypes))
                    out.println("<br/><span class=\"fail\">FAIL</span>");
                else
                    out.println("<br/><span class=\"pass\">PASS</span>");
            }
            else
                out.print("<br/><span class=\"fail\">FAIL</span> (No such attribute com.acme.Foo)");
            out.println("</b></p>");

            out.println("<h2>Complete Servlet Registration</h2>");
            Boolean complete = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.complete");
            out.println("<p><b>Result: " + (complete.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");

            out.println("<h2>ServletContextListener Programmatic Registration from ServletContainerInitializer</h2>");
            Boolean programmaticListener = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.listenerTest");
            out.println("<p><b>Result: " + (programmaticListener.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");

            out.println("<h2>ServletContextListener Programmatic Registration Prevented from ServletContextListener</h2>");
            Boolean programmaticListenerPrevention = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.listenerRegoTest");
            out.println("<p><b>Result: " + (programmaticListenerPrevention.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");

            out.println("<h2>ServletContextListener Registration Prevented from ServletContextListener</h2>");
            Boolean webListenerPrevention = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.sclFromSclRegoTest");
            out.println("<p><b>Result: " + (webListenerPrevention.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");
            
            out.println("<h2>Add Jsp File Registration</h2>");
            complete = (Boolean)config.getServletContext().getAttribute("com.acme.jsp.file");
            out.println("<p><b>Result: " + (complete.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");
            
            out.println("<h2>ServletContextListener In web.xml Injected</h2>");
            Boolean listenerInject = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.sclInjectTest");
            out.println("<p><b>Result: " + (listenerInject.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");

            out.println("<h2>ServletContextListener as @WebListener Injected</h2>");
            Boolean annotatedListenerInject = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.sclInjectWebListenerTest");
            out.println("<p><b>Result: " + (annotatedListenerInject.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");

            out.println("<h2>ServletContextListener as @WebListener Get/Set Session Timeout</h2>");
            out.println("<p><b>getSessionTimeout Result: " + 
                ((Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.sclGetSessionTimeout") ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");
            out.println("<p><b>setSessionTimeout Result: " + 
                ((Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.sclSetSessionTimeout") ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");
            
            out.println("<h2>Programmatic Listener Injected</h2>");
            Boolean programListenerInject = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.programListenerInjectTest");
            out.println("<p><b>Result: " + (programListenerInject.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");

            out.println("<h2>Invalid Type for Listener Detection</h2>");
            Boolean badListener = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.invalidListenerRegoTest");
            out.println("<p><b>Result: " + (badListener.booleanValue() ? "<span class=\"pass\">PASS" : "<span class=\"fail\">FAIL") + "</span></b></p>");

            out.println("<h2>@PostConstruct Callback</h2>");
            out.println("<pre>");
            out.println("@PostConstruct");
            out.println("private void myPostConstructMethod ()");
            out.println("{}");
            out.println("</pre>");
            out.println("<p><b>Result: " + postConstructResult + "</b></p>");

            out.println("<h2>@Resource Injection for DataSource</h2>");
            out.println("<pre>");
            out.println("@Resource(mappedName=\"jdbc/mydatasource\");");
            out.println("public void setMyDatasource(DataSource ds)");
            out.println("{");
            out.println("myDS=ds;");
            out.println("}");
            out.println("</pre>");
            out.println("<p><b>Result: " + dsResult + "</b>");
            out.println("<br/><b>JNDI Lookup Result: " + dsLookupResult + "</b></p>");

            out.println("<h2>@Resource Injection for env-entry </h2>");
            out.println("<pre>");
            out.println("@Resource(mappedName=\"maxAmount\")");
            out.println("private Double maxAmount;");
            out.println("@Resource(name=\"minAmount\")");
            out.println("private Double minAmount;");
            out.println("</pre>");
            if (maxAmount == null)
                out.println("<p><b>Result: " + envResult + ":  <span class=\"fail\">FAIL</span>");
            else
                out.println("<p><b>Result: " + envResult + ": " + (maxAmount.compareTo(55D) == 0 ? " <span class=\"pass\">PASS" : " <span class=\"fail\">FAIL") + "</span></b>");
            out.println("<br/><b>JNDI Lookup Result: " + envLookupResult + "</b>");

            if (minAmount == null)
                out.println("<p><b>Result: " + envResult2 + ":  <span class=\"fail\">FAIL</span>");
            else
                out.println("<br/><b>Result: " + envResult2 + ": " + (minAmount.compareTo(0.99D) == 0 ? " <span class=\"pass\">PASS" : " <span class=\"fail\">FAIL") + "</span></b>");
            out.println("<br/><b>JNDI Lookup Result: " + envLookupResult2 + "</b>");

            if (avgAmount == null)
                out.println("<p><b>Result: " + envResult3 + ":  <span class=\"fail\">FAIL</span>");
            else
                out.println("<br/><b>Result: " + envResult3 + ": " + (avgAmount.compareTo(1.25D) == 0 ? " <span class=\"pass\">PASS" : " <span class=\"fail\">FAIL") + "</span></b>");
            out.println("<br/><b>JNDI Lookup Result: " + envLookupResult3 + "</b></p>");

            out.println("<h2>@Resource Injection for UserTransaction </h2>");
            out.println("<pre>");
            out.println("@Resource(mappedName=\"UserTransaction\")");
            out.println("private UserTransaction myUserTransaction;");
            out.println("</pre>");
            out.println("<p><b>Result: " + txResult + "</b>");
            out.println("<br/><b>JNDI Lookup Result: " + txLookupResult + "</b></p>");

            out.println("</body>");
            out.println("</html>");
            out.flush();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }
}

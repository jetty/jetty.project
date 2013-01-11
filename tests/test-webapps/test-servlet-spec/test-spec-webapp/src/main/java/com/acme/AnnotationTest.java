//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
 *
 */

@RunAs("special")
@WebServlet(urlPatterns = {"/","/test/*"}, name="AnnotationTest", initParams={@WebInitParam(name="fromAnnotation", value="xyz")})
@DeclareRoles({"user","client"})
public class AnnotationTest extends HttpServlet 
{
    static List<String> __HandlesTypes; 
    private String postConstructResult = "";
    private String dsResult = "";
    private String envResult = "";
    private String envLookupResult = "";
    private String envResult2 ="";
    private String envLookupResult2 = "";
    private String envResult3 = "";
    private String envLookupResult3 = "";
    private String dsLookupResult = "";
    private String txResult = "";
    private String txLookupResult = "";
    private DataSource myDS;
    private ServletConfig config;
    
    @Resource(mappedName="UserTransaction")
    private UserTransaction myUserTransaction;


    @Resource(mappedName="maxAmount")
    private Double maxAmount;
    
    @Resource(name="someAmount")
    private Double minAmount;

    @Resource
    private Double avgAmount;

   
    @Resource(mappedName="jdbc/mydatasource")
    public void setMyDatasource(DataSource ds)
    {
        myDS=ds;
    }
  
    
    @PostConstruct
    private void myPostConstructMethod ()
    {       
        postConstructResult = "Called";
       try 
       {
           dsResult = (myDS==null?"FAIL":"myDS="+myDS.toString());
       }
       catch (Exception e)
       {
           dsResult = "FAIL: "+e;
       }


       envResult = (maxAmount==null?"FAIL":"maxAmount="+maxAmount.toString());
       
       try
       {
           InitialContext ic = new InitialContext();
           envLookupResult = "java:comp/env/com.acme.AnnotationTest/maxAmount="+ic.lookup("java:comp/env/com.acme.AnnotationTest/maxAmount");
       }
       catch (Exception e)
       {
           envLookupResult = "FAIL: "+e;
       }

      envResult2 = (minAmount==null?"FAIL":"minAmount="+minAmount.toString());
      try
      {
          InitialContext ic = new InitialContext();
          envLookupResult2 = "java:comp/env/someAmount="+ic.lookup("java:comp/env/someAmount");
      }
      catch (Exception e)
      {
          envLookupResult2 = "FAIL: "+e;
      }
      envResult3 = (minAmount==null?"FAIL":"avgAmount="+avgAmount.toString());
      try
      {
          InitialContext ic = new InitialContext();
          envLookupResult3 = "java:comp/env/com.acme.AnnotationTest/avgAmount="+ic.lookup("java:comp/env/com.acme.AnnotationTest/avgAmount");
      }
      catch (Exception e)
      {
          envLookupResult3 = "FAIL: "+e;
      }
      
      
      
       try
       {
           InitialContext ic = new InitialContext();
           dsLookupResult = "java:comp/env/com.acme.AnnotationTest/myDatasource="+ic.lookup("java:comp/env/com.acme.AnnotationTest/myDatasource");
       }
       catch (Exception e)
       {
           dsLookupResult = "FAIL: "+e;
       }
       
       txResult = (myUserTransaction==null?"FAIL":"myUserTransaction="+myUserTransaction);
       try
       {
           InitialContext ic = new InitialContext();
           txLookupResult = "java:comp/env/com.acme.AnnotationTest/myUserTransaction="+ic.lookup("java:comp/env/com.acme.AnnotationTest/myUserTransaction");
       }
       catch (Exception e)
       {
           txLookupResult = "FAIL: "+e;
       }
    }
    
    @PreDestroy
    private void myPreDestroyMethod()
    {
    }
    
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        this.config = config;
    }

    
    
    /* ------------------------------------------------------------ */
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    /* ------------------------------------------------------------ */
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {      
        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<body>");
            out.println("<h1>Results</h1>");

            out.println("<h2>Init Params from Annotation</h2>");
            out.println("<pre>");
            out.println("initParams={@WebInitParam(name=\"fromAnnotation\", value=\"xyz\")}");
            out.println("</pre>");
            out.println("<br/><b>Result: "+("xyz".equals(config.getInitParameter("fromAnnotation"))? "PASS": "FAIL"));

            out.println("<h2>Init Params from web-fragment</h2>");
            out.println("<pre>");
            out.println("extra1=123, extra2=345");
            out.println("</pre>");
            boolean fragInitParamResult = "123".equals(config.getInitParameter("extra1")) && "345".equals(config.getInitParameter("extra2"));
            out.println("<br/><b>Result: "+(fragInitParamResult? "PASS": "FAIL"));


             __HandlesTypes = Arrays.asList( "javax.servlet.GenericServlet", 
                                             "javax.servlet.http.HttpServlet", 
                                             "com.acme.AnnotationTest", 
                                             "com.acme.RoleAnnotationTest", 
                                             "com.acme.MultiPartTest", 
                                             "com.acme.FragmentServlet", 
                                             "com.acme.TestListener",
                                             "com.acme.SecuredServlet",
                                             "com.acme.Bar");
             out.println("<h2>@ContainerInitializer</h2>");
             out.println("<pre>");
             out.println("@HandlesTypes({javax.servlet.Servlet.class, Foo.class})");
             out.println("</pre>");
             out.print("<br/><b>Result: ");
             List<Class> classes = (List<Class>)config.getServletContext().getAttribute("com.acme.Foo");
             List<String> classNames = new ArrayList<String>();
             if (classes != null)
             {
                 for (Class c: classes)
                 {
                     classNames.add(c.getName());
                     out.print(c.getName()+" ");
                 }
                
                 if (classNames.size() != __HandlesTypes.size())
                     out.println("<br/>FAIL");
                 else if (!classNames.containsAll(__HandlesTypes))
                     out.println("<br/>FAIL");
                 else
                     out.println("<br/>PASS");
             }
             else
                 out.print("<br/>FAIL (No such attribute com.acme.Foo)");
             out.println("</b>");

            out.println("<h2>Complete Servlet Registration</h2>");
            Boolean complete = (Boolean)config.getServletContext().getAttribute("com.acme.AnnotationTest.complete");
            out.println("<br/><b>Result: "+(complete.booleanValue()?"PASS":"FAIL")+"</b>");
            
            out.println("<h2>@PostConstruct Callback</h2>");
            out.println("<pre>");
            out.println("@PostConstruct");
            out.println("private void myPostConstructMethod ()");
            out.println("{}"); 
            out.println("</pre>");
            out.println("<br/><b>Result: "+postConstructResult+"</b>");
           
            
            out.println("<h2>@Resource Injection for DataSource</h2>");    
            out.println("<pre>");         
            out.println("@Resource(mappedName=\"jdbc/mydatasource\");");
            out.println("public void setMyDatasource(DataSource ds)");
            out.println("{");
            out.println("myDS=ds;");
            out.println("}");
            out.println("</pre>");
            out.println("<br/><b>Result: "+dsResult+"</b>");
            out.println("<br/><b>JNDI Lookup Result: "+dsLookupResult+"</b>");

            
            out.println("<h2>@Resource Injection for env-entry </h2>");
            out.println("<pre>");
            out.println("@Resource(mappedName=\"maxAmount\")");
            out.println("private Double maxAmount;");
            out.println("@Resource(name=\"minAmount\")");
            out.println("private Double minAmount;");
            out.println("</pre>");
            out.println("<br/><b>Result: "+envResult+": "+(maxAmount.compareTo(new Double(55))==0?" PASS":" FAIL")+"</b>");     
            out.println("<br/><b>JNDI Lookup Result: "+envLookupResult+"</b>");
            out.println("<br/><b>Result: "+envResult2+": "+(minAmount.compareTo(new Double("0.99"))==0?" PASS":" FAIL")+"</b>");     
            out.println("<br/><b>JNDI Lookup Result: "+envLookupResult2+"</b>");
            out.println("<br/><b>Result: "+envResult3+": "+(avgAmount.compareTo(new Double("1.25"))==0?" PASS":" FAIL")+"</b>");     
            out.println("<br/><b>JNDI Lookup Result: "+envLookupResult3+"</b>");          
            out.println("<h2>@Resource Injection for UserTransaction </h2>");
            out.println("<pre>");
            out.println("@Resource(mappedName=\"UserTransaction\")");
            out.println("private UserTransaction myUserTransaction;");
            out.println("</pre>");
            out.println("<br/><b>Result: "+txResult+"</b>");
            out.println("<br/><b>JNDI Lookup Result: "+txLookupResult+"</b>");
            out.println("<h2>DeclaresRoles</h2>");
            out.println("<p>Login as user \"admin\" with password \"admin\" when prompted after clicking the button below to test @DeclareRoles annotation</p>");
            String context = request.getContextPath();
            if (!context.endsWith("/"))
                context += "/";
            context += "role/";
            out.println("<form action="+context+" method=\"post\"><button type=\"submit\">Test Role Annotations</button></form>");

            out.println("<h2>ServletSecurity</h2>");
            out.println("<p>Login as user \"admin\" with password \"admin\" when prompted after clicking the button below to test @ServletSecurity annotation</p>");
            context = request.getContextPath();
            if (!context.endsWith("/"))
                context += "/";
            context += "sec/foo";
            out.println("<form action="+context+" method=\"post\"><button type=\"submit\">Test ServletSecurity Annotation</button></form>");


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

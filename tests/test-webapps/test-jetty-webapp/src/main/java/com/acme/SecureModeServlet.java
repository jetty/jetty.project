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

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Calendar;
import java.util.GregorianCalendar;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Dump Servlet Request.
 */
@SuppressWarnings("serial")
public class SecureModeServlet extends HttpServlet
{
    private static final Logger LOG = Log.getLogger(SecureModeServlet.class);

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {

        response.setContentType("text/html");
        ServletOutputStream out = response.getOutputStream();
        out.println("<html>");
        out.println("  <title>Secure Jetty Test Webapp</title>");

        try
        {
            runPropertyChecks(out);

            runFileSystemChecks(out);

            runLoggingChecks(out);

            runClassloaderChecks(out);
        }
        catch (Exception e)
        {
            e.printStackTrace(new PrintStream(out));
        }
        out.println("</html>");
        out.flush();

        try
        {
            Thread.sleep(200);
        }
        catch (InterruptedException e)
        {
            getServletContext().log("exception", e);
        }
    }

    private void runClassloaderChecks(ServletOutputStream out) throws Exception
    {
        out.println("    <h1>Checking Classloader Setup</h1>");
        out.println("      <p>");

        System.getProperty("user.dir");
        try
        {
            out.println("check ability to create classloader<br/>");
            URL url = new URL("http://not.going.to.work");
            new URLClassLoader(new URL[]{url});
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        out.println("      </p><br/><br/>");
    }

    private void runLoggingChecks(ServletOutputStream out) throws Exception
    {
        out.println("    <h1>Checking File System</h1>");
        out.println("      <p>");

        String userDir = System.getProperty("user.dir");
        try
        {
            out.println("check ability to log<br/>");
            LOG.info("testing logging");
            out.println("status: <b>SUCCESS - expected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - unexpected</b><br/>");
            out.println("<table><tr><td>");
            e.printStackTrace(new PrintStream(out));
            out.println("</td></tr></table>");
        }

        try
        {
            Calendar c = new GregorianCalendar();

            String logFile = c.get(Calendar.YEAR) + "_" + c.get(Calendar.MONTH) + "_" + c.get(Calendar.DAY_OF_MONTH) + ".request.log";

            out.println("check ability to access log file directly<br/>");
            File jettyHomeFile = new File(userDir + File.separator + "logs" + File.separator + logFile);
            jettyHomeFile.canRead();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        out.println("      </p><br/><br/>");
    }

    private void runFileSystemChecks(ServletOutputStream out) throws Exception
    {
        out.println("    <h1>Checking File System</h1>");

        /*
         * test the reading and writing of a read only permission
         */
        out.println("      <p>");

        String userDir = System.getProperty("user.dir");
        try
        {
            out.println("check read for $jetty.home/lib/policy/jetty.policy<br/>");

            File jettyHomeFile = new File(userDir + File.separator + "lib" + File.separator + "policy" + File.separator + "jetty.policy");
            jettyHomeFile.canRead();
            out.println("status: <b>SUCCESS - expected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - unexpected</b><br/>");
            out.println("<table><tr><td>");
            e.printStackTrace(new PrintStream(out));
            out.println("</td></tr></table>");
        }

        try
        {
            out.println("check write permission for $jetty.home/lib/policy/jetty.policy<br/>");

            File jettyHomeFile = new File(userDir + File.separator + "lib" + File.separator + "policy" + File.separator + "jetty.policy");
            jettyHomeFile.canWrite();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        try
        {
            out.println("check read permission for $jetty.home/lib<br/>");

            File jettyHomeFile = new File(userDir + File.separator + "lib");
            jettyHomeFile.canRead();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        try
        {
            out.println("check write permission for $jetty.home/lib<br/>");

            File jettyHomeFile = new File(userDir + File.separator + "lib");
            jettyHomeFile.canWrite();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        try
        {
            out.println("check read permission for $jetty.home<br/>");

            File jettyHomeFile = new File(userDir + File.separator);
            jettyHomeFile.canRead();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        try
        {
            out.println("check write permission for $jetty.home<br/>");

            File jettyHomeFile = new File(userDir + File.separator);
            jettyHomeFile.canWrite();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        try
        {
            out.println("check read permission for $jetty.home/logs<br/>");

            File jettyHomeFile = new File(userDir + File.separator + "logs" + File.separator);
            jettyHomeFile.canRead();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        try
        {
            out.println("check read permission for $jetty.home/logs<br/>");

            File jettyHomeFile = new File(userDir + File.separator + "logs");
            jettyHomeFile.canWrite();
            out.println("status: <b>SUCCESS - unexpected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        out.println("      </p><br/><br/>");
    }

    private void runPropertyChecks(ServletOutputStream out) throws IOException
    {

        out.println("    <h1>Checking Properties</h1>");

        /*
         * test the reading and writing of a read only permission
         */
        out.println("    <h3>Declared Property - read</h3>");
        out.println("      <p>");
        try
        {
            out.println("check read permission for __ALLOWED_READ_PROPERTY <br/>");
            System.getProperty("__ALLOWED_READ_PROPERTY");
            out.println("status: <b>SUCCESS - expected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - unexpected</b><br/>");
            out.println("<table><tr><td>");
            e.printStackTrace(new PrintStream(out));
            out.println("</td></tr></table>");
        }
        try
        {
            out.println("check write permission for __ALLOWED_READ_PROPERTY<br/>");
            System.setProperty("__ALLOWED_READ_PROPERTY", "SUCCESS - unexpected");
            String value = System.getProperty("__ALLOWED_READ_PROPERTY");
            out.println("status: <b>" + value + "</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        out.println("      </p><br/><br/>");

        /*
         * test the reading and writing of a read/write permission
         */
        out.println("    <h3>Declared Property - read/write</h3>");
        out.println("      <p>");
        try
        {
            out.println("check read permission for __ALLOWED_WRITE_PROPERTY<br/>");
            System.getProperty("__ALLOWED_WRITE_PROPERTY");
            out.println("Status: <b>SUCCESS - expected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - unexpected</b><br/>");
            out.println("<table><tr><td>");
            e.printStackTrace(new PrintStream(out));
            out.println("</td></tr></table>");
        }
        try
        {
            out.println("check write permission for __ALLOWED_WRITE_PROPERTY<br/>");
            System.setProperty("__ALLOWED_WRITE_PROPERTY", "SUCCESS - expected");
            String value = System.getProperty("__ALLOWED_WRITE_PROPERTY");
            out.println("status: <b>" + value + "</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - unexpected</b><br/>");
            out.println("<table><tr><td>");
            e.printStackTrace(new PrintStream(out));
            out.println("</td></tr></table>");
        }

        out.println("      </p><br/><br/>");

        /*
         * test the reading and writing of an undeclared property
         */
        out.println("    <h3>checking forbidden properties</h3>");
        out.println("      <p>");
        try
        {
            out.println("check read permission for __UNDECLARED_PROPERTY: <br/>");
            System.getProperty("__UNDECLARED_PROPERTY");
            out.println("status: <b>SUCCESS - expected</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }
        try
        {
            out.println("check write permission for __UNDECLARED_PROPERTY: <br/>");
            System.setProperty("__UNDECLARED_PROPERTY", "SUCCESS - unexpected");
            String value = System.getProperty("__UNDECLARED_PROPERTY");
            out.println("status: <b>" + value + "</b><br/>");
        }
        catch (SecurityException e)
        {
            out.println("status: <b>FAILURE - expected</b><br/>");
        }

        out.println("      </p><br/><br/>");
    }
}

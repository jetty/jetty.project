//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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


package org.eclipse.jetty.ant;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.List;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;

import org.apache.tools.ant.AntClassLoader;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ant.types.Attribute;
import org.eclipse.jetty.ant.types.FileMatchingConfiguration;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.ant.utils.WebApplicationProxy;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.TagLibConfiguration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * An abstraction layer over Jetty WebAppContext.
 *
 */
public class WebApplicationProxyImpl implements WebApplicationProxy
{

    /** Common root temp directory for all web applications. */
    static File baseTempDirectory = new File(".");

    /** Name of this web application. */
    private String name;

    /** Location of WAR file (either expanded or not). */
    private File warFile;

    /** Application context path. */
    private String contextPath;

    /** Location of web.xml file. */
    private File webXmlFile;

    /** Location of jetty-env.xml file. */
    private File jettyEnvXml;

    /** List of classpath files. */
    private List classPathFiles;

    /** Jetty Web Application Context. */
    private WebAppContext webAppContext;

    /** Extra scan targets. */
    private FileMatchingConfiguration extraScanTargetsConfiguration;

    /** Extra context handlers. */
    private List contextHandlers;

    private List attributes;

    Configuration[] configurations;

    private FileMatchingConfiguration librariesConfiguration;

    public static void setBaseTempDirectory(File tempDirectory)
    {
        baseTempDirectory = tempDirectory;
    }


    public static class AntServletHolder extends ServletHolder
    {

        public AntServletHolder()
        {
            super();
        }


        public AntServletHolder(Class<? extends Servlet> servlet)
        {
            super(servlet);
        }


        public AntServletHolder(Servlet servlet)
        {
            super(servlet);
        }


        public AntServletHolder(String name, Class<? extends Servlet> servlet)
        {
            super(name, servlet);
        }


        public AntServletHolder(String name, Servlet servlet)
        {
            super(name, servlet);
        }


        @Override
        protected void initJspServlet() throws Exception
        {
            //super.initJspServlet();

            ContextHandler ch = ContextHandler.getContextHandler(getServletHandler().getServletContext());

            /* Set the webapp's classpath for Jasper */
            ch.setAttribute("org.apache.catalina.jsp_classpath", ch.getClassPath());

            /* Set the system classpath for Jasper */
            String sysClassPath = getSystemClassPath(ch.getClassLoader().getParent());
            setInitParameter("com.sun.appserv.jsp.classpath", sysClassPath);

            /* Set up other classpath attribute */
            if ("?".equals(getInitParameter("classpath")))
            {
                String classpath = ch.getClassPath();
                if (classpath != null)
                    setInitParameter("classpath", classpath);
            }
        }


        protected String getSystemClassPath (ClassLoader loader) throws Exception
        {
            StringBuilder classpath=new StringBuilder();
            while (loader != null)
            {
                if (loader instanceof URLClassLoader)
                {
                    URL[] urls = ((URLClassLoader)loader).getURLs();
                    if (urls != null)
                    {
                        for (int i=0;i<urls.length;i++)
                        {
                            Resource resource = Resource.newResource(urls[i]);
                            File file=resource.getFile();
                            if (file!=null && file.exists())
                            {
                                if (classpath.length()>0)
                                    classpath.append(File.pathSeparatorChar);
                                classpath.append(file.getAbsolutePath());
                            }
                        }
                    }
                }
                else if (loader instanceof AntClassLoader)
                {
                    classpath.append(((AntClassLoader)loader).getClasspath());
                }

                loader = loader.getParent();
            }

            return classpath.toString();
        }

    }

    public static class AntServletHandler extends ServletHandler
    {

        @Override
        public ServletHolder newServletHolder(Holder.Source source)
        {
            return new AntServletHolder();
        }

    }

    public static class AntWebAppContext extends WebAppContext
    {
        public AntWebAppContext()
        {
            super();
        }

        public AntWebAppContext(HandlerContainer parent, String webApp, String contextPath)
        {
            super(parent, webApp, contextPath);
        }

        public AntWebAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
        {
            super(sessionHandler, securityHandler, servletHandler, errorHandler);
        }

        public AntWebAppContext(String webApp, String contextPath)
        {
            super(webApp, contextPath);
        }

        @Override
        protected ServletHandler newServletHandler()
        {
            return new AntServletHandler();
        }
    }


    /**
     * Default constructor. Takes application name as an argument
     *
     * @param name web application name.
     */
    public WebApplicationProxyImpl(String name) throws Exception
    {
        this.name = name;
        TaskLog.log("\nConfiguring Jetty for web application: " + name);

        this.configurations = new Configuration[]
                                                { new AntWebInfConfiguration(),
                                                  new AntWebXmlConfiguration(),
                                                  new MetaInfConfiguration(),
                                                  new FragmentConfiguration(),
                                                  new EnvConfiguration(),
                                                  new PlusConfiguration(),
                                                  new AnnotationConfiguration(),
                                                  new JettyWebXmlConfiguration(),
                                                  new TagLibConfiguration() };
    }

    public List getClassPathFiles()
    {
        return classPathFiles;
    }

    public String getContextPath()
    {
        return contextPath;
    }

    public String getName()
    {
        return name;
    }

    public File getSourceDirectory()
    {
        return warFile;
    }

    public File getWebXmlFile()
    {
        return webXmlFile;
    }

    public void setSourceDirectory(File warFile)
    {
        this.warFile = warFile;
        TaskLog.log("Webapp source directory = " + warFile);
    }

    public void setContextPath(String contextPath)
    {
        if (!contextPath.startsWith("/"))
        {
            contextPath = "/" + contextPath;
        }
        this.contextPath = contextPath;
        TaskLog.log("Context path = " + contextPath);

    }

    public void setWebXml(File webXmlFile)
    {
        this.webXmlFile = webXmlFile;
    }

    public void setJettyEnvXml(File jettyEnvXml)
    {
        this.jettyEnvXml = jettyEnvXml;
        if (this.jettyEnvXml != null)
        {
            TaskLog.log("jetty-env.xml file: = " + jettyEnvXml.getAbsolutePath());
        }
    }

    public void setClassPathFiles(List classPathFiles)
    {
        this.classPathFiles = classPathFiles;
        TaskLog.log("Classpath = " + classPathFiles);
    }

    /**
     * Checks if a given file is scanned according to the internal
     * configuration. This may be difficult due to use of 'includes' and
     * 'excludes' statements.
     *
     * @param pathToFile a fully qualified path to file.
     * @return true if file is being scanned, false otherwise.
     */
    public boolean isFileScanned(String pathToFile)
    {
        return librariesConfiguration.isIncluded(pathToFile)
                || extraScanTargetsConfiguration.isIncluded(pathToFile);
    }

    public void setLibrariesConfiguration(FileMatchingConfiguration classesConfiguration)
    {
        TaskLog.log("Default scanned paths = " + classesConfiguration.getBaseDirectories());
        this.librariesConfiguration = classesConfiguration;
    }

    public List getLibraries()
    {
        return librariesConfiguration.getBaseDirectories();
    }

    public void setExtraScanTargetsConfiguration(
            FileMatchingConfiguration extraScanTargetsConfiguration)
    {
        this.extraScanTargetsConfiguration = extraScanTargetsConfiguration;
        TaskLog.log("Extra scan targets = " + extraScanTargetsConfiguration.getBaseDirectories());
    }

    public void setAttributes(List attributes)
    {
        this.attributes = attributes;
    }

    public List getExtraScanTargets()
    {
        return extraScanTargetsConfiguration.getBaseDirectories();
    }

    public List getContextHandlers()
    {
        return contextHandlers;
    }

    public void setContextHandlers(List contextHandlers)
    {
        this.contextHandlers = contextHandlers;
    }

    /**
     * @see WebApplicationProxy#getProxiedObject()
     */
    public Object getProxiedObject()
    {
        return webAppContext;
    }

    /**
     * @see WebApplicationProxy#start()
     */
    public void start()
    {
        try
        {
            TaskLog.logWithTimestamp("Starting web application " + name + " ...\n");
            webAppContext.start();
        }
        catch (Exception e)
        {
            TaskLog.log(e.toString());
        }
    }

    /**
     * @see WebApplicationProxy#stop()
     */
    public void stop()
    {
        try
        {
            TaskLog.logWithTimestamp("Stopping web application " + name + " ...");
            Thread.currentThread().sleep(500L);
            webAppContext.stop();
        }
        catch (InterruptedException e)
        {
            TaskLog.log(e.toString());
        }
        catch (Exception e)
        {
            TaskLog.log(e.toString());
        }
    }

    /**
     * @see WebApplicationProxy#createApplicationContext(org.eclipse.jetty.server.handler.ContextHandlerCollection)
     */
    public void createApplicationContext(ContextHandlerCollection contexts)
    {
        webAppContext = new AntWebAppContext(contexts, warFile.getAbsolutePath(), contextPath);
        webAppContext.setDisplayName(name);

        configurePaths();

        if ( !attributes.isEmpty() )
        {
            for ( Iterator i = attributes.iterator(); i.hasNext(); )
            {
                Attribute attr = (Attribute) i.next();

                webAppContext.setAttribute(attr.getName(),attr.getValue());
            }
        }

        configureHandlers(contexts);

        applyConfiguration();
    }

    private void configureHandlers(ContextHandlerCollection contexts)
    {
        // adding extra context handlers
        Iterator handlersIterator = contextHandlers.iterator();
        while (handlersIterator.hasNext())
        {
            ContextHandler contextHandler = (ContextHandler) handlersIterator.next();
            contexts.addHandler(contextHandler);
        }
    }

    private void configurePaths()
    {
        // configuring temp directory
        File tempDir = new File(baseTempDirectory, contextPath);
        if (!tempDir.exists())
        {
            tempDir.mkdirs();
        }
        webAppContext.setTempDirectory(tempDir);
        tempDir.deleteOnExit();
        TaskLog.log("Temp directory = " + tempDir.getAbsolutePath());

        // configuring WAR directory for packaged web applications
        if (warFile.isFile())
        {
            warFile = new File(tempDir, "webapp");
            webXmlFile = new File(new File(warFile, "WEB-INF"), "web.xml");
        }
    }

    /**
     * Applies web application configuration at the end of configuration process
     * or after application restart.
     */
    void applyConfiguration()
    {
        for (int i = 0; i < configurations.length; i++)
        {
            if (configurations[i] instanceof EnvConfiguration)
            {
                try
                {
                    if (jettyEnvXml != null && jettyEnvXml.exists())
                    {
                        ((EnvConfiguration) configurations[i]).setJettyEnvXml(Resource.toURL(jettyEnvXml));
                    }
                }
                catch (MalformedURLException e)
                {
                    throw new RuntimeException(e);
                }
            }
            else if (configurations[i] instanceof AntWebXmlConfiguration)
            {
                ((AntWebXmlConfiguration) configurations[i]).setClassPathFiles(classPathFiles);
                ((AntWebXmlConfiguration) configurations[i]).setWebAppBaseDir(warFile);
                ((AntWebXmlConfiguration) configurations[i]).setWebXmlFile(webXmlFile);
                ((AntWebXmlConfiguration) configurations[i]).setWebDefaultXmlFile(webDefaultXmlFile);
            }
        }

        try
        {
            ClassLoader loader = new WebAppClassLoader(this.getClass().getClassLoader(),
                    webAppContext);
            webAppContext.setParentLoaderPriority(true);
            webAppContext.setClassLoader(loader);
            if (webDefaultXmlFile != null)
                webAppContext.setDefaultsDescriptor(webDefaultXmlFile.getCanonicalPath());

        }
        catch (IOException e)
        {
            TaskLog.log(e.toString());
        }

        webAppContext.setConfigurations(configurations);
    }

    private File webDefaultXmlFile;

    public File getWebDefaultXmlFile()
    {
        return this.webDefaultXmlFile;
    }

    public void setWebDefaultXmlFile(File webDefaultXmlfile)
    {
        this.webDefaultXmlFile = webDefaultXmlfile;
    }
}

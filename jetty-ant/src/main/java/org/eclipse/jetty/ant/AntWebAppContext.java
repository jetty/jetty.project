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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.Servlet;

import org.apache.tools.ant.AntClassLoader;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ant.types.Attribute;
import org.eclipse.jetty.ant.types.Attributes;
import org.eclipse.jetty.ant.types.FileMatchingConfiguration;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.handler.ContextHandler;
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
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.eclipse.jetty.xml.XmlConfiguration;



/**
 * AntWebAppContext
 * 
 * Extension of WebAppContext to allow configuration via Ant environment.
 *
 */
public class AntWebAppContext extends WebAppContext
{
    public final AntWebInfConfiguration antWebInfConfiguration = new AntWebInfConfiguration();
    public final WebXmlConfiguration webXmlConfiguration = new WebXmlConfiguration();
    public final MetaInfConfiguration metaInfConfiguration = new MetaInfConfiguration();
    public final FragmentConfiguration fragmentConfiguration = new FragmentConfiguration();
    public final EnvConfiguration envConfiguration = new EnvConfiguration();
    public final PlusConfiguration plusConfiguration = new PlusConfiguration();
    public final AnnotationConfiguration annotationConfiguration = new AnnotationConfiguration();
    public final JettyWebXmlConfiguration jettyWebXmlConfiguration = new JettyWebXmlConfiguration();
    public final TagLibConfiguration tagLibConfiguration = new TagLibConfiguration();


    public final Configuration[] DEFAULT_CONFIGURATIONS = 
        { 
         antWebInfConfiguration,
         webXmlConfiguration,
         metaInfConfiguration,
         fragmentConfiguration,
         envConfiguration,
         plusConfiguration,
         annotationConfiguration,
         jettyWebXmlConfiguration,
         tagLibConfiguration
        };
    

    public final static String DEFAULT_CONTAINER_INCLUDE_JAR_PATTERN =
    ".*/.*jsp-api-[^/]*\\.jar$|.*/.*jsp-[^/]*\\.jar$|.*/.*taglibs[^/]*\\.jar$|.*/.*jstl[^/]*\\.jar$|.*/.*jsf-impl-[^/]*\\.jar$|.*/.*javax.faces-[^/]*\\.jar$|.*/.*myfaces-impl-[^/]*\\.jar$";


    /** Location of jetty-env.xml file. */
    private File jettyEnvXml;
    
    /** List of web application libraries. */
    private List libraries = new ArrayList();

    /** List of web application class directories. */
    private List classes = new ArrayList();
    
    /** context xml file to apply to the webapp */
    private File contextXml;
    
    /** List of extra scan targets for this web application. */
    private FileSet scanTargets;
    
    /** context attributes to set **/
    private Attributes attributes;
    
    private Project project;
    
    private List<File> scanFiles;
    


    /** Extra scan targets. */
    private FileMatchingConfiguration extraScanTargetsConfiguration;


    private FileMatchingConfiguration librariesConfiguration;
    

    public static void dump(ClassLoader loader)
    {
        while (loader != null)
        {
            System.err.println(loader);
            if (loader instanceof URLClassLoader)
            {
                URL[] urls = ((URLClassLoader)loader).getURLs();
                if (urls != null)
                {
                    for (URL u:urls)
                        System.err.println("\t"+u+"\n");
                }
            }
            loader = loader.getParent();
        }
    }

    
    
    /**
     * AntServletHolder
     *
     *
     */
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

    
    
    /**
     * AntServletHandler
     *
     *
     */
    public static class AntServletHandler extends ServletHandler
    {

        @Override
        public ServletHolder newServletHolder(Holder.Source source)
        {
            return new AntServletHolder();
        }

    }



    /**
     * Default constructor. Takes application name as an argument
     *
     * @param name web application name.
     */
    public AntWebAppContext(Project project) throws Exception
    {
        super();
        this.project = project;
        setConfigurations(DEFAULT_CONFIGURATIONS);
        setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, DEFAULT_CONTAINER_INCLUDE_JAR_PATTERN);
        setParentLoaderPriority(true);
    }
    

    /**
     * Adds a new Ant's attributes tag object if it have not been created yet.
     */
    public void addAttributes(Attributes atts)
    {
        if (this.attributes != null)
        {
            throw new BuildException("Only one <attributes> tag is allowed!");
        }

        this.attributes = atts;
    }

    
    public void addLib(FileSet lib)
    {
        libraries.add(lib);
    }


    public void addClasses(FileSet classes)
    {
        this.classes.add(classes);
    }
    
    

    @Override
    protected ServletHandler newServletHandler()
    {
        return new AntServletHandler();
    }


    public void setJettyEnvXml(File jettyEnvXml)
    {
        this.jettyEnvXml = jettyEnvXml;
        TaskLog.log("jetty-env.xml file: = " + (jettyEnvXml == null ? null : jettyEnvXml.getAbsolutePath()));
    }

    public File getJettyEnvXml ()
    {
        return this.jettyEnvXml;
    }

    


    public List getLibraries()
    {
        return librariesConfiguration.getBaseDirectories();
    }

 
    public void addScanTargets(FileSet scanTargets)
    {
        if (this.scanTargets != null)
        {
            throw new BuildException("Only one <scanTargets> tag is allowed!");
        }

        this.scanTargets = scanTargets;
    }
    
    public List getScanTargetFiles () 
    {
        if (this.scanTargets == null)
            return null;
        
      
        FileMatchingConfiguration configuration = new FileMatchingConfiguration();
        configuration.addDirectoryScanner(scanTargets.getDirectoryScanner(project));
        return configuration.getBaseDirectories();
    }
    
    public List<File> getScanFiles()
    {
        if (scanFiles == null)
            scanFiles = initScanFiles();
        return scanFiles;
    }
    
    
    public boolean isScanned (File file)
    {
       List<File> files = getScanFiles();
       if (files == null || files.isEmpty())
           return false;
       return files.contains(file);
    }
    
    
    public List<File> initScanFiles ()
    {
        List<File> scanList = new ArrayList<File>();
        
        if (getDescriptor() != null)
        {
            try
            {
                Resource r = Resource.newResource(getDescriptor());
                scanList.add(r.getFile());
            }
            catch (IOException e)
            {
                throw new BuildException(e);
            }
        }

        if (getJettyEnvXml() != null)
        {
            try
            {
                Resource r = Resource.newResource(getJettyEnvXml());
                scanList.add(r.getFile());
            }
            catch (IOException e)
            {
                throw new BuildException("Problem configuring scanner for jetty-env.xml", e);
            }
        }

        if (getDefaultsDescriptor() != null)
        {
            try
            {
                if (!AntWebAppContext.WEB_DEFAULTS_XML.equals(getDefaultsDescriptor()))
                {
                    Resource r = Resource.newResource(getDefaultsDescriptor());
                    scanList.add(r.getFile());
                }
            }
            catch (IOException e)
            {
                throw new BuildException("Problem configuring scanner for webdefaults.xml", e);
            }
        }

        if (getOverrideDescriptor() != null)
        {
            try
            {
                Resource r = Resource.newResource(getOverrideDescriptor());
                scanList.add(r.getFile());
            }
            catch (IOException e)
            {
                throw new BuildException("Problem configuring scanner for webdefaults.xml", e);
            }
        }

        //add any extra classpath and libs 
        List<File> cpFiles = getClassPathFiles();
        if (cpFiles != null)
            scanList.addAll(cpFiles);
        
        //any extra scan targets
        @SuppressWarnings("unchecked")
        List<File> scanFiles = (List<File>)getScanTargetFiles();
        if (scanFiles != null)
            scanList.addAll(scanFiles);
        
        return scanList;
    }
    
    
    
    @Override
    public void setWar(String path)
    {
        super.setWar(path);

        try
        {
            Resource war = Resource.newResource(path);
            if (war.exists() && war.isDirectory() && getDescriptor() == null)
            {
                Resource webXml = war.addPath("WEB-INF/web.xml");
                setDescriptor(webXml.toString());
            }
        }
        catch (IOException e)
        {
            throw new BuildException(e);
        }
    }


    /**
     * @see WebApplicationProxy#start()
     */
    public void doStart()
    {
        try
        {
            TaskLog.logWithTimestamp("Starting web application "+this.getDescriptor());
            if (jettyEnvXml != null && jettyEnvXml.exists())
                envConfiguration.setJettyEnvXml(Resource.toURL(jettyEnvXml));
            setClassLoader(new WebAppClassLoader(this.getClass().getClassLoader(), this));
            if (attributes != null && attributes.getAttributes() != null)
            {
                for (Attribute a:attributes.getAttributes())
                    setAttribute(a.getName(), a.getValue());
            }
            
            //apply a context xml file if one was supplied
            if (contextXml != null)
            {
                XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.toURL(contextXml));
                TaskLog.log("Applying context xml file "+contextXml);
                xmlConfiguration.configure(this);   
            }
            
            super.doStart();
        }
        catch (Exception e)
        {
            TaskLog.log(e.toString());
        }
    }

    /**
     * @see WebApplicationProxy#stop()
     */
    public void doStop()
    {
        try
        {
            scanFiles = null;
            TaskLog.logWithTimestamp("Stopping web application "+this);
            Thread.currentThread().sleep(500L);
            super.doStop();
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
     * @return a list of classpath files (libraries and class directories).
     */
    public List<File> getClassPathFiles()
    {
        List<File> classPathFiles = new ArrayList<File>();
        Iterator classesIterator = classes.iterator();
        while (classesIterator.hasNext())
        {
            FileSet clazz = (FileSet) classesIterator.next();
            classPathFiles.add(clazz.getDirectoryScanner(project).getBasedir());
        }

        Iterator iterator = libraries.iterator();
        while (iterator.hasNext())
        {
            FileSet library = (FileSet) iterator.next();
            String[] includedFiles = library.getDirectoryScanner(project).getIncludedFiles();
            File baseDir = library.getDirectoryScanner(project).getBasedir();

            for (int i = 0; i < includedFiles.length; i++)
            {
                classPathFiles.add(new File(baseDir, includedFiles[i]));
            }
        }


        return classPathFiles;
    }

    
    /**
     * @return a <code>FileMatchingConfiguration</code> object describing the
     *         configuration of all libraries added to this particular web app
     *         (both classes and libraries).
     */
    public FileMatchingConfiguration getLibrariesConfiguration()
    {
        FileMatchingConfiguration config = new FileMatchingConfiguration();

        Iterator classesIterator = classes.iterator();
        while (classesIterator.hasNext())
        {
            FileSet clazz = (FileSet) classesIterator.next();
            config.addDirectoryScanner(clazz.getDirectoryScanner(project));
        }

        Iterator librariesIterator = libraries.iterator();
        while (librariesIterator.hasNext())
        {
            FileSet library = (FileSet) librariesIterator.next();
            config.addDirectoryScanner(library.getDirectoryScanner(project));
        }

        return config;
    }


    public File getContextXml()
    {
        return contextXml;
    }


    public void setContextXml(File contextXml)
    {
        this.contextXml = contextXml;
    }
    
}

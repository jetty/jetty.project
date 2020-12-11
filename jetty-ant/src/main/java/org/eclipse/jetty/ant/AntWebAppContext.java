//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ant;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;

import jakarta.servlet.Servlet;
import org.apache.tools.ant.AntClassLoader;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;
import org.eclipse.jetty.ant.types.Attribute;
import org.eclipse.jetty.ant.types.Attributes;
import org.eclipse.jetty.ant.types.FileMatchingConfiguration;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.servlet.Source;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of WebAppContext to allow configuration via Ant environment.
 */
public class AntWebAppContext extends WebAppContext
{
    private static final Logger LOG = LoggerFactory.getLogger(WebAppContext.class);

    public static final String DEFAULT_CONTAINER_INCLUDE_JAR_PATTERN =
        ".*/.*jsp-api-[^/]*\\.jar$|.*/.*jsp-[^/]*\\.jar$|.*/.*taglibs[^/]*\\.jar$|.*/.*jstl[^/]*\\.jar$|.*/.*jsf-impl-[^/]*\\.jar$|.*/.*javax.faces-[^/]*\\.jar$|.*/.*myfaces-impl-[^/]*\\.jar$";

    /**
     * Location of jetty-env.xml file.
     */
    private File jettyEnvXml;

    /**
     * List of web application libraries.
     */
    private List<FileSet> libraries = new ArrayList<>();

    /**
     * List of web application class directories.
     */
    private List<FileSet> classes = new ArrayList<>();

    /**
     * context xml file to apply to the webapp
     */
    private File contextXml;

    /**
     * List of extra scan targets for this web application.
     */
    private FileSet scanTargets;

    /**
     * context attributes to set
     **/
    private Attributes attributes;

    private Project project;

    private List<File> scanFiles;

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
                    for (URL u : urls)
                    {
                        System.err.println("\t" + u + "\n");
                    }
                }
            }
            loader = loader.getParent();
        }
    }

    /**
     * AntURLClassLoader
     *
     * Adapt the AntClassLoader which is not a URLClassLoader - this is needed for
     * jsp to be able to search the classpath.
     */
    public static class AntURLClassLoader extends URLClassLoader
    {
        private AntClassLoader antLoader;

        public AntURLClassLoader(AntClassLoader antLoader)
        {
            super(new URL[]{}, antLoader);
            this.antLoader = antLoader;
        }

        @Override
        public InputStream getResourceAsStream(String name)
        {
            return super.getResourceAsStream(name);
        }

        @Override
        public void close() throws IOException
        {
            super.close();
        }

        @Override
        protected void addURL(URL url)
        {
            super.addURL(url);
        }

        @Override
        public URL[] getURLs()
        {
            Set<URL> urls = new HashSet<URL>();

            //convert urls from antLoader
            String[] paths = antLoader.getClasspath().split(new String(new char[]{File.pathSeparatorChar}));
            if (paths != null)
            {
                for (String p : paths)
                {
                    File f = new File(p);
                    try
                    {
                        urls.add(f.toURI().toURL());
                    }
                    catch (Exception e)
                    {
                        LOG.trace("IGNORED", e);
                    }
                }
            }

            //add in any that may have been added to us as a URL directly
            URL[] ourURLS = super.getURLs();
            if (ourURLS != null)
            {
                for (URL u : ourURLS)
                {
                    urls.add(u);
                }
            }

            return urls.toArray(new URL[urls.size()]);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException
        {
            return super.findClass(name);
        }

        @Override
        public URL findResource(String name)
        {
            return super.findResource(name);
        }

        @Override
        public Enumeration<URL> findResources(String name) throws IOException
        {
            return super.findResources(name);
        }

        @Override
        protected PermissionCollection getPermissions(CodeSource codesource)
        {
            return super.getPermissions(codesource);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException
        {
            return super.loadClass(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
        {
            return super.loadClass(name, resolve);
        }

        @Override
        protected Object getClassLoadingLock(String className)
        {
            return super.getClassLoadingLock(className);
        }

        @Override
        public URL getResource(String name)
        {
            return super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException
        {
            return super.getResources(name);
        }

        @Override
        protected Package definePackage(String name, Manifest man, URL url) throws IllegalArgumentException
        {
            return super.definePackage(name, man, url);
        }

        @Override
        protected Package definePackage(String name, String specTitle, String specVersion, String specVendor, String implTitle, String implVersion,
                                        String implVendor, URL sealBase) throws IllegalArgumentException
        {
            return super.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
        }

        @Override
        protected Package[] getPackages()
        {
            return super.getPackages();
        }

        @Override
        protected String findLibrary(String libname)
        {
            return super.findLibrary(libname);
        }

        @Override
        public void setDefaultAssertionStatus(boolean enabled)
        {
            super.setDefaultAssertionStatus(enabled);
        }

        @Override
        public void setPackageAssertionStatus(String packageName, boolean enabled)
        {
            super.setPackageAssertionStatus(packageName, enabled);
        }

        @Override
        public void setClassAssertionStatus(String className, boolean enabled)
        {
            super.setClassAssertionStatus(className, enabled);
        }

        @Override
        public void clearAssertionStatus()
        {
            super.clearAssertionStatus();
        }
    }

    /**
     * AntServletHolder
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

        protected String getSystemClassPath(ClassLoader loader) throws Exception
        {
            StringBuilder classpath = new StringBuilder();
            while (loader != null)
            {
                if (loader instanceof URLClassLoader)
                {
                    URL[] urls = ((URLClassLoader)loader).getURLs();
                    if (urls != null)
                    {
                        for (int i = 0; i < urls.length; i++)
                        {
                            Resource resource = Resource.newResource(urls[i]);
                            File file = resource.getFile();
                            if (file != null && file.exists())
                            {
                                if (classpath.length() > 0)
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
     */
    public static class AntServletHandler extends ServletHandler
    {

        @Override
        public ServletHolder newServletHolder(Source source)
        {
            return new AntServletHolder();
        }
    }

    /**
     * Default constructor. Takes project as an argument
     *
     * @param project the project.
     * @throws Exception if unable to create webapp context
     */
    public AntWebAppContext(Project project) throws Exception
    {
        super();
        this.project = project;
        setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, DEFAULT_CONTAINER_INCLUDE_JAR_PATTERN);
        setParentLoaderPriority(true);
        addConfiguration(new AntWebInfConfiguration(), new AntWebXmlConfiguration(), new AntMetaInfConfiguration());
    }

    /**
     * Adds a new Ant's attributes tag object if it have not been created yet.
     *
     * @param atts the attributes
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

    public File getJettyEnvXml()
    {
        return this.jettyEnvXml;
    }

    public List<File> getLibraries()
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

    public List<File> getScanTargetFiles()
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

    public boolean isScanned(File file)
    {
        List<File> files = getScanFiles();
        if (files == null || files.isEmpty())
            return false;
        return files.contains(file);
    }

    public List<File> initScanFiles()
    {
        List<File> scanList = new ArrayList<File>();

        if (getDescriptor() != null)
        {
            try (Resource r = Resource.newResource(getDescriptor());)
            {
                scanList.add(r.getFile());
            }
            catch (IOException e)
            {
                throw new BuildException(e);
            }
        }

        if (getJettyEnvXml() != null)
        {
            try (Resource r = Resource.newResource(getJettyEnvXml());)
            {
                scanList.add(r.getFile());
            }
            catch (IOException e)
            {
                throw new BuildException("Problem configuring scanner for jetty-env.xml", e);
            }
        }

        if (getDefaultsDescriptor() != null)
        {
            try (Resource r = Resource.newResource(getDefaultsDescriptor());)
            {
                if (!WebAppContext.WEB_DEFAULTS_XML.equals(getDefaultsDescriptor()))
                {
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

    @Override
    public void doStart()
    {
        try
        {
            TaskLog.logWithTimestamp("Starting web application " + this.getDescriptor());

            if (jettyEnvXml != null && jettyEnvXml.exists())
                getConfiguration(EnvConfiguration.class).setJettyEnvResource(new PathResource(jettyEnvXml));

            ClassLoader parentLoader = this.getClass().getClassLoader();
            if (parentLoader instanceof AntClassLoader)
                parentLoader = new AntURLClassLoader((AntClassLoader)parentLoader);

            setClassLoader(new WebAppClassLoader(parentLoader, this));
            if (attributes != null && attributes.getAttributes() != null)
            {
                for (Attribute a : attributes.getAttributes())
                {
                    setAttribute(a.getName(), a.getValue());
                }
            }

            //apply a context xml file if one was supplied
            if (contextXml != null)
            {
                XmlConfiguration xmlConfiguration = new XmlConfiguration(new PathResource(contextXml));
                TaskLog.log("Applying context xml file " + contextXml);
                xmlConfiguration.configure(this);
            }

            super.doStart();
        }
        catch (Exception e)
        {
            TaskLog.log(e.toString());
        }
    }

    @Override
    public void doStop()
    {
        try
        {
            scanFiles = null;
            TaskLog.logWithTimestamp("Stopping web application " + this);
            Thread.currentThread().sleep(500L);
            super.doStop();
            // remove all filters and servlets. They will be recreated
            // either via application of a context xml file or web.xml or annotation or servlet api.
            // Event listeners are reset in ContextHandler.doStop()
            getServletHandler().setFilters(new FilterHolder[0]);
            getServletHandler().setFilterMappings(new FilterMapping[0]);
            getServletHandler().setServlets(new ServletHolder[0]);
            getServletHandler().setServletMappings(new ServletMapping[0]);
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
        Iterator<FileSet> classesIterator = classes.iterator();
        while (classesIterator.hasNext())
        {
            FileSet clazz = classesIterator.next();
            classPathFiles.add(clazz.getDirectoryScanner(project).getBasedir());
        }

        Iterator<FileSet> iterator = libraries.iterator();
        while (iterator.hasNext())
        {
            FileSet library = iterator.next();
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
     * configuration of all libraries added to this particular web app
     * (both classes and libraries).
     */
    public FileMatchingConfiguration getLibrariesConfiguration()
    {
        FileMatchingConfiguration config = new FileMatchingConfiguration();

        Iterator<FileSet> classesIterator = classes.iterator();
        while (classesIterator.hasNext())
        {
            FileSet clazz = classesIterator.next();
            config.addDirectoryScanner(clazz.getDirectoryScanner(project));
        }

        Iterator<FileSet> librariesIterator = libraries.iterator();
        while (librariesIterator.hasNext())
        {
            FileSet library = librariesIterator.next();
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

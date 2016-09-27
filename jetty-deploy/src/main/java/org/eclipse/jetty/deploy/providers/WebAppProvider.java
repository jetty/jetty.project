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

package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Locale;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.ConfigurationManager;
import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

/** 
 * The webapps directory scanning provider.
 * <p>
 * This provider scans one or more directories (typically "webapps") for contexts to
 * deploy, which may be:<ul>
 * <li>A standard WAR file (must end in ".war")</li>
 * <li>A directory containing an expanded WAR file</li>
 * <li>A directory containing static content</li>
 * <li>An XML descriptor in {@link XmlConfiguration} format that configures a {@link ContextHandler} instance</li>
 * </ul>
 * <p>
 * To avoid double deployments and allow flexibility of the content of the scanned directories, the provider
 * implements some heuristics to ignore some files found in the scans: <ul>
 * <li>Hidden files (starting with ".") are ignored</li>
 * <li>Directories with names ending in ".d" are ignored</li>
 * <li>If a directory and a WAR file exist ( eg foo/ and foo.war) then the directory is assumed to be
 * the unpacked WAR and only the WAR is deployed (which may reused the unpacked directory)</li>
 * <li>If a directory and a matching XML file exist ( eg foo/ and foo.xml) then the directory is assumed to be
 * an unpacked WAR and only the XML is deployed (which may used the directory in it's configuration)</li>
 * <li>If a WAR file and a matching XML exist (eg foo.war and foo.xml) then the WAR is assumed to
 * be configured by the XML and only the XML is deployed.
 * </ul>
 * <p>For XML configured contexts, the ID map will contain a reference to the {@link Server} instance called "Server" and
 * properties for the webapp file as "jetty.webapp" and directory as "jetty.webapps".
 */
@ManagedObject("Provider for start-up deployement of webapps based on presence in directory")
public class WebAppProvider extends ScanningAppProvider
{
    private boolean _extractWars = false;
    private boolean _parentLoaderPriority = false;
    private ConfigurationManager _configurationManager;
    private String _defaultsDescriptor;
    private File _tempDirectory;
    private String[] _configurationClasses;

    public class Filter implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name)
        {
            if (!dir.exists())
            {
                return false;
            }
            String lowername = name.toLowerCase(Locale.ENGLISH);

            File file = new File(dir,name);

            // ignore hidden files
            if (lowername.startsWith("."))
                return false;

            // Ignore some directories
            if (file.isDirectory())
            {
                // is it a nominated config directory
                if (lowername.endsWith(".d"))
                    return false;

                // is it an unpacked directory for an existing war file?
                if (exists(name+".war")||exists(name+".WAR"))
                    return false;

                // is it a directory for an existing xml file?
                if (exists(name+".xml")||exists(name+".XML"))
                    return false;

                //is it a sccs dir?
                if ("cvs".equals(lowername) || "cvsroot".equals(lowername))
                    return false;

                // OK to deploy it then
                return true;
            }

            // else is it a war file
            if (lowername.endsWith(".war"))
            {
                //defer deployment decision to fileChanged()
                return true;
            }

            // else is it a context XML file 
            if (lowername.endsWith(".xml"))
                return true;

            return false;
        }
    }

    /* ------------------------------------------------------------ */
    public WebAppProvider()
    {
        super();
        setFilenameFilter(new Filter());
        setScanInterval(0);
    }

    /* ------------------------------------------------------------ */
    /** Get the extractWars.
     * @return the extractWars
     */
    @ManagedAttribute("extract war files")
    public boolean isExtractWars()
    {
        return _extractWars;
    }

    /* ------------------------------------------------------------ */
    /** Set the extractWars.
     * @param extractWars the extractWars to set
     */
    public void setExtractWars(boolean extractWars)
    {
        _extractWars = extractWars;
    }

    /* ------------------------------------------------------------ */
    /** Get the parentLoaderPriority.
     * @return the parentLoaderPriority
     */
    @ManagedAttribute("parent classloader has priority")
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /** Set the parentLoaderPriority.
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the defaultsDescriptor.
     * @return the defaultsDescriptor
     */
    @ManagedAttribute("default descriptor for webapps")
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /** Set the defaultsDescriptor.
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    public ConfigurationManager getConfigurationManager()
    {
        return _configurationManager;
    }
    
    /* ------------------------------------------------------------ */
    /** Set the configurationManager.
     * @param configurationManager the configurationManager to set
     */
    public void setConfigurationManager(ConfigurationManager configurationManager)
    {
        _configurationManager = configurationManager;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _configurationClasses = configurations==null?null:(String[])configurations.clone();
    }  
    
    /* ------------------------------------------------------------ */
    @ManagedAttribute("configuration classes for webapps to be processed through")
    public String[] getConfigurationClasses()
    {
        return _configurationClasses;
    }

    /**
     * Set the Work directory where unpacked WAR files are managed from.
     * <p>
     * Default is the same as the <code>java.io.tmpdir</code> System Property.
     *
     * @param directory the new work directory
     */
    public void setTempDir(File directory)
    {
        _tempDirectory = directory;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get the user supplied Work Directory.
     *
     * @return the user supplied work directory (null if user has not set Temp Directory yet)
     */
    @ManagedAttribute("temp directory for use, null if no user set temp directory")
    public File getTempDir()
    {
        return _tempDirectory;
    }

    /* ------------------------------------------------------------ */
    protected void initializeWebAppContextDefaults(WebAppContext webapp)
    {
        if (_defaultsDescriptor != null)
            webapp.setDefaultsDescriptor(_defaultsDescriptor);
        webapp.setExtractWAR(_extractWars);
        webapp.setParentLoaderPriority(_parentLoaderPriority);
        if (_configurationClasses != null)
            webapp.setConfigurationClasses(_configurationClasses);

        if (_tempDirectory != null)
        {
            /* Since the Temp Dir is really a context base temp directory,
             * Lets set the Temp Directory in a way similar to how WebInfConfiguration does it,
             * instead of setting the WebAppContext.setTempDirectory(File).  
             * If we used .setTempDirectory(File) all webapps will wind up in the
             * same temp / work directory, overwriting each others work.
             */
            webapp.setAttribute(WebAppContext.BASETEMPDIR, _tempDirectory);
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public ContextHandler createContextHandler(final App app) throws Exception
    {
        Resource resource = Resource.newResource(app.getOriginId());
        File file = resource.getFile();
        if (!resource.exists())
            throw new IllegalStateException("App resource does not exist "+resource);

        String context = file.getName();

        if (resource.exists() && FileID.isXmlFile(file))
        {
            XmlConfiguration xmlc = new XmlConfiguration(resource.getURI().toURL())
            {
                @Override
                public void initializeDefaults(Object context)
                {
                    super.initializeDefaults(context);

                    if (context instanceof WebAppContext)
                    {
                        WebAppContext webapp = (WebAppContext)context;
                        initializeWebAppContextDefaults(webapp);
                    }
                }
            };
            
            xmlc.getIdMap().put("Server", getDeploymentManager().getServer());
            xmlc.getProperties().put("jetty.home",System.getProperty("jetty.home","."));
            xmlc.getProperties().put("jetty.base",System.getProperty("jetty.base","."));
            xmlc.getProperties().put("jetty.webapp",file.getCanonicalPath());
            xmlc.getProperties().put("jetty.webapps",file.getParentFile().getCanonicalPath());

            if (getConfigurationManager() != null)
                xmlc.getProperties().putAll(getConfigurationManager().getProperties());
            return (ContextHandler)xmlc.configure();
        }
        else if (file.isDirectory())
        {
            // must be a directory
        }
        else if (FileID.isWebArchiveFile(file))
        {
            // Context Path is the same as the archive.
            context = context.substring(0,context.length() - 4);
        }
        else
        {
            throw new IllegalStateException("unable to create ContextHandler for "+app);
        }

        // Ensure "/" is Not Trailing in context paths.
        if (context.endsWith("/") && context.length() > 0)
        {
            context = context.substring(0,context.length() - 1);
        }

        // Start building the webapplication
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setDisplayName(context);

        // special case of archive (or dir) named "root" is / context
        if (context.equalsIgnoreCase("root"))
        {
            context = URIUtil.SLASH;
        }
        else if (context.toLowerCase(Locale.ENGLISH).startsWith("root-"))
        {
            int dash=context.toLowerCase(Locale.ENGLISH).indexOf('-');
            String virtual = context.substring(dash+1);
            webAppContext.setVirtualHosts(new String[]{virtual});
            context = URIUtil.SLASH;
        }

        // Ensure "/" is Prepended to all context paths.
        if (context.charAt(0) != '/')
        {
            context = "/" + context;
        }

        webAppContext.setContextPath(context);
        webAppContext.setWar(file.getAbsolutePath());
        initializeWebAppContextDefaults(webAppContext);

        return webAppContext;
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void fileChanged(String filename) throws Exception
    {        
        File file = new File(filename);
        if (!file.exists())
            return;
        
        File parent = file.getParentFile();
        
        //is the file that changed a directory? 
        if (file.isDirectory())
        {
            //is there a .xml file of the same name?
            if (exists(file.getName()+".xml")||exists(file.getName()+".XML"))
                return; //ignore it

            //is there .war file of the same name?
            if (exists(file.getName()+".war")||exists(file.getName()+".WAR"))
                return; //ignore it

             super.fileChanged(filename);
             return;
        }
        
      
        String lowname = file.getName().toLowerCase(Locale.ENGLISH);
        //is the file that changed a .war file?
        if (lowname.endsWith(".war"))
        {
            String name = file.getName();
            String base=name.substring(0,name.length()-4);
            String xmlname = base+".xml";
            if (exists(xmlname))
            {
                //if a .xml file exists for it, then redeploy that instead
                File xml = new File (parent, xmlname);
                super.fileChanged(xml.getCanonicalPath());
                return;
            }
            
            xmlname = base+".XML";
            if (exists(xmlname))
            {
                //if a .XML file exists for it, then redeploy that instead
                File xml = new File(parent, xmlname);
                super.fileChanged(xml.getCanonicalPath());
                return;
            }
            
            //redeploy the changed war
            super.fileChanged(filename);
            return;
        }

        //is the file that changed a .xml file?
        if (lowname.endsWith(".xml"))
            super.fileChanged(filename);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void fileAdded(String filename) throws Exception
    {
        File file = new File(filename);
        if (!file.exists())
            return;

        //is the file that was added a directory? 
        if (file.isDirectory())
        {
            //is there a .xml file of the same name?
            if (exists(file.getName()+".xml")||exists(file.getName()+".XML"))
                return; //assume we will get added events for the xml file

            //is there .war file of the same name?
            if (exists(file.getName()+".war")||exists(file.getName()+".WAR"))
                return; //assume we will get added events for the war file

            super.fileAdded(filename);
            return;
        }


        //is the file that was added a .war file?
        String lowname = file.getName().toLowerCase(Locale.ENGLISH);
        if (lowname.endsWith(".war"))
        {
            String name = file.getName();
            String base=name.substring(0,name.length()-4);
            //is there a .xml file of the same name?
            if (exists(base+".xml")||exists(base+".XML")) 
                return; //ignore it as we should get addition of the xml file

            super.fileAdded(filename);
            return;
        }

        //is the file that was added an .xml file?
        if (lowname.endsWith(".xml"))
            super.fileAdded(filename);
    }

    
    /* ------------------------------------------------------------ */
    @Override
    protected void fileRemoved(String filename) throws Exception
    { 
        File file = new File(filename);

        //is the file that was removed a directory? 
        if (file.isDirectory())
        {
            //is there a .xml file of the same name?
            if (exists(file.getName()+".xml")||exists(file.getName()+".XML"))
                return; //assume we will get removed events for the xml file

            //is there .war file of the same name?
            if (exists(file.getName()+".war")||exists(file.getName()+".WAR"))
                return; //assume we will get removed events for the war file

            super.fileRemoved(filename);
            return;
        }
  
        //is the file that was removed a .war file?
        String lowname = file.getName().toLowerCase(Locale.ENGLISH);
        if (lowname.endsWith(".war"))
        {
            //is there a .xml file of the same name?
            String name = file.getName();
            String base=name.substring(0,name.length()-4);
            if (exists(base+".xml")||exists(base+".XML"))
                return; //ignore it as we should get removal of the xml file

            super.fileRemoved(filename);
            return;
        }

        //is the file that was removed an .xml file?
        if (lowname.endsWith(".xml"))
            super.fileRemoved(filename);
    }

}

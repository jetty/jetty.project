//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Locale;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/* ------------------------------------------------------------ */
/** Context directory App Provider.
 * <p>This specialization of {@link ScanningAppProvider} is the
 * replacement for old (and deprecated) <code>org.eclipse.jetty.deploy.WebAppDeployer</code> and it will scan a directory
 * only for war files or directories files.</p>
 * <p>
 * Webapps with names root or starting with root- are deployed at /.
 * If the name is in the format root-hostname, then the webapp is deployed
 * at / in the virtual host hostname.
 */
public class WebAppProvider extends ScanningAppProvider
{
    private boolean _extractWars = false;
    private boolean _parentLoaderPriority = false;
    private String _defaultsDescriptor;
    private Filter _filter;
    private File _tempDirectory;
    private String[] _configurationClasses;

    public static class Filter implements FilenameFilter
    {
        private File _contexts;
        
        public boolean accept(File dir, String name)
        {
            if (!dir.exists())
            {
                return false;
            }
            String lowername = name.toLowerCase(Locale.ENGLISH);
            
            File file = new File(dir,name);
            // is it not a directory and not a war ?
            if (!file.isDirectory() && !lowername.endsWith(".war"))
            {
                return false;
            }
            
            //ignore hidden files
            if (lowername.startsWith("."))
                return false;
                   
            if (file.isDirectory())
            {
                // is it a directory for an existing war file?
                if (new File(dir,name+".war").exists() ||
                    new File(dir,name+".WAR").exists())

                    return false;
 
                //is it a sccs dir?
                if ("cvs".equals(lowername) || "cvsroot".equals(lowername))
                    return false;
            }
            
            // is there a contexts config file
            if (_contexts!=null)
            {
                String context=name;
                if (!file.isDirectory())
                {
                    context=context.substring(0,context.length()-4);
                }
                if (new File(_contexts,context+".xml").exists() ||
                    new File(_contexts,context+".XML").exists() )
                {
                    return false;
                }
            }
               
            return true;
        }
    }
    
    /* ------------------------------------------------------------ */
    public WebAppProvider()
    {
        super(new Filter());
        _filter=(Filter)_filenameFilter;
        setScanInterval(0);
    }

    /* ------------------------------------------------------------ */
    /** Get the extractWars.
     * @return the extractWars
     */
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
    public String getContextXmlDir()
    {
        return _filter._contexts==null?null:_filter._contexts.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the directory in which to look for context XML files.
     * <p>
     * If a webapp call "foo/" or "foo.war" is discovered in the monitored
     * directory, then the ContextXmlDir is examined to see if a foo.xml
     * file exists.  If it does, then this deployer will not deploy the webapp
     * and the ContextProvider should be used to act on the foo.xml file.
     * @see ContextProvider
     * @param contextsDir
     */
    public void setContextXmlDir(String contextsDir)
    {
        try
        {
            _filter._contexts=Resource.newResource(contextsDir).getFile();
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException(e);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
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
    /**
     * 
     */
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
    
    /**
     * Get the user supplied Work Directory.
     * 
     * @return the user supplied work directory (null if user has not set Temp Directory yet)
     */
    public File getTempDir()
    {
        return _tempDirectory;
    }
    
    /* ------------------------------------------------------------ */
    public ContextHandler createContextHandler(final App app) throws Exception
    {
        Resource resource = Resource.newResource(app.getOriginId());
        File file = resource.getFile();
        if (!resource.exists())
            throw new IllegalStateException("App resouce does not exist "+resource);

        String context = file.getName();
        
        if (file.isDirectory())
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
        WebAppContext wah = new WebAppContext();
        wah.setDisplayName(context);
        
        // special case of archive (or dir) named "root" is / context
        if (context.equalsIgnoreCase("root"))
        {
            context = URIUtil.SLASH;
        }
        else if (context.toLowerCase(Locale.ENGLISH).startsWith("root-"))
        {
            int dash=context.toLowerCase(Locale.ENGLISH).indexOf('-');
            String virtual = context.substring(dash+1);
            wah.setVirtualHosts(new String[]{virtual});
            context = URIUtil.SLASH;
        }

        // Ensure "/" is Prepended to all context paths.
        if (context.charAt(0) != '/') 
        {
            context = "/" + context;
        }


        wah.setContextPath(context);
        wah.setWar(file.getAbsolutePath());
        if (_defaultsDescriptor != null) 
        {
            wah.setDefaultsDescriptor(_defaultsDescriptor);
        }
        wah.setExtractWAR(_extractWars);
        wah.setParentLoaderPriority(_parentLoaderPriority);
        if (_configurationClasses != null) 
        {
            wah.setConfigurationClasses(_configurationClasses);
        }

        if (_tempDirectory != null)
        {
            /* Since the Temp Dir is really a context base temp directory,
             * Lets set the Temp Directory in a way similar to how WebInfConfiguration does it,
             * instead of setting the
             * WebAppContext.setTempDirectory(File).  
             * If we used .setTempDirectory(File) all webapps will wind up in the
             * same temp / work directory, overwriting each others work.
             */
            wah.setAttribute(WebAppContext.BASETEMPDIR,_tempDirectory);
        }
        return wah; 
    }
    
}

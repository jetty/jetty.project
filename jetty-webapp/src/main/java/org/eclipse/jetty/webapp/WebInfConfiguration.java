//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.PatternMatcher;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

public class WebInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(WebInfConfiguration.class);

    public static final String TEMPDIR_CONFIGURED = "org.eclipse.jetty.tmpdirConfigured";
    public static final String CONTAINER_JAR_PATTERN = "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern";
    public static final String WEBINF_JAR_PATTERN = "org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern";
    
    /**
     * If set, to a list of URLs, these resources are added to the context
     * resource base as a resource collection. 
     */
    public static final String RESOURCE_URLS = "org.eclipse.jetty.resources";
    
    protected Resource _preUnpackBaseResource;
    
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        // Look for a work directory
        File work = findWorkDirectory(context);
        if (work != null)
            makeTempDirectory(work, context, false);
        
        //Make a temp directory for the webapp if one is not already set
        resolveTempDirectory(context);
        
        //Extract webapp if necessary
        unpack (context);

        
        //Apply an initial ordering to the jars which governs which will be scanned for META-INF
        //info and annotations. The ordering is based on inclusion patterns.       
        String tmp = (String)context.getAttribute(WEBINF_JAR_PATTERN);
        Pattern webInfPattern = (tmp==null?null:Pattern.compile(tmp));
        tmp = (String)context.getAttribute(CONTAINER_JAR_PATTERN);
        Pattern containerPattern = (tmp==null?null:Pattern.compile(tmp));

        //Apply ordering to container jars - if no pattern is specified, we won't
        //match any of the container jars
        PatternMatcher containerJarNameMatcher = new PatternMatcher ()
        {
            public void matched(URI uri) throws Exception
            {
                context.getMetaData().addContainerJar(Resource.newResource(uri));
            }      
        };
        ClassLoader loader = null;
        if (context.getClassLoader() != null)
            loader = context.getClassLoader().getParent();

        while (loader != null && (loader instanceof URLClassLoader))
        {
            URL[] urls = ((URLClassLoader)loader).getURLs();
            if (urls != null)
            {
                URI[] containerUris = new URI[urls.length];
                int i=0;
                for (URL u : urls)
                {
                    try 
                    {
                        containerUris[i] = u.toURI();
                    }
                    catch (URISyntaxException e)
                    {
                        containerUris[i] = new URI(u.toString().replaceAll(" ", "%20"));
                    }  
                    i++;
                }
                containerJarNameMatcher.match(containerPattern, containerUris, false);
            }
            loader = loader.getParent();
        }
        
        //Apply ordering to WEB-INF/lib jars
        PatternMatcher webInfJarNameMatcher = new PatternMatcher ()
        {
            @Override
            public void matched(URI uri) throws Exception
            {
                context.getMetaData().addWebInfJar(Resource.newResource(uri));
            }      
        };
        List<Resource> jars = findJars(context);
       
        //Convert to uris for matching
        URI[] uris = null;
        if (jars != null)
        {
            uris = new URI[jars.size()];
            int i=0;
            for (Resource r: jars)
            {
                uris[i++] = r.getURI();
            }
        }
        webInfJarNameMatcher.match(webInfPattern, uris, true); //null is inclusive, no pattern == all jars match 
    }
    

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        //cannot configure if the context is already started
        if (context.isStarted())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Cannot configure webapp "+context+" after it is started");
            return;
        }

        Resource web_inf = context.getWebInf();

        // Add WEB-INF classes and lib classpaths
        if (web_inf != null && web_inf.isDirectory() && context.getClassLoader() instanceof WebAppClassLoader)
        {
            // Look for classes directory
            Resource classes= web_inf.addPath("classes/");
            if (classes.exists())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(classes);

            // Look for jars
            Resource lib= web_inf.addPath("lib/");
            if (lib.exists() || lib.isDirectory())
                ((WebAppClassLoader)context.getClassLoader()).addJars(lib);
        }
        
        // Look for extra resource
        @SuppressWarnings("unchecked")
        List<Resource> resources = (List<Resource>)context.getAttribute(RESOURCE_URLS);
        if (resources!=null)
        {
            Resource[] collection=new Resource[resources.size()+1];
            int i=0;
            collection[i++]=context.getBaseResource();
            for (Resource resource : resources)
                collection[i++]=resource;
            context.setBaseResource(new ResourceCollection(collection));
        }
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        // delete temp directory if we had to create it or if it isn't called work
        Boolean tmpdirConfigured = (Boolean)context.getAttribute(TEMPDIR_CONFIGURED);
        
        if (context.getTempDirectory()!=null && (tmpdirConfigured == null || !tmpdirConfigured.booleanValue()) && !isTempWorkDirectory(context.getTempDirectory()))
        {
            IO.delete(context.getTempDirectory());
            context.setTempDirectory(null);
            
            //clear out the context attributes for the tmp dir only if we had to
            //create the tmp dir
            context.setAttribute(TEMPDIR_CONFIGURED, null);
            context.setAttribute(WebAppContext.TEMPDIR, null);
        }

        
        //reset the base resource back to what it was before we did any unpacking of resources
        context.setBaseResource(_preUnpackBaseResource);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#cloneConfigure(org.eclipse.jetty.webapp.WebAppContext, org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
        File tmpDir=File.createTempFile(WebInfConfiguration.getCanonicalNameForWebAppTmpDir(context),"",template.getTempDirectory().getParentFile());
        if (tmpDir.exists())
        {
            IO.delete(tmpDir);
        }
        tmpDir.mkdir();
        tmpDir.deleteOnExit();
        context.setTempDirectory(tmpDir);
    }


    /* ------------------------------------------------------------ */
    /**
     * Get a temporary directory in which to unpack the war etc etc.
     * The algorithm for determining this is to check these alternatives
     * in the order shown:
     * 
     * <p>A. Try to use an explicit directory specifically for this webapp:</p>
     * <ol>
     * <li>
     * Iff an explicit directory is set for this webapp, use it. Do NOT set
     * delete on exit.
     * </li>
     * <li>
     * Iff javax.servlet.context.tempdir context attribute is set for
     * this webapp && exists && writeable, then use it. Do NOT set delete on exit.
     * </li>
     * </ol>
     * 
     * <p>B. Create a directory based on global settings. The new directory 
     * will be called "Jetty_"+host+"_"+port+"__"+context+"_"+virtualhost
     * Work out where to create this directory:
     * <ol>
     * <li>
     * Iff $(jetty.home)/work exists create the directory there. Do NOT
     * set delete on exit. Do NOT delete contents if dir already exists.
     * </li>
     * <li>
     * Iff WEB-INF/work exists create the directory there. Do NOT set
     * delete on exit. Do NOT delete contents if dir already exists.
     * </li>
     * <li>
     * Else create dir in $(java.io.tmpdir). Set delete on exit. Delete
     * contents if dir already exists.
     * </li>
     * </ol>
     */
    public void resolveTempDirectory (WebAppContext context)
    {
        //If a tmp directory is already set, we're done
        File tmpDir = context.getTempDirectory();
        if (tmpDir != null && tmpDir.isDirectory() && tmpDir.canWrite())
        {
            context.setAttribute(TEMPDIR_CONFIGURED, Boolean.TRUE);
            return; // Already have a suitable tmp dir configured
        }
        

        // No temp directory configured, try to establish one.
        // First we check the context specific, javax.servlet specified, temp directory attribute
        File servletTmpDir = asFile(context.getAttribute(WebAppContext.TEMPDIR));
        if (servletTmpDir != null && servletTmpDir.isDirectory() && servletTmpDir.canWrite())
        {
            // Use as tmpDir
            tmpDir = servletTmpDir;
            // Ensure Attribute has File object
            context.setAttribute(WebAppContext.TEMPDIR,tmpDir);
            // Set as TempDir in context.
            context.setTempDirectory(tmpDir);
            return;
        }

        try
        {
            // Put the tmp dir in the work directory if we had one
            File work =  new File(System.getProperty("jetty.home"),"work");
            if (work.exists() && work.canWrite() && work.isDirectory())
            {
                makeTempDirectory(work, context, false); //make a tmp dir inside work, don't delete if it exists
            }
            else
            {
                File baseTemp = asFile(context.getAttribute(WebAppContext.BASETEMPDIR));
                if (baseTemp != null && baseTemp.isDirectory() && baseTemp.canWrite())
                {
                    // Use baseTemp directory (allow the funky Jetty_0_0_0_0.. subdirectory logic to kick in
                    makeTempDirectory(baseTemp,context,false);
                }
                else
                {
                    makeTempDirectory(new File(System.getProperty("java.io.tmpdir")),context,true); //make a tmpdir, delete if it already exists
                }
            }
        }
        catch(Exception e)
        {
            tmpDir=null;
            LOG.ignore(e);
        }

        //Third ... Something went wrong trying to make the tmp directory, just make
        //a jvm managed tmp directory
        if (context.getTempDirectory() == null)
        {
            try
            {
                // Last resort
                tmpDir=File.createTempFile("JettyContext","");
                if (tmpDir.exists())
                    IO.delete(tmpDir);
                tmpDir.mkdir();
                tmpDir.deleteOnExit();
                context.setTempDirectory(tmpDir);
            }
            catch(IOException e)
            {
                tmpDir = null;
                throw new IllegalStateException("Cannot create tmp dir in "+System.getProperty("java.io.tmpdir")+ " for context "+context,e);
            }
        }
    }
    
    /**
     * Given an Object, return File reference for object.
     * Typically used to convert anonymous Object from getAttribute() calls to a File object.
     * @param fileattr the file attribute to analyze and return from (supports type File and type String, all others return null)
     * @return the File object, null if null, or null if not a File or String
     */
    private File asFile(Object fileattr)
    {
        if (fileattr == null)
        {
            return null;
        }
        if (fileattr instanceof File)
        {
            return (File)fileattr;
        }
        if (fileattr instanceof String)
        {
            return new File((String)fileattr);
        }
        return null;
    }



    public void makeTempDirectory (File parent, WebAppContext context, boolean deleteExisting)
    throws IOException
    {
        if (parent != null && parent.exists() && parent.canWrite() && parent.isDirectory())
        {
            String temp = getCanonicalNameForWebAppTmpDir(context);                    
            File tmpDir = new File(parent,temp);

            if (deleteExisting && tmpDir.exists())
            {
                if (!IO.delete(tmpDir))
                {
                    if(LOG.isDebugEnabled())LOG.debug("Failed to delete temp dir "+tmpDir);
                }
            
                //If we can't delete the existing tmp dir, create a new one
                if (tmpDir.exists())
                {
                    String old=tmpDir.toString();
                    tmpDir=File.createTempFile(temp+"_","");
                    if (tmpDir.exists())
                        IO.delete(tmpDir);
                    LOG.warn("Can't reuse "+old+", using "+tmpDir);
                } 
            }
            
            if (!tmpDir.exists())
                tmpDir.mkdir();

            //If the parent is not a work directory
            if (!isTempWorkDirectory(tmpDir))
            {
                tmpDir.deleteOnExit();
            }

            if(LOG.isDebugEnabled())
                LOG.debug("Set temp dir "+tmpDir);
            context.setTempDirectory(tmpDir);
        }
    }
    
    
    public void unpack (WebAppContext context) throws IOException
    {
        Resource web_app = context.getBaseResource();
        _preUnpackBaseResource = context.getBaseResource();
        
        if (web_app == null)
        {
            String war = context.getWar();
            if (war!=null && war.length()>0)
                web_app = context.newResource(war);
            else
                web_app=context.getBaseResource();

            // Accept aliases for WAR files
            if (web_app.getAlias() != null)
            {
                LOG.debug(web_app + " anti-aliased to " + web_app.getAlias());
                web_app = context.newResource(web_app.getAlias());
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Try webapp=" + web_app + ", exists=" + web_app.exists() + ", directory=" + web_app.isDirectory()+" file="+(web_app.getFile()));
            // Is the WAR usable directly?
            if (web_app.exists() && !web_app.isDirectory() && !web_app.toString().startsWith("jar:"))
            {
                // No - then lets see if it can be turned into a jar URL.
                Resource jarWebApp = JarResource.newJarResource(web_app);
                if (jarWebApp.exists() && jarWebApp.isDirectory())
                    web_app= jarWebApp;
            }

            // If we should extract or the URL is still not usable
            if (web_app.exists()  && (
                    (context.isCopyWebDir() && web_app.getFile() != null && web_app.getFile().isDirectory()) ||
                    (context.isExtractWAR() && web_app.getFile() != null && !web_app.getFile().isDirectory()) ||
                    (context.isExtractWAR() && web_app.getFile() == null) || 
                    !web_app.isDirectory())
                            )
            {
                // Look for sibling directory.
                File extractedWebAppDir = null;

                if (war!=null)
                {
                    // look for a sibling like "foo/" to a "foo.war"
                    File warfile=Resource.newResource(war).getFile();
                    if (warfile!=null && warfile.getName().toLowerCase(Locale.ENGLISH).endsWith(".war"))
                    {
                        File sibling = new File(warfile.getParent(),warfile.getName().substring(0,warfile.getName().length()-4));
                        if (sibling.exists() && sibling.isDirectory() && sibling.canWrite())
                            extractedWebAppDir=sibling;
                    }
                }
                
                if (extractedWebAppDir==null)
                    // Then extract it if necessary to the temporary location
                    extractedWebAppDir= new File(context.getTempDirectory(), "webapp");

                if (web_app.getFile()!=null && web_app.getFile().isDirectory())
                {
                    // Copy directory
                    LOG.info("Copy " + web_app + " to " + extractedWebAppDir);
                    web_app.copyTo(extractedWebAppDir);
                }
                else
                {
                    //Use a sentinel file that will exist only whilst the extraction is taking place.
                    //This will help us detect interrupted extractions.
                    File extractionLock = new File (context.getTempDirectory(), ".extract_lock");
                   
                    if (!extractedWebAppDir.exists())
                    {
                        //it hasn't been extracted before so extract it
                        extractionLock.createNewFile();  
                        extractedWebAppDir.mkdir();
                        LOG.info("Extract " + web_app + " to " + extractedWebAppDir);                                     
                        Resource jar_web_app = JarResource.newJarResource(web_app);
                        jar_web_app.copyTo(extractedWebAppDir);
                        extractionLock.delete();
                    }
                    else
                    {
                        //only extract if the war file is newer, or a .extract_lock file is left behind meaning a possible partial extraction
                        if (web_app.lastModified() > extractedWebAppDir.lastModified() || extractionLock.exists())
                        {
                            extractionLock.createNewFile();
                            IO.delete(extractedWebAppDir);
                            extractedWebAppDir.mkdir();
                            LOG.info("Extract " + web_app + " to " + extractedWebAppDir);
                            Resource jar_web_app = JarResource.newJarResource(web_app);
                            jar_web_app.copyTo(extractedWebAppDir);
                            extractionLock.delete();
                        }
                    }
                } 
                web_app = Resource.newResource(extractedWebAppDir.getCanonicalPath());
            }

            // Now do we have something usable?
            if (!web_app.exists() || !web_app.isDirectory())
            {
                LOG.warn("Web application not found " + war);
                throw new java.io.FileNotFoundException(war);
            }
        
            context.setBaseResource(web_app);
            
            if (LOG.isDebugEnabled())
                LOG.debug("webapp=" + web_app);
        }
        

        // Do we need to extract WEB-INF/lib?
        if (context.isCopyWebInf() && !context.isCopyWebDir())
        {
            Resource web_inf= web_app.addPath("WEB-INF/");

            File extractedWebInfDir= new File(context.getTempDirectory(), "webinf");
            if (extractedWebInfDir.exists())
                IO.delete(extractedWebInfDir);
            extractedWebInfDir.mkdir();
            Resource web_inf_lib = web_inf.addPath("lib/");
            File webInfDir=new File(extractedWebInfDir,"WEB-INF");
            webInfDir.mkdir();

            if (web_inf_lib.exists())
            {
                File webInfLibDir = new File(webInfDir, "lib");
                if (webInfLibDir.exists())
                    IO.delete(webInfLibDir);
                webInfLibDir.mkdir();

                LOG.info("Copying WEB-INF/lib " + web_inf_lib + " to " + webInfLibDir);
                web_inf_lib.copyTo(webInfLibDir);
            }

            Resource web_inf_classes = web_inf.addPath("classes/");
            if (web_inf_classes.exists())
            {
                File webInfClassesDir = new File(webInfDir, "classes");
                if (webInfClassesDir.exists())
                    IO.delete(webInfClassesDir);
                webInfClassesDir.mkdir();
                LOG.info("Copying WEB-INF/classes from "+web_inf_classes+" to "+webInfClassesDir.getAbsolutePath());
                web_inf_classes.copyTo(webInfClassesDir);
            }

            web_inf=Resource.newResource(extractedWebInfDir.getCanonicalPath());

            ResourceCollection rc = new ResourceCollection(web_inf,web_app);

            if (LOG.isDebugEnabled())
                LOG.debug("context.resourcebase = "+rc);

            context.setBaseResource(rc);   
        }
    }
    
    
    public File findWorkDirectory (WebAppContext context) throws IOException
    {
        if (context.getBaseResource() != null)
        {
            Resource web_inf = context.getWebInf();
            if (web_inf !=null && web_inf.exists())
            {
               return new File(web_inf.getFile(),"work");
            }
        }
        return null;
    }
    
    
    /**
     * Check if the tmpDir itself is called "work", or if the tmpDir
     * is in a directory called "work".
     * @return true if File is a temporary or work directory
     */
    public boolean isTempWorkDirectory (File tmpDir)
    {
        if (tmpDir == null)
            return false;
        if (tmpDir.getName().equalsIgnoreCase("work"))
            return true;
        File t = tmpDir.getParentFile();
        if (t == null)
            return false;
        return (t.getName().equalsIgnoreCase("work"));
    }
    
    
    /**
     * Create a canonical name for a webapp temp directory.
     * The form of the name is:
     *  <code>"Jetty_"+host+"_"+port+"__"+resourceBase+"_"+context+"_"+virtualhost+base36_hashcode_of_whole_string</code>
     *  
     *  host and port uniquely identify the server
     *  context and virtual host uniquely identify the webapp
     * @return the canonical name for the webapp temp directory
     */
    public static String getCanonicalNameForWebAppTmpDir (WebAppContext context)
    {
        StringBuffer canonicalName = new StringBuffer();
        canonicalName.append("jetty-");
       
        //get the host and the port from the first connector 
        Server server=context.getServer();
        if (server!=null)
        {
            Connector[] connectors = context.getServer().getConnectors();

            if (connectors.length>0)
            {
                //Get the host
                String host = (connectors==null||connectors[0]==null?"":connectors[0].getHost());
                if (host == null)
                    host = "0.0.0.0";
                canonicalName.append(host);
                
                //Get the port
                canonicalName.append("-");
                //try getting the real port being listened on
                int port = (connectors==null||connectors[0]==null?0:connectors[0].getLocalPort());
                //if not available (eg no connectors or connector not started), 
                //try getting one that was configured.
                if (port < 0)
                    port = connectors[0].getPort();
                canonicalName.append(port);
                canonicalName.append("-");
            }
        }

       
        //Resource  base
        try
        {
            Resource resource = context.getBaseResource();
            if (resource == null)
            {
                if (context.getWar()==null || context.getWar().length()==0)
                    resource=context.newResource(context.getResourceBase());
                
                // Set dir or WAR
                resource = context.newResource(context.getWar());
            }
                
            String tmp = URIUtil.decodePath(resource.getURL().getPath());
            if (tmp.endsWith("/"))
                tmp = tmp.substring(0, tmp.length()-1);
            if (tmp.endsWith("!"))
                tmp = tmp.substring(0, tmp.length() -1);
            //get just the last part which is the filename
            int i = tmp.lastIndexOf("/");
            canonicalName.append(tmp.substring(i+1, tmp.length()));
            canonicalName.append("-");
        }
        catch (Exception e)
        {
            LOG.warn("Can't generate resourceBase as part of webapp tmp dir name", e);
        }
            
        //Context name
        String contextPath = context.getContextPath();
        contextPath=contextPath.replace('/','_');
        contextPath=contextPath.replace('\\','_');
        canonicalName.append(contextPath);
        
        //Virtual host (if there is one)
        canonicalName.append("-");
        String[] vhosts = context.getVirtualHosts();
        if (vhosts == null || vhosts.length <= 0)
            canonicalName.append("any");
        else
            canonicalName.append(vhosts[0]);
        
        // sanitize
        for (int i=0;i<canonicalName.length();i++)
        {
            char c=canonicalName.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && "-.".indexOf(c)<0)
                canonicalName.setCharAt(i,'.');
        }        

        canonicalName.append("-");
        return canonicalName.toString();
    }
    
    /**
     * Look for jars in WEB-INF/lib
     * @param context
     * @return the list of jar resources found within context 
     * @throws Exception
     */
    protected List<Resource> findJars (WebAppContext context) 
    throws Exception
    {
        List<Resource> jarResources = new ArrayList<Resource>();
        
        Resource web_inf = context.getWebInf();
        if (web_inf==null || !web_inf.exists())
            return null;
        
        Resource web_inf_lib = web_inf.addPath("/lib");
       
        
        if (web_inf_lib.exists() && web_inf_lib.isDirectory())
        {
            String[] files=web_inf_lib.list();
            for (int f=0;files!=null && f<files.length;f++)
            {
                try 
                {
                    Resource file = web_inf_lib.addPath(files[f]);
                    String fnlc = file.getName().toLowerCase(Locale.ENGLISH);
                    int dot = fnlc.lastIndexOf('.');
                    String extension = (dot < 0 ? null : fnlc.substring(dot));
                    if (extension != null && (extension.equals(".jar") || extension.equals(".zip")))
                    {
                        jarResources.add(file);
                    }
                }
                catch (Exception ex)
                {
                    LOG.warn(Log.EXCEPTION,ex);
                }
            }
        }
        return jarResources;
    }
}

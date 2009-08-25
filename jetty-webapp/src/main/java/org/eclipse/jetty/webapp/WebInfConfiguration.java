package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.PatternMatcher;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

public class WebInfConfiguration implements Configuration
{
    public static final String TEMPDIR_CREATED = "org.eclipse.jetty.tmpdirCreated";
    public static final String CONTAINER_JAR_RESOURCES = "org.eclipse.jetty.containerJars";
    public static final String WEB_INF_JAR_RESOURCES = "org.eclipse.jetty.webInfJars";
    public static final String CONTAINER_JAR_PATTERN = "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern";
    public static final String WEBINF_JAR_PATTERN = "org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern";
    
    /**
     * If set, to a list of URLs, these resources are added to the context
     * resource base as a resource collection. 
     */
    public static final String RESOURCE_URLS = "org.eclipse.jetty.resources";
    
    protected Resource _preUnpackBaseResource;
    
    
    
    
    public void preConfigure(final WebAppContext context) throws Exception
    {
        //Make a temp directory for the webapp if one is not already set
        resolveTempDirectory(context);
        
        //Extract webapp if necessary
        unpack (context);

        File work = findWorkDirectory(context);
        if (work != null)
            makeTempDirectory(work, context, false);
        
        //Apply an initial ordering to the jars which governs which will be scanned for META-INF
        //info and annotations. The ordering is based on inclusion patterns.       
        String tmp = (String)context.getAttribute(WEBINF_JAR_PATTERN);
        Pattern webInfPattern = (tmp==null?null:Pattern.compile(tmp));
        tmp = (String)context.getAttribute(CONTAINER_JAR_PATTERN);
        Pattern containerPattern = (tmp==null?null:Pattern.compile(tmp));
        
        final ArrayList containerJarResources = new ArrayList<Resource>();
        context.setAttribute(CONTAINER_JAR_RESOURCES, containerJarResources);  

        //Apply ordering to container jars - if no pattern is specified, we won't
        //match any of the container jars
        PatternMatcher containerJarNameMatcher = new PatternMatcher ()
        {
            public void matched(URI uri) throws Exception
            {
                containerJarResources.add(Resource.newResource(uri));
            }      
        };
        ClassLoader loader = context.getClassLoader();
        while (loader != null && (loader instanceof URLClassLoader))
        {
            URL[] urls = ((URLClassLoader)loader).getURLs();
            if (urls != null)
            {
                URI[] containerUris = new URI[urls.length];
                int i=0;
                for (URL u : urls)
                {
                    containerUris[i++] = u.toURI();
                }
                containerJarNameMatcher.match(containerPattern, containerUris, false);
            }
            loader = loader.getParent();
        }
        
        //Apply ordering to WEB-INF/lib jars
        final ArrayList webInfJarResources = new ArrayList<Resource>();
        context.setAttribute(WEB_INF_JAR_RESOURCES, webInfJarResources);
        PatternMatcher webInfJarNameMatcher = new PatternMatcher ()
        {
            public void matched(URI uri) throws Exception
            {
                webInfJarResources.add(Resource.newResource(uri));
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
    
    
    
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(CONTAINER_JAR_RESOURCES, null);
        context.setAttribute(WEB_INF_JAR_RESOURCES, null);
    }
    

    public void configure(WebAppContext context) throws Exception
    {
        //cannot configure if the context is already started
        if (context.isStarted())
        {
            if (Log.isDebugEnabled()){Log.debug("Cannot configure webapp "+context+" after it is started");}
            return;
        }

        Resource web_inf = context.getWebInf();

        // Add WEB-INF classes and lib classpaths
        if (web_inf != null && web_inf.isDirectory() && context.getClassLoader() instanceof WebAppClassLoader)
        {
            // Look for classes directory
            Resource classes= web_inf.addPath("classes/");
            if (classes.exists())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(classes.toString());

            // Look for jars
            Resource lib= web_inf.addPath("lib/");
            if (lib.exists() || lib.isDirectory())
                ((WebAppClassLoader)context.getClassLoader()).addJars(lib);
        }
        
        // Look for extra resource
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
    
    public void deconfigure(WebAppContext context) throws Exception
    {
        // delete temp directory if we had to create it or if it isn't called work
        Boolean containerCreated = (Boolean)context.getAttribute(TEMPDIR_CREATED);
        
        if (context.getTempDirectory()!=null && (containerCreated != null && containerCreated.booleanValue()) && !isTempWorkDirectory(context.getTempDirectory()))
        {
            IO.delete(context.getTempDirectory());
            setTempDirectory(null, context);
        }
        

        context.setAttribute(TEMPDIR_CREATED, null);
        context.setAttribute(context.TEMPDIR, null);
        
        //reset the base resource back to what it was before we did any unpacking of resources
        context.setBaseResource(_preUnpackBaseResource);
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
     * 
     * @return
     */
    public void resolveTempDirectory (WebAppContext context)
    {
      //If a tmp directory is already set, we're done
        File tmpDir = context.getTempDirectory();
        if (tmpDir!=null && tmpDir.isDirectory() && tmpDir.canWrite())
            return; //Already have a suitable tmp dir configured
        

        //None configured, try and come up with one
        //First ... see if one is configured in a context attribute
        //either as a File or name of a file
        Object t = context.getAttribute(WebAppContext.TEMPDIR);
        if (t != null)
        {
            //Is it a File?
            if (t instanceof File)
            {
                tmpDir=(File)t;
                if (tmpDir.isDirectory() && tmpDir.canWrite())
                {
                    context.setTempDirectory(tmpDir);
                    return;
                }
            }
            // The context attribute specified a name not a File
            if (t instanceof String)
            {
                try
                {
                    tmpDir=new File((String)t);

                    if (tmpDir.isDirectory() && tmpDir.canWrite())
                    {
                        context.setAttribute(context.TEMPDIR,tmpDir);
                        context.setTempDirectory(tmpDir);
                        return;
                    }
                }
                catch(Exception e)
                {
                    Log.warn(Log.EXCEPTION,e);
                }
            }
        }

        // Second ... make a tmp directory, in a work directory if one exists
        String temp = getCanonicalNameForWebAppTmpDir(context);
        
        try
        {
            //Put the tmp dir in the work directory if we had one
            File work =  new File(System.getProperty("jetty.home"),"work");
            if (!work.exists() || !work.canWrite() || !work.isDirectory())
                    work = null;
            
            if (work!=null)
                makeTempDirectory(work, context, false); //make a tmp dir inside work, don't delete if it exists
            else
                makeTempDirectory(new File(System.getProperty("java.io.tmpdir")), context, true); //make a tmpdir, delete if it already exists
        }
        catch(Exception e)
        {
            tmpDir=null;
            Log.ignore(e);
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
                    tmpDir.delete();
                tmpDir.mkdir();
                tmpDir.deleteOnExit();
                setTempDirectory(tmpDir, context);
            }
            catch(IOException e)
            {
                Log.warn("tmpdir",e); System.exit(1);
            }
        }
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
                    if(Log.isDebugEnabled())Log.debug("Failed to delete temp dir "+tmpDir);
                }
            
                //If we can't delete the existing tmp dir, create a new one
                if (tmpDir.exists())
                {
                    String old=tmpDir.toString();
                    tmpDir=File.createTempFile(temp+"_","");
                    if (tmpDir.exists())
                        tmpDir.delete();
                    Log.warn("Can't reuse "+old+", using "+tmpDir);
                } 
            }
            
            if (!tmpDir.exists())
                tmpDir.mkdir();

            //If the parent is not a work directory
            if (!isTempWorkDirectory(tmpDir))
            {
                tmpDir.deleteOnExit();
                //TODO why is this here?
                File sentinel = new File(tmpDir, ".active");
                if(!sentinel.exists())
                    sentinel.mkdir();
            }
            setTempDirectory(tmpDir, context);
        }
    }

    
    public void setTempDirectory (File tmpDir, WebAppContext context)
    {
        context.setAttribute(TEMPDIR_CREATED, Boolean.TRUE);
        context.setAttribute(context.TEMPDIR,tmpDir);
        context.setTempDirectory(tmpDir);
        if(Log.isDebugEnabled())Log.debug("Set temp dir "+tmpDir);
    }
    
    
    public void unpack (WebAppContext context) throws IOException
    {
        Resource web_app = context.getBaseResource();
        
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
                Log.debug(web_app + " anti-aliased to " + web_app.getAlias());
                web_app = context.newResource(web_app.getAlias());
            }

            if (Log.isDebugEnabled())
                Log.debug("Try webapp=" + web_app + ", exists=" + web_app.exists() + ", directory=" + web_app.isDirectory());

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
                // Then extract it if necessary to the temporary location
                File extractedWebAppDir= new File(context.getTempDirectory(), "webapp");

                if (web_app.getFile()!=null && web_app.getFile().isDirectory())
                {
                    // Copy directory
                    Log.info("Copy " + web_app + " to " + extractedWebAppDir);
                    web_app.copyTo(extractedWebAppDir);
                }
                else
                {
                    if (!extractedWebAppDir.exists())
                    {
                        //it hasn't been extracted before so extract it
                        extractedWebAppDir.mkdir();
                        Log.info("Extract " + web_app + " to " + extractedWebAppDir);
                        Resource jar_web_app = JarResource.newJarResource(web_app);
                        jar_web_app.copyTo(extractedWebAppDir);
                    }
                    else
                    {
                        //only extract if the war file is newer
                        if (web_app.lastModified() > extractedWebAppDir.lastModified())
                        {
                            extractedWebAppDir.delete();
                            extractedWebAppDir.mkdir();
                            Log.info("Extract " + web_app + " to " + extractedWebAppDir);
                            Resource jar_web_app = JarResource.newJarResource(web_app);
                            jar_web_app.copyTo(extractedWebAppDir);
                        }
                    }
                } 
                web_app = Resource.newResource(extractedWebAppDir.getCanonicalPath());
            }

            // Now do we have something usable?
            if (!web_app.exists() || !web_app.isDirectory())
            {
                Log.warn("Web application not found " + war);
                throw new java.io.FileNotFoundException(war);
            }

        
            context.setBaseResource(web_app);
            
            if (Log.isDebugEnabled())
                Log.debug("webapp=" + web_app);
        }
        
        _preUnpackBaseResource = context.getBaseResource();
        
        // Do we need to extract WEB-INF/lib?
        Resource web_inf= web_app.addPath("WEB-INF/");
        if (web_inf instanceof ResourceCollection ||
            web_inf.exists() && 
            web_inf.isDirectory() && 
            (web_inf.getFile()==null || !web_inf.getFile().isDirectory()))
        {       
            File extractedWebInfDir= new File(context.getTempDirectory(), "webinf");
            if (extractedWebInfDir.exists())
                extractedWebInfDir.delete();
            extractedWebInfDir.mkdir();
            File webInfDir=new File(extractedWebInfDir,"WEB-INF");
            webInfDir.mkdir();
            Log.info("Extract " + web_inf + " to " + webInfDir);
            web_inf.copyTo(webInfDir);
            web_inf=Resource.newResource(extractedWebInfDir.toURL());
            ResourceCollection rc = new ResourceCollection(new Resource[]{web_inf,web_app});
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
     * @return
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
     * Create a canonical name for a webapp tmp directory.
     * The form of the name is:
     *  "Jetty_"+host+"_"+port+"__"+resourceBase+"_"+context+"_"+virtualhost+base36 hashcode of whole string
     *  
     *  host and port uniquely identify the server
     *  context and virtual host uniquely identify the webapp
     * @return
     */
    public String getCanonicalNameForWebAppTmpDir (WebAppContext context)
    {
        StringBuffer canonicalName = new StringBuffer();
        canonicalName.append("Jetty");
       
        //get the host and the port from the first connector 
        Connector[] connectors = context.getServer().getConnectors();
        
        
        //Get the host
        canonicalName.append("_");
        String host = (connectors==null||connectors[0]==null?"":connectors[0].getHost());
        if (host == null)
            host = "0.0.0.0";
        canonicalName.append(host.replace('.', '_'));
        
        //Get the port
        canonicalName.append("_");
        //try getting the real port being listened on
        int port = (connectors==null||connectors[0]==null?0:connectors[0].getLocalPort());
        //if not available (eg no connectors or connector not started), 
        //try getting one that was configured.
        if (port < 0)
            port = connectors[0].getPort();
        canonicalName.append(port);

       
        //Resource  base
        canonicalName.append("_");
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
        }
        catch (Exception e)
        {
            Log.warn("Can't generate resourceBase as part of webapp tmp dir name", e);
        }
            
        //Context name
        canonicalName.append("_");
        String contextPath = context.getContextPath();
        contextPath=contextPath.replace('/','_');
        contextPath=contextPath.replace('\\','_');
        canonicalName.append(contextPath);
        
        //Virtual host (if there is one)
        canonicalName.append("_");
        String[] vhosts = context.getVirtualHosts();
        if (vhosts == null || vhosts.length <= 0)
            canonicalName.append("");
        else
            canonicalName.append(vhosts[0]);
        
        //base36 hash of the whole string for uniqueness
        String hash = Integer.toString(canonicalName.toString().hashCode(),36);
        canonicalName.append("_");
        canonicalName.append(hash);
        
        // sanitize
        for (int i=0;i<canonicalName.length();i++)
        {
            char c=canonicalName.charAt(i);
            if (!Character.isJavaIdentifierPart(c))
                canonicalName.setCharAt(i,'.');
        }        
        
        return canonicalName.toString();
    }
    
    /**
     * Look for jars in WEB-INF/lib
     * @param context
     * @return
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
                    String fnlc = file.getName().toLowerCase();
                    int dot = fnlc.lastIndexOf('.');
                    String extension = (dot < 0 ? null : fnlc.substring(dot));
                    if (extension != null && (extension.equals(".jar") || extension.equals(".zip")))
                    {
                        jarResources.add(file);
                    }
                }
                catch (Exception ex)
                {
                    Log.warn(Log.EXCEPTION,ex);
                }
            }
        }
        return jarResources;
    }
}

//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.overlays;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.ConfigurationManager;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.jndi.java.javaRootURLContext;
import org.eclipse.jetty.jndi.local.localContextRoot;
import org.eclipse.jetty.server.ResourceCache;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.xml.sax.SAXException;

/**
 * Overlayed AppProvider
 * <p>
 * This {@link AppProvider} implementation can deploy either {@link WebAppContext}s or plain
 * {@link ContextHandler}s that are assembled from a series of overlays:
 * <dl>
 * <dt>webapp</dt><dd>The webapp overlay is a WAR file or docroot directory. The intent is that 
 * the WAR should be deployed to this AppProvider unchanged from how it was delivered.  All configuration
 * and extension should be able to be done in an overlay.</dd>
 * <dt>template</dt><dd>A template overlay is applied to a WAR file to configure it for all instances of
 * the webapp to be deployed in the server(s)</dd>
 * <dt>node</dt><dd>A node overlay is applied to a template to configure it all instances of the template
 * with node specific information (eg IP address, DB servers etc.).</dd>
 * <dt>instance</dt><dd>An instance overlay is applied to a node and/or template to configure it 
 * for a specific instance of the template (eg per tenant configuration).</dd>
 * </dl>
 * <p>
 * Each overlays may provide the following files and subdirectories:<dl>
 * <dt>WEB-INF/lib-overlay</dt>
 * <dd>The lib-overlay directory can contain jars that are applied to a {@link URLClassLoader} that is
 * available before any overlay.xml files are executed, so that classes from these jars may be used by the 
 * overlay.xml.</dd> 
 * 
 * <dt>WEB-INF/overlay.xml</dt>
 * <dd>This {@link XmlConfiguration} formatted file must exist in the WEB-INF directory of an overlay and is 
 * used to configure a {@link ContextHandler} or {@link WebAppContext}.  The overlay.xml from the template 
 * overlay can be used to instantiate the ContextHandler instance, so a derived class maybe used.</dd>
 * 
 * <dt>WEB-INF/template.xml</dt>
 * <dd>This {@link XmlConfiguration} formatted file if it exists in a template or node overlay, is applied to a shared instance of {@link TemplateContext}.
 * Any ID's created in a template are available as ID's in overlay.xml for an instance.</dd>
 * 
 * <dt>WEB-INF/webdefault.xml</dt>
 * <dd>If present in an overlay, then the most specific version is passed to 
 * {@link WebAppContext#setDefaultsDescriptor(String)}. Typically this is set in the template overlay.</dd>
 * 
 * <dt>WEB-INF/web-overlay.xml</dt>
 * <dd>The web-overlay.xml file of an overlay is applied to a web application as 
 * with {@link WebAppContext#addOverrideDescriptor(String)}. This allows incremental changes to web.xml without
 * totally replacing it (see webapp). Typically this is used to set init parameters.</dd>
 * 
 * <dt>.</dt>
 * <dd>This root directory contains static content that overlays the static content of the webapp
 * or earlier overlays. Using this directory, files like index.html or logo.png can be added or replaced. It can
 * also be used to replace files within WEB-INF including web.xml classes and libs.</dd>
 * </dl>
 * <p>
 * Any init parameters set on the context, filters or servlets may have parameterized values, with the parameters 
 * including:
 * <dl>
 * <dt>${overlays.dir}</dt>
 * <dd>the root overlay scan directory as a canonical file name.</dd>
 * <dt>${overlay.webapp}</dt>
 * <dd>the webapp name, same as {@link Webapp#getName()}.</dd>
 * <dt>${overlay.template}</dt>
 * <dd>the  template name, as {@link Template#getName()}.</dd>
 * <dt>${overlay.template.name}</dt>
 * <dd>the  template classifier, as {@link Template#getTemplateName()}.</dd>
 * <dt>${overlay.template.classifier}</dt>
 * <dd>the  template classifier, as {@link Template#getClassifier()()}.</dd>
 * <dt>${overlay.node}</dt>
 * <dd>the node name, as {@link Node#getName()}.</dd>
 * <dt>${overlay.instance}</dt>
 * <dd>the instance name, {@link Instance#getName()}.</dd>
 * <dt>${overlay.instance.classifier}</dt>
 * <dd>the instance name, {@link Instance#getClassifier()()}.</dd>
 * <dt>${*}</dt>
 * <dd>Any properties obtained via {@link #getConfigurationManager()}.{@link ConfigurationManager#getProperties()}</dd>
 * <dd></dd>
 * </dl>
 * <p>
 * The OverlayedAppProvider will scan the "webapps", "templates", "nodes" and "instances" subdirectories of 
 * the directory configured with {@link #setScanDir(File)}. New webapps and overlays and modified files within 
 * the overlays will trigger hot deployment, redeployment or undeployment.   The scan for modified files is 
 * restricted to only top level files (eg overlay.xml) and the files matching WEB-INF/*.xml WEB-INF/lib/*
 * and WEB-INF/classes/*.  The webapps/overlays may be directory structures or war/jar archives.
 * <p>
 * The filenames of the templates and instances are used to match them together and with a webapplication.
 * A webapp may be named anyway, but it is good practise to include a version number (eg webapps/foo-1.2.3.war
 * or webapps/foo-1.2.3/).   A template for that webapplication must have a name that includes the template name 
 * and the war name separated by '=' (eg templates/myFoo=foo-1.2.3.jar or  templates/myFoo=foo-1.2.3/).
 * An instance overlay is named with the template name and an arbitrary instance name separated by '='
 * (eg instances/myFoo=instance1.jar instances/myFoo=instance2/ etc.).
 * <p>
 * If a template name does not include a webapp name, then the template is created as a ContextHandler
 * instead of a WebAppContext (with the exact type being determined by overlay.xml).
 */
public class OverlayedAppProvider extends AbstractLifeCycle implements AppProvider
{
    private final static Logger __log=org.eclipse.jetty.util.log.Log.getLogger("OverlayedAppProvider");
    /**
     * Property set for overlay.xml and template.xml files that gives the root overlay scan directory as a canonical file name.
     */
    public final static String OVERLAYS_DIR="overlays.dir";
    /**
     *  Property set for overlay.xml and template.xml files that gives the current webapp name, as {@link Webapp#getName()}.
     */
    public final static String OVERLAY_WEBAPP="overlay.webapp";
    /**
     *  Property set for overlay.xml and template.xml files that gives the current template full name, as {@link Template#getName()}.
     */
    public final static String OVERLAY_TEMPLATE="overlay.template";
    /**
     *  Property set for overlay.xml and template.xml files that gives the current template name, as {@link Template#getTemplateName()}.
     */
    public final static String OVERLAY_TEMPLATE_NAME="overlay.template.name";
    /**
     *  Property set for overlay.xml and template.xml files that gives the current template classifier, as {@link Template#getClassifier()}.
     */
    public final static String OVERLAY_TEMPLATE_CLASSIFIER="overlay.template.classifier";
    /**
     *  Property set for overlay.xml and template.xml files that gives the current node name, as {@link Node#getName()}.
     */
    public final static String OVERLAY_NODE="overlay.node";
    /**
     *  Property set for overlay.xml and template.xml files that gives the current instance name, {@link Instance#getName()}.
     */
    public final static String OVERLAY_INSTANCE="overlay.instance";
    /**
     *  Property set for overlay.xml and template.xml files that gives the current instance clasifier, {@link Instance#getClassifier()}.
     */
    public final static String OVERLAY_INSTANCE_CLASSIFIER="overlay.instance.classifier";
    
    public final static String WEBAPPS="webapps";
    public final static String TEMPLATES="templates";
    public final static String NODES="nodes";
    public final static String INSTANCES="instances";

    public final static String LIB="WEB-INF/lib-overlay";
    public final static String WEBAPP=".";
    public final static String OVERLAY_XML="WEB-INF/overlay.xml";
    public final static String TEMPLATE_XML="WEB-INF/template.xml";
    public final static String WEB_DEFAULT_XML="WEB-INF/web-default.xml";
    public final static String WEB_FRAGMENT_XML="WEB-INF/web-overlay.xml";
    
    enum Monitor { WEBAPPS,TEMPLATES,NODES,INSTANCES} ;
    
    public final static List<Pattern> __scanPatterns = new ArrayList<Pattern>();
    
    static 
    {
        List<String> regexes = new ArrayList<String>();

        for (String s:new String[] {".war",".jar","/WEB-INF/syslib/[^/]*","/WEB-INF/lib/[^/]*","/WEB-INF/classes/[^/]*","/WEB-INF/[^/]*\\.xml",})
        {
            regexes.add(WEBAPPS+"/[^/]*"+s);
            regexes.add(TEMPLATES+"/[^/]*"+s);
            regexes.add(NODES+"/[^/]*"+s);
            regexes.add(INSTANCES+"/[^/]*"+s);
        }
        
        for (String s: regexes)
            __scanPatterns.add(Pattern.compile(s,Pattern.CASE_INSENSITIVE));
    };
    
    private String _nodeName;
    private File _scanDir;
    private File _tmpDir;
    private String _scanDirURI;
    private long _loading;
    private Node _node;
    private final Map<String,Webapp> _webapps = new HashMap<String,Webapp>();
    private final Map<String,Template> _templates = new HashMap<String,Template>();
    private final Map<String,Instance> _instances = new HashMap<String,Instance>();
    private final Map<String,OverlayedApp> _deployed = new HashMap<String,OverlayedApp>();
    private final Map<String,TemplateContext> _shared = new HashMap<String, TemplateContext>();
    private boolean _copydir=false;
    private DeploymentManager _deploymentManager;
    private ConfigurationManager _configurationManager;
    private String _serverID="Server";
    private final Set<Layer> _removedLayers = new HashSet<Layer>();
    private Timer _sessionScavenger = new Timer();
    
    private final Scanner _scanner = new Scanner();
    private final Scanner.BulkListener _listener = new Scanner.BulkListener()
    {  
        public void filesChanged(List<String> filenames) throws Exception
        {
            __log.debug("Changed {}",filenames);
            
            Set<String> changes = new HashSet<String>();
            for (String filename:filenames)
            {
                
                File file=new File(filename);
                if (file.getName().startsWith(".") || file.getName().endsWith(".swp"))
                    continue;
                
                String relname=file.toURI().getPath().substring(_scanDirURI.length());
                                
                File rel = new File(relname);
                
                String dir=null;
                String name=null;
                String parent=rel.getParent();
                while (parent!=null)
                {
                    name=rel.getName();
                    dir=parent;
                    rel=rel.getParentFile();
                    parent=rel.getParent();
                }
                
                String uri=dir+"/"+name;

                for (Pattern p : __scanPatterns)
                {
                    if (p.matcher(relname).matches())
                    {
                        __log.debug("{} == {}",relname,p.pattern());
                        changes.add(uri);
                    }
                    else
                        __log.debug("{} != {}",relname,p.pattern());
                }
            }
            
            if (changes.size()>0)
                OverlayedAppProvider.this.updateLayers(changes);
        }
    };
    

    /* ------------------------------------------------------------ */
    public OverlayedAppProvider()
    {
        try
        {
            _nodeName=InetAddress.getLocalHost().getHostName();
        }
        catch(UnknownHostException e)
        {
            __log.debug(e);
            _nodeName="unknown";
        }
    }



    /* ------------------------------------------------------------ */
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager=deploymentManager;
    }

    /* ------------------------------------------------------------ */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
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
     * @return The name in {@link XmlConfiguration#getIdMap()} of the {@link Server} instance. Default "Server".
     */
    public String getServerID()
    {
        return _serverID;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param serverID The name in {@link XmlConfiguration#getIdMap()} of the {@link Server} instance.
     */
    public void setServerID(String serverID)
    {
        _serverID = serverID;
    }
    
    
    /**
     * Create Context Handler.
     * <p>
     * Callback from the deployment manager to create a context handler instance.
     * @see org.eclipse.jetty.deploy.AppProvider#createContextHandler(org.eclipse.jetty.deploy.App)
     */
    public synchronized ContextHandler createContextHandler(App app) throws Exception
    {
        final OverlayedApp overlayed = (OverlayedApp)app;
        final String origin = overlayed.getOriginId();
        final Instance instance = overlayed.getInstance();
        final Template template = instance.getTemplate();
        final Webapp webapp = template.getWebapp();
        final Node node = _node;
        
        // remember the original loader
        ClassLoader orig_loader = Thread.currentThread().getContextClassLoader();
        try
        {
            // Look for existing shared resources
            String key=(node==null?"":node.getLoadedKey())+template.getLoadedKey()+(webapp==null?"":webapp.getLoadedKey());
            instance.setSharedKey(key);
           
            TemplateContext shared=_shared.get(key);
            // Create shared resourced
            if (shared==null)
                shared=createTemplateContext(key,webapp,template,node,orig_loader);
            
            // Build the instance lib loader
            ClassLoader shared_loader = shared.getWebappLoader()!=null?shared.getWebappLoader():(shared.getLibLoader()!=null?shared.getLibLoader():orig_loader);
            ClassLoader loader = shared_loader;
            Resource instance_lib = instance.getResource(LIB);
            if (instance_lib.exists())
            {
                List<URL> libs = new ArrayList<URL>();
                for (String jar :instance_lib.list())
                {
                    if (!jar.toLowerCase(Locale.ENGLISH).endsWith(".jar"))
                        continue;
                    libs.add(instance_lib.addPath(jar).getURL());
                }
                
                __log.debug("{}: libs={}",origin,libs);
                loader = URLClassLoader.newInstance(libs.toArray(new URL[]{}),loader);
            }
            
            // set the thread loader
            Thread.currentThread().setContextClassLoader(loader);

            // Create properties to be shared by overlay.xmls
            Map<String,Object> idMap = new HashMap<String,Object>();
            idMap.putAll(shared.getIdMap());
            idMap.put(_serverID,getDeploymentManager().getServer());
            
            // Create the instance context for the template
            ContextHandler context=null;
                
            Resource template_context_xml = template.getResource(OVERLAY_XML);
            if (template_context_xml.exists())
            {
                __log.debug("{}: overlay.xml={}",origin,template_context_xml);
                XmlConfiguration xmlc = newXmlConfiguration(template_context_xml.getURL(),idMap,template,instance);
                context=(ContextHandler)xmlc.configure();
                idMap=xmlc.getIdMap();
            }
            else if (webapp==null)
                // If there is no webapp, this is a plain context
                context=new ContextHandler();
            else
                // It is a webapp context
                context=new WebAppContext();

            // Set the resource base
            final Resource instance_webapp = instance.getResource(WEBAPP);
            if (instance_webapp.exists())
            {   
                context.setBaseResource(new ResourceCollection(instance_webapp,shared.getBaseResource()));

                // Create the resource cache
                ResourceCache cache = new ResourceCache(shared.getResourceCache(),instance_webapp,context.getMimeTypes(),false,false);
                context.setAttribute(ResourceCache.class.getCanonicalName(),cache);
            }
            else
            {
                context.setBaseResource(shared.getBaseResource());
                context.setAttribute(ResourceCache.class.getCanonicalName(),shared.getResourceCache());
            }
            __log.debug("{}: baseResource={}",origin,context.getResourceBase());
            
            // Set the shared session scavenger timer
            context.setAttribute("org.eclipse.jetty.server.session.timer", _sessionScavenger);
            
            // Apply any node or instance overlay.xml
            for (Resource context_xml : getLayeredResources(OVERLAY_XML,node,instance))
            {
                __log.debug("{}: overlay.xml={}",origin,context_xml);
                XmlConfiguration xmlc = newXmlConfiguration(context_xml.getURL(),idMap,template,instance);
                xmlc.getIdMap().put("Cache",context.getAttribute(ResourceCache.class.getCanonicalName()));
                xmlc.configure(context);
                idMap=xmlc.getIdMap();
            }

            // Is it a webapp?
            if (context instanceof WebAppContext)
            {
                final WebAppContext webappcontext = (WebAppContext)context;
                
                if (Arrays.asList(((WebAppContext)context).getServerClasses()).toString().equals(Arrays.asList(WebAppContext.__dftServerClasses).toString()))
                {
                    __log.debug("clear server classes");
                    webappcontext.setServerClasses(null);
                }
                    
                // set classloader
                webappcontext.setCopyWebDir(false);
                webappcontext.setCopyWebInf(false);
                webappcontext.setExtractWAR(false);
                
                if (instance_webapp.exists())
                {
                    final Resource classes=instance_webapp.addPath("WEB-INF/classes");
                    final Resource lib=instance_webapp.addPath("WEB-INF/lib");
                
                    if (classes.exists()||lib.exists())
                    {
                        final AtomicBoolean locked =new AtomicBoolean(false);
                        
                        WebAppClassLoader webapp_loader=new WebAppClassLoader(loader,webappcontext)
                        {
                            @Override
                            public void addClassPath(Resource resource) throws IOException
                            {
                                if (!locked.get())
                                    super.addClassPath(resource);
                            }

                            @Override
                            public void addClassPath(String classPath) throws IOException
                            {
                                if (!locked.get())
                                    super.addClassPath(classPath);
                            }

                            @Override
                            public void addJars(Resource lib)
                            {
                                if (!locked.get())
                                    super.addJars(lib);
                            }
                        };

                        if (classes.exists())
                            webapp_loader.addClassPath(classes);
                        if (lib.exists())
                            webapp_loader.addJars(lib);
                        locked.set(true);
                        
                        loader=webapp_loader;
                    }
                }
                
                // Make sure loader is unique for JNDI
                if (loader==shared_loader)
                    loader = new URLClassLoader(new URL[]{},shared_loader);

                // add default descriptor
                List<Resource> webdefaults=getLayeredResources(WEB_DEFAULT_XML,instance,node,template);
                if (webdefaults.size()>0)
                {
                    Resource webdefault = webdefaults.get(0);
                    __log.debug("{}: defaultweb={}",origin,webdefault);
                    webappcontext.setDefaultsDescriptor(webdefault.toString());
                }
                
                // add overlay descriptors
                for (Resource override : getLayeredResources(WEB_FRAGMENT_XML,template,node,instance))
                {
                    __log.debug("{}: web override={}",origin,override);
                    webappcontext.addOverrideDescriptor(override.toString());
                }
            }

            context.setClassLoader(loader);

            __log.debug("{}: baseResource={}",origin,context.getBaseResource());
            
            Resource jetty_web_xml = context.getResource("/WEB-INF/"+JettyWebXmlConfiguration.JETTY_WEB_XML);
            if (jetty_web_xml!=null && jetty_web_xml.exists())
                context.setAttribute(JettyWebXmlConfiguration.XML_CONFIGURATION,newXmlConfiguration(jetty_web_xml.getURL(),idMap,template,instance));
            
            // Add listener to expand parameters from descriptors before other listeners execute
            Map<String,String> params = new HashMap<String,String>();
            populateParameters(params,template,instance);
            context.addEventListener(new ParameterExpander(params,context));
            
            System.err.println("created:\n"+context.dump());
            
            return context;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(orig_loader);
        }
    }

    /* ------------------------------------------------------------ */
    private XmlConfiguration newXmlConfiguration(URL url, Map<String, Object> idMap, Template template, Instance instance) throws SAXException, IOException
    {
        XmlConfiguration xmlc = new XmlConfiguration(url);
        populateParameters(xmlc.getProperties(),template,instance);
        xmlc.getIdMap().putAll(idMap);
        
        return xmlc;
    }

    /* ------------------------------------------------------------ */
    private void populateParameters(Map<String,String> params,Template template, Instance instance)
    {
        try
        {
            params.put(OVERLAYS_DIR,_scanDir.getCanonicalPath());
            if (template!=null)
            {
                params.put(OVERLAY_TEMPLATE,template.getName());
                params.put(OVERLAY_TEMPLATE_NAME,template.getTemplateName());
                params.put(OVERLAY_TEMPLATE_CLASSIFIER,template.getClassifier());
                params.put(OVERLAY_WEBAPP,template.getWebapp()==null?null:template.getWebapp().getName());
            }
            if (_node!=null)
                params.put(OVERLAY_NODE,_node.getName());
            if (instance!=null)
            {
                params.put(OVERLAY_INSTANCE,instance.getName());
                params.put(OVERLAY_INSTANCE_CLASSIFIER,instance.getClassifier());
            }
            if (getConfigurationManager()!=null)
                params.putAll(getConfigurationManager().getProperties());
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }


    /* ------------------------------------------------------------ */
    private TemplateContext createTemplateContext(final String key, Webapp webapp, Template template, Node node, ClassLoader parent) throws Exception
    {
        __log.info("created {}",key);
        
        // look for libs
        // If we have libs directories, create classloader and make it available to
        // the XMLconfiguration
        List<URL> libs = new ArrayList<URL>();
        for (Resource lib : getLayeredResources(LIB,node,template))
        {
            for (String jar :lib.list())
            {
                if (!jar.toLowerCase(Locale.ENGLISH).endsWith(".jar"))
                    continue;
                libs.add(lib.addPath(jar).getURL());
            }
        }
        final ClassLoader libLoader;
        if (libs.size()>0)
        {
            __log.debug("{}: libs={}",key,libs);
            libLoader=new URLClassLoader(libs.toArray(new URL[]{}),parent)
            {
                public String toString() {return "libLoader@"+Long.toHexString(hashCode())+"-lib-"+key;}
            };
            
        }
        else
            libLoader=parent;
        
        Thread.currentThread().setContextClassLoader(libLoader);
        
        
        // Make the shared resourceBase
        List<Resource> bases = new ArrayList<Resource>();
        for (Resource wa : getLayers(node,template))
            bases.add(wa);
        if (webapp!=null)
            bases.add(webapp.getBaseResource());
        Resource baseResource = bases.size()==1?bases.get(0):new ResourceCollection(bases.toArray(new Resource[bases.size()]));
        __log.debug("{}: baseResource={}",key,baseResource);
        
        
        // Make the shared context
        TemplateContext shared = new TemplateContext(key,getDeploymentManager().getServer(),baseResource,libLoader);
        _shared.put(key,shared);

        
        // Create properties to be shared by overlay.xmls
        Map<String,Object> idMap = new HashMap<String,Object>();
        idMap.put(_serverID,getDeploymentManager().getServer());

        
        // Create the shared context for the template
        // This instance will never be start, but is used to capture the 
        // shared results of running the template and node overlay.xml files.
        // If there is a template overlay.xml, give it the chance to create the ContextHandler instance
        // otherwise create an instance ourselves
        for (Resource template_xml : getLayeredResources(TEMPLATE_XML,template,node))
        {
            __log.debug("{}: template.xml={}",key,template_xml);
            XmlConfiguration xmlc = newXmlConfiguration(template_xml.getURL(),idMap,template,null);
            xmlc.getIdMap().putAll(idMap);
            xmlc.configure(shared);
            idMap=xmlc.getIdMap();
        }
        
        shared.setIdMap(idMap);
        shared.start();
        
        return shared;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The node name (defaults to hostname)
     */
    public String getNodeName()
    {
        return _nodeName;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param nodeName Set the node name
     */
    public void setNodeName(String nodeName)
    {
        _nodeName = nodeName;
    }

    /* ------------------------------------------------------------ */
    /** Get the scanDir.
     * @return the scanDir
     */
    public File getScanDir()
    {
        return _scanDir;
    }

    /* ------------------------------------------------------------ */
    /** Set the scanDir.
     * @param scanDir the scanDir to set
     */
    public void setScanDir(File scanDir)
    {
        _scanDir = scanDir;
    }

    /* ------------------------------------------------------------ */
    /** Set the temporary directory.
     * @param tmpDir the directory for temporary files.  If null, then getScanDir()+"/tmp" is used if it exists, else the system default is used.
     */
    public void setTmpDir(File tmpDir)
    {
        _tmpDir=tmpDir;
    }

    /* ------------------------------------------------------------ */
    /** Get the temporary directory.
     * return the tmpDir.  If null, then getScanDir()+"/tmp" is used if it exists, else the system default is used.
     */
    public File getTmpDir()
    {
        return _tmpDir;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The scan interval
     * @see org.eclipse.jetty.util.Scanner#getScanInterval()
     */
    public int getScanInterval()
    {
        return _scanner.getScanInterval();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param scanInterval The scan interval
     * @see org.eclipse.jetty.util.Scanner#setScanInterval(int)
     */
    public void setScanInterval(int scanInterval)
    {
        _scanner.setScanInterval(scanInterval);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.Scanner#scan()
     */
    public void scan()
    {
        _scanner.scan();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        __log.info("Node={} Scan=",_nodeName,_scanDir);
        if (_scanDir==null || !_scanDir.exists())
            throw new IllegalStateException("!scandir");

        _scanDirURI=_scanDir.toURI().getPath();
        _scanner.setScanDepth(6); // enough for templates/name/webapps/WEB-INF/lib/foo.jar
        List<File> dirs = Arrays.asList(new File[]
                                                 {
                new File(_scanDir,WEBAPPS),
                new File(_scanDir,TEMPLATES),
                new File(_scanDir,NODES),
                new File(_scanDir,INSTANCES)
            });
        for (File file : dirs)
        {
            if (!file.exists() && !file.isDirectory())
                __log.warn("No directory: "+file.getAbsolutePath());
        }
        _scanner.setScanDirs(dirs);
        _scanner.addListener(_listener);
        _scanner.start();
        
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        _scanner.removeListener(_listener);
        _scanner.stop();
        
        if (_deploymentManager.isRunning())
        {
            for (App app: _deployed.values())
            _deploymentManager.removeApp(app);
        }
        _deployed.clear();
        
        for (Layer layer : _webapps.values())
            layer.release();
        _webapps.clear();
        for (Layer layer : _templates.values())
            layer.release();
        _templates.clear();
        if (_node!=null)
            _node.release();
        for (Layer layer : _instances.values())
            layer.release();
        _instances.clear();
        
        super.doStop();
    }
    
    /* ------------------------------------------------------------ */
    protected synchronized void updateLayers(Set<String> layerURIs)
    {
        _loading=System.currentTimeMillis();
        for (String ruri: layerURIs)
        {
            try
            {
                // Decompose the name
                File directory;
                File archive;
                File origin = new File(new URI(_scanDir.toURI()+ruri));
                String name=origin.getName();
                
                Monitor monitor = Monitor.valueOf(origin.getParentFile().getName().toUpperCase(Locale.ENGLISH));
                
                String ext=".war";
                
                // check directory vs archive 
                if (origin.isDirectory() || !origin.exists() && !ruri.toLowerCase(Locale.ENGLISH).endsWith(ext))
                {
                    // directories have priority over archives
                    directory=origin;
                    archive=new File(directory.toString()+ext);
                }
                else
                {
                    // check extension name
                    if (!ruri.toLowerCase(Locale.ENGLISH).endsWith(ext))
                        continue;

                    name=name.substring(0,name.length()-4);
                    archive=origin;
                    directory=new File(new URI(_scanDir.toURI()+ruri.substring(0,ruri.length()-4)));
                    
                    // Look to see if directory exists
                    if (directory.exists())
                    {
                        __log.info("Directory exists, ignoring change to {}",ruri);
                        continue;
                    }
                }
                
                Layer layer=null;
                
                switch(monitor)
                {
                    case WEBAPPS:
                        if (origin.exists())
                            layer=loadWebapp(name,origin);
                        else
                        {
                            removeWebapp(name);
                            if (origin==directory && archive.exists())
                                layer=loadWebapp(name,archive);
                        }
                        
                        break;
                        
                    case TEMPLATES:
                        if (origin.exists())
                            layer=loadTemplate(name,origin);
                        else 
                        {
                            removeTemplate(name);
                            if (origin==directory && archive.exists())
                                layer=loadTemplate(name,archive);
                        }
                        break;
                        
                    case NODES:
                        if (name.equalsIgnoreCase(_nodeName))
                        {
                            if (origin.exists())
                                layer=loadNode(origin);
                            else
                            {
                                removeNode();
                                if (origin==directory && archive.exists())
                                    layer=loadNode(archive);
                            }
                        }
                        break;
                        
                    case INSTANCES:
                        if (origin.exists())
                            layer=loadInstance(name,origin);
                        else
                        {
                            removeInstance(name);
                            if (origin==directory && archive.exists())
                                layer=loadInstance(name,archive);
                        }
                        break;
                        
                }
                
                if (layer!=null)
                    __log.info("loaded {}",layer.getLoadedKey());
            }
            catch(Exception e)
            {
                __log.warn(e);
            }
        }
        
        redeploy();

        // Release removed layers
        for (Layer layer : _removedLayers)
        {    
            if (layer!=null)
            {
                __log.info("unload {}",layer.getLoadedKey());
                layer.release();
            }
        }
        _removedLayers.clear();
        
        if (__log.isDebugEnabled())
        {
            System.err.println("updated:");
            System.err.println("java:"+javaRootURLContext.getRoot().dump());
            System.err.println("local:"+localContextRoot.getRoot().dump());
            if (getDeploymentManager()!=null && getDeploymentManager().getServer()!=null)
                System.err.println(getDeploymentManager().getServer().dump());
        }
    }

    /* ------------------------------------------------------------ */
    protected File tmpdir(String name,String suffix) throws IOException
    {
        File dir=_tmpDir;
        if (dir==null || !dir.isDirectory() || !dir.canWrite())
        {
            dir=new File(_scanDir,"tmp");
            if (!dir.isDirectory() || !dir.canWrite())
                dir=null;
        }
        
        File tmp = File.createTempFile(name+"_","."+suffix,dir);
        tmp=tmp.getCanonicalFile();
        if (tmp.exists())
            IO.delete(tmp);
        tmp.mkdir();
        tmp.deleteOnExit();
        return tmp;
    }

    /* ------------------------------------------------------------ */
    /**
     * Walks the defined webapps, templates, nodes and instances to 
     * determine what should be deployed, then adjust reality to match.
     */
    protected void redeploy()
    {
        Map<String,Template> templates = new ConcurrentHashMap<String,Template>();
        
        // Check for duplicate templates
        for (Template template : _templates.values())
        {
            Template other=templates.get(template.getTemplateName());
            if (other!=null)
            {
                __log.warn("Multiple Templates: {} & {}",template.getName(),other.getName());
                if (other.getName().compareToIgnoreCase(template.getName())<=0)
                    continue;
            }
            templates.put(template.getTemplateName(),template);
        }
        
        // Match webapps to templates
        for (Template template : templates.values())
        {
            String webappname=template.getClassifier();
            
            if (webappname==null)
                continue;
            
            Webapp webapp = _webapps.get(webappname);
            
            if (webapp==null)
            {
                __log.warn("No webapp found for template: {}",template.getName());
                templates.remove(template.getTemplateName());
            }
            else
            {
                template.setWebapp(webapp);
            }
        }

        // Match instance to templates and check if what needs to be deployed or undeployed.
        Set<String> deployed = new HashSet<String>();
        List<Instance> deploy = new ArrayList<Instance>();
       
        for (Instance instance : _instances.values())
        {
            Template template=templates.get(instance.getTemplateName());
            instance.setTemplate(template);
            if (template!=null)
            {
                String key=instance.getInstanceKey();
                App app = _deployed.get(key);
                if (app==null)
                    deploy.add(instance);
                else
                    deployed.add(key);
            }
        }
        
        // Look for deployed apps that need to be undeployed
        List<String> undeploy = new ArrayList<String>();
        for (String key : _deployed.keySet())
        {
            if (!deployed.contains(key))
                undeploy.add(key);
        }
        
        // Do the undeploys
        for (String key : undeploy)
        {
            App app = _deployed.remove(key);
            if (app!=null)
            {
                __log.info("Undeploy {}",key);
                _deploymentManager.removeApp(app);
            }
        }
        
        // ready the deploys
        for (Instance instance : deploy)
        {
            String key=instance.getInstanceKey();
            OverlayedApp app = new OverlayedApp(_deploymentManager,this,key,instance);
            _deployed.put(key,app);
        }

        // Remove unused Shared stuff
        Set<String> sharedKeys = new HashSet<String>(_shared.keySet());
        for (OverlayedApp app : _deployed.values())
        {
            Instance instance = app.getInstance();
            sharedKeys.remove(instance.getSharedKey());
        }
        for (String sharedKey: sharedKeys)
        {
            __log.debug("Remove "+sharedKey);
            TemplateContext shared=_shared.remove(sharedKey);
            if (shared!=null)
            {
                try
                {
                    shared.stop();
                }
                catch(Exception e)
                {
                    __log.warn(e);
                }
                shared.destroy();
            }
        }

        // Do the deploys
        for (Instance instance : deploy)
        {
            String key=instance.getInstanceKey();
            OverlayedApp app = _deployed.get(key);
            __log.info("Deploy {}",key);
            _deploymentManager.addApp(app);
        }


    }

    /* ------------------------------------------------------------ */
    protected void removeInstance(String name)
    {
        _removedLayers.add(_instances.remove(name));
    }

    /* ------------------------------------------------------------ */
    protected Instance loadInstance(String name, File origin)
        throws IOException
    {
        Instance instance=new Instance(name,origin);
        _removedLayers.add(_instances.put(name,instance));
        return instance;
    }

    /* ------------------------------------------------------------ */
    protected void removeNode()
    {
        if (_node!=null)
            _removedLayers.add(_node);
        _node=null;
    }

    /* ------------------------------------------------------------ */
    protected Node loadNode(File origin)
        throws IOException
    {
        if (_node!=null)
            _removedLayers.add(_node);
        _node=new Node(_nodeName,origin);
        return _node;
    }

    /* ------------------------------------------------------------ */
    protected void removeTemplate(String name)
    {
        _removedLayers.add(_templates.remove(name));
    }

    /* ------------------------------------------------------------ */
    protected Template loadTemplate(String name, File origin)
        throws IOException
    {
        Template template=new Template(name,origin);
        _removedLayers.add(_templates.put(name,template));
        return template;
    }

    protected void removeWebapp(String name)
    {
        _removedLayers.add(_webapps.remove(name));
    }

    /* ------------------------------------------------------------ */
    protected Webapp loadWebapp(String name, File origin)
        throws IOException
    {
        Webapp webapp = new Webapp(name,origin);
        _removedLayers.add(_webapps.put(name,webapp));
        return webapp;
    }

    /* ------------------------------------------------------------ */
    private static List<Resource> getLayers(Layer... layers)
    {
        List<Resource> resources = new ArrayList<Resource>();
        for (Layer layer: layers)
        {
            if (layer==null)
                continue;
            Resource resource = layer.getBaseResource();
            if (resource.exists())
                resources.add(resource);
        }
        return resources;
    }
    
    /* ------------------------------------------------------------ */
    private static List<Resource> getLayeredResources(String path, Layer... layers)
    {
        List<Resource> resources = new ArrayList<Resource>();
        for (Layer layer: layers)
        {
            if (layer==null)
                continue;
            Resource resource = layer.getResource(path);
            if (resource.exists())
                resources.add(resource);
        }
        return resources;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class Layer 
    {
        private final String _name;
        private final File _origin;
        private final long _loaded=_loading;
        private final Resource _resourceBase;
        private final boolean _resourceBaseIsCopy;
        
        public Layer(String name, File origin)
            throws IOException
        {
            super();
            _name = name;
            _origin = origin;
            
            Resource resource = Resource.newResource(origin.toURI());
            
            if (resource.isDirectory())
            {
                if (_copydir)
                {
                    File tmp=tmpdir(name,"extract");
                    __log.info("Extract {} to {}",origin,tmp);
                    IO.copyDir(origin,tmp);
                    _resourceBase=Resource.newResource(tmp.toURI());
                    _resourceBaseIsCopy=true;
                }
                else
                {
                    _resourceBase=resource;
                    _resourceBaseIsCopy=false;
                }
            }
            else 
            {
                Resource jar = JarResource.newJarResource(resource);
                File tmp=tmpdir(name,"extract");
                __log.info("Extract {} to {}",jar,tmp);
                jar.copyTo(tmp);
                _resourceBase=Resource.newResource(tmp.toURI());
                _resourceBaseIsCopy=true;
            }    
        }
        
        public String getName()
        {
            return _name;
        }
        
        public File getOrigin()
        {
            return _origin;
        }
        
        public long getLoaded()
        {
            return _loaded;
        }

        public Resource getBaseResource()
        {
            return _resourceBase;
        } 

        public Resource getResource(String path)
        {
            try
            {
                return getBaseResource().addPath(path);
            }
            catch(Exception e)
            {
                __log.warn(e);
            }
            return null;
        }
        
        public String getLoadedKey()
        {
            return _name+"@"+_loaded;
        }
        
        public void release()
        {
            if (_resourceBaseIsCopy)
            {
                try
                {
                    File file = _resourceBase.getFile();
                    if (file!=null)
                        IO.delete(file);
                }
                catch(Exception e)
                {
                    __log.warn(e);
                }
            }
        }
        
        public String toString()
        {
            return getLoadedKey();
        }
    }

    class Webapp extends Layer
    {
        public Webapp(String name, File origin) throws IOException
        {
            super(name,origin);
        }
    }
    
    class Overlay extends Layer
    {
        public Overlay(String name, File origin) throws IOException
        {
            super(name,origin);
        }
    
        public Resource getContext()
        {
            return getResource(OVERLAY_XML);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class Node extends Overlay
    {
        public Node(String name, File origin) throws IOException
        {
            super(name,origin);
        }
    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ClassifiedOverlay extends Overlay
    {
        private final String _templateName;
        private final String _classifier;
        
        public ClassifiedOverlay(String name, File origin) throws IOException
        {
            super(name,origin);
            
            int l=1;
            int e=name.indexOf('=');
            if (e<0)
            {
                l=2;
                e=name.indexOf("--");
            }
            _templateName=e>=0?name.substring(0,e):name;
            _classifier=e>=0?name.substring(e+l):null;
        }

        public String getTemplateName()
        {
            return _templateName;
        }

        public String getClassifier()
        {
            return _classifier;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class Template extends ClassifiedOverlay
    {
        private Webapp _webapp;
        
        public Webapp getWebapp()
        {
            return _webapp;
        }

        public void setWebapp(Webapp webapp)
        {
            _webapp = webapp;
        }

        public Template(String name, File origin) throws IOException
        {
            super(name,origin);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class Instance extends ClassifiedOverlay
    {
        Template _template;
        String _sharedKey;
        
        public Instance(String name, File origin) throws IOException
        {
            super(name,origin);
            if (getClassifier()==null)
                throw new IllegalArgumentException("Instance without '=':"+name);
        }

        public void setSharedKey(String key)
        {
            _sharedKey=key;
        }

        public String getSharedKey()
        {
            return _sharedKey;
        }

        public void setTemplate(Template template)
        {
            _template=template;
        }

        public Template getTemplate()
        {
            return _template;
        }
        
        public String getInstanceKey()
        {
            return 
            (_template.getWebapp()==null?"":_template.getWebapp().getLoadedKey())+"|"+
            _template.getLoadedKey()+"|"+
            (_node==null?"":_node.getLoadedKey())+"|"+
            getLoadedKey();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    static class OverlayedApp extends App
    {
        final Instance _instance;
        
        public OverlayedApp(DeploymentManager manager, AppProvider provider, String originId, Instance instance)
        {
            super(manager,provider,originId);
            _instance=instance;
        }
        
        public Instance getInstance()
        {
            return _instance;
        }
    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final class ParameterExpander implements ServletContextListener
    {
        private final Map<String, String> _params;
        private final ContextHandler _ctx;

        private ParameterExpander(Map<String, String> params, ContextHandler ctx)
        {
            _params = params;
            _ctx = ctx;
        }

        public void contextInitialized(ServletContextEvent sce)
        {
            Enumeration<String> e=_ctx.getInitParameterNames();
            while (e.hasMoreElements())
            {
                String name = e.nextElement();
                _ctx.setInitParameter(name,expandParameter(_ctx.getInitParameter(name)));
            }
            
            ServletHandler servletHandler = _ctx.getChildHandlerByClass(ServletHandler.class);
            if (servletHandler!=null)
            {
                List<Holder<?>> holders = new ArrayList<Holder<?>>();
                if (servletHandler.getFilters()!=null)
                    holders.addAll(Arrays.asList(servletHandler.getFilters()));
                if (servletHandler.getHandler()!=null)
                    holders.addAll(Arrays.asList(servletHandler.getServlets()));
                for (Holder<?> holder: holders)
                {
                    e=holder.getInitParameterNames();
                    while (e.hasMoreElements())
                    {
                        String name = e.nextElement();
                        holder.setInitParameter(name,expandParameter(holder.getInitParameter(name)));
                    }
                }
            }
        }

        private String expandParameter(String value)
        {
            int i=0;
            while (true)
            {
                int open=value.indexOf("${",i);
                if (open<0)
                    return value;
                int close=value.indexOf("}",open);
                if (close<0)
                    return value;
                
                String param = value.substring(open+2,close);
                if (_params.containsKey(param))
                {
                    String tmp=value.substring(0,open)+_params.get(param);
                    i=tmp.length();
                    value=tmp+value.substring(close+1);
                }
                else
                    i=close+1;
            }
        }

        public void contextDestroyed(ServletContextEvent sce)
        {
        }
    }
}

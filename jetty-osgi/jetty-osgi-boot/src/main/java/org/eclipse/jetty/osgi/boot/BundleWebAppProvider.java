package org.eclipse.jetty.osgi.boot;

import java.io.File;
import java.net.URL;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;


import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper;
import org.eclipse.jetty.osgi.boot.internal.webapp.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.internal.webapp.OSGiWebappClassLoader;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.packageadmin.PackageAdmin;



/**
 * BundleWebAppProvider
 *
 *
 */
public class BundleWebAppProvider extends AbstractLifeCycle implements AppProvider
{     
    private static final Logger LOG = Log.getLogger(BundleWebAppProvider.class);
    
    public static final String WATERMARK = "o.e.j.o.b.BWAP"; //indicates this class created the webapp

    public static String __defaultConfigurations[] = {
                                                            "org.eclipse.jetty.osgi.boot.OSGiWebInfConfiguration",
                                                            "org.eclipse.jetty.webapp.WebXmlConfiguration",
                                                            "org.eclipse.jetty.osgi.boot.OSGiMetaInfConfiguration",
                                                            "org.eclipse.jetty.webapp.FragmentConfiguration",
                                                            "org.eclipse.jetty.webapp.JettyWebXmlConfiguration"//,
                                                            //"org.eclipse.jetty.osgi.boot.jsp.TagLibOSGiConfiguration"                            
                                                     };
   
    private DeploymentManager _deploymentManager;
    
    private Map<String, App> _appMap = new HashMap<String, App>();
    
    private Map<Bundle, App> _bundleMap = new HashMap<Bundle, App>();

    private boolean _parentLoaderPriority;

    private String _defaultsDescriptor;

    private boolean _extractWars = true; //See WebAppContext.extractWars

    private String _tldBundles;

    private String[] _configurationClasses;
    
    private ServerInstanceWrapper _serverWrapper;
    
    private ServiceRegistration _serviceReg;
    
    
    public static void setDefaultConfigurations (String[] defaultConfigs)
    {
        __defaultConfigurations = defaultConfigs;
    }
    
    public static String[] getDefaultConfigurations ()
    {
        return __defaultConfigurations;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * BundleApp
     *
     *
     */
    public class BundleApp extends App
    {
        private Bundle _bundle;
        private String _contextPath;
        private String _webAppPath;
        private WebAppContext _webApp;
        private Dictionary _properties;

        public BundleApp(DeploymentManager manager, AppProvider provider, Bundle bundle, String originId)
        {
            super(manager, provider, originId);
            _properties = bundle.getHeaders();
            _bundle = bundle;
        }
        
        public BundleApp(DeploymentManager manager, AppProvider provider, Bundle bundle, Dictionary properties, String originId)
        {
            super(manager, provider, originId);
            _properties = properties;
            _bundle = bundle;
        }
        
        public void setWebAppContext (WebAppContext webApp)
        {
            _webApp = webApp;
        }
        
        
        public Bundle getBundle()
        {
            return _bundle;
        }

        public String getContextPath()
        {
            return _contextPath;
        }

        public void setContextPath(String contextPath)
        {
            this._contextPath = contextPath;
        }

        public String getBundlePath()
        {
            return _webAppPath;
        }

        public void setWebAppPath(String path)
        {
            this._webAppPath = path;
        }
        
        
        public WebAppContext getWebAppContext()
        throws Exception
        {
            if (_webApp != null)
            {
                configureWebApp();
                return _webApp;
            }
            
            createWebApp();
            return _webApp;
        }
        
        
        protected void createWebApp ()
        throws Exception
        {
            _webApp = newWebApp();
            configureWebApp();
        }
        
        protected WebAppContext newWebApp ()
        {
           WebAppContext webApp = new WebAppContext();
           webApp.setAttribute(WATERMARK, WATERMARK);
           return webApp;
        }


        public void configureWebApp() 
        throws Exception
        {           
            _webApp.setContextPath(_contextPath);

            String overrideBundleInstallLocation = (String)_properties.get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
            File bundleInstallLocation = 
                (overrideBundleInstallLocation == null 
                        ? BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(_bundle) 
                        : new File(overrideBundleInstallLocation));
            URL url = null;

            //if the path wasn't set or it was ., then it is the root of the bundle's installed location
            if (_webAppPath == null || _webAppPath.length() == 0 || ".".equals(_webAppPath))
            {
                url = bundleInstallLocation.toURI().toURL();
            }
            else
            {
                //Get the location of the root of the webapp inside the installed bundle
                if (_webAppPath.startsWith("/") || _webAppPath.startsWith("file:"))
                {
                    url = new File(_webAppPath).toURI().toURL();
                }
                else if (bundleInstallLocation != null && bundleInstallLocation.isDirectory())
                {
                    url = new File(bundleInstallLocation, _webAppPath).toURI().toURL();
                }
                else if (bundleInstallLocation != null)
                {
                    Enumeration<URL> urls = BundleFileLocatorHelperFactory.getFactory().getHelper().findEntries(_bundle, _webAppPath);
                    if (urls != null && urls.hasMoreElements())
                        url = urls.nextElement();
                }
            }

            if (url == null)
            { 
                throw new IllegalArgumentException("Unable to locate " + _webAppPath
                                                   + " in "
                                                   + (bundleInstallLocation != null ? bundleInstallLocation.getAbsolutePath() : "unlocated bundle '" + _bundle.getSymbolicName()+ "'"));
            }

            // converts bundleentry: protocol if necessary
            _webApp.setWar(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(url).toString());

            // Set up what has been configured on the provider
            _webApp.setParentLoaderPriority(isParentLoaderPriority());
            _webApp.setExtractWAR(isExtract());
            if (getConfigurationClasses() != null)
                _webApp.setConfigurationClasses(getConfigurationClasses());
            else
                _webApp.setConfigurationClasses(__defaultConfigurations);
            
            for (int i=0;i<__defaultConfigurations.length;i++)
                System.err.println(__defaultConfigurations[i]);

            if (getDefaultsDescriptor() != null)
                _webApp.setDefaultsDescriptor(getDefaultsDescriptor());

            //Set up configuration from manifest headers
            //extra classpath
            String tmp = (String)_properties.get(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH);
            if (tmp != null)
                _webApp.setExtraClasspath(tmp);

            //web.xml
            tmp = (String)_properties.get(OSGiWebappConstants.JETTY_WEB_XML_PATH);
            if (tmp != null && tmp.trim().length() != 0)
            {
                File webXml = getFile (tmp, bundleInstallLocation);
                if (webXml != null && webXml.exists())
                    _webApp.setDescriptor(webXml.getAbsolutePath());
            }

            //webdefault.xml
            tmp = (String)_properties.get(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH);
            if (tmp != null)
            {
                File defaultWebXml = getFile (tmp, bundleInstallLocation);
                if (defaultWebXml != null && defaultWebXml.exists())
                    _webApp.setDefaultsDescriptor(defaultWebXml.getAbsolutePath());
            }

            //Handle Require-TldBundle
            //This is a comma separated list of names of bundles that contain tlds that this webapp uses.
            //We add them to the webapp classloader.
            String requireTldBundles = (String)_properties.get(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
            String pathsToTldBundles = getPathsToRequiredBundles(requireTldBundles);


            // make sure we provide access to all the jetty bundles by going
            // through this bundle.
            OSGiWebappClassLoader webAppLoader = new OSGiWebappClassLoader(_serverWrapper.getParentClassLoaderForWebapps(), _webApp, _bundle);

            if (pathsToTldBundles != null)
                webAppLoader.addClassPath(pathsToTldBundles);
            _webApp.setClassLoader(webAppLoader);


            // apply any META-INF/context.xml file that is found to configure
            // the webapp first
            applyMetaInfContextXml();

            // pass the value of the require tld bundle so that the TagLibOSGiConfiguration
            // can pick it up.
            _webApp.setAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, requireTldBundles);

            //Set up some attributes
            // rfc66
            _webApp.setAttribute(OSGiWebappConstants.RFC66_OSGI_BUNDLE_CONTEXT, _bundle.getBundleContext());

            // spring-dm-1.2.1 looks for the BundleContext as a different attribute.
            // not a spec... but if we want to support
            // org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext
            // then we need to do this to:
            _webApp.setAttribute("org.springframework.osgi.web." + BundleContext.class.getName(), _bundle.getBundleContext());

            // also pass the bundle directly. sometimes a bundle does not have a
            // bundlecontext.
            // it is still useful to have access to the Bundle from the servlet
            // context.
            _webApp.setAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE, _bundle);
        }

        protected String getPathsToRequiredBundles (String requireTldBundles)
        throws Exception
        {
            if (requireTldBundles == null) return null;

            ServiceReference ref = _bundle.getBundleContext().getServiceReference(org.osgi.service.packageadmin.PackageAdmin.class.getName());
            PackageAdmin packageAdmin = (ref == null) ? null : (PackageAdmin)_bundle.getBundleContext().getService(ref);
            if (packageAdmin == null)
                throw new IllegalStateException("Unable to get PackageAdmin reference to locate required Tld bundles");

            StringBuilder paths = new StringBuilder();         
            String[] symbNames = requireTldBundles.split(", ");

            for (String symbName : symbNames)
            {
                Bundle[] bs = packageAdmin.getBundles(symbName, null);
                if (bs == null || bs.length == 0) 
                { 
                    throw new IllegalArgumentException("Unable to locate the bundle '" + symbName
                                                       + "' specified by "
                                                       + OSGiWebappConstants.REQUIRE_TLD_BUNDLE
                                                       + " in manifest of "
                                                       + (_bundle == null ? "unknown" : _bundle.getSymbolicName())); 
                }

                File f = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bs[0]);
                if (paths.length() > 0) paths.append(", ");
                paths.append(f.toURI().toURL().toString());
                LOG.debug("getPathsToRequiredBundles: bundle path=" + bs[0].getLocation() + " uri=" + f.toURI());
            }

            return paths.toString();
        }
        
        
        protected void applyMetaInfContextXml()
        throws Exception
        {
            if (_bundle == null) return;
            if (_webApp == null) return;

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            LOG.debug("Context classloader = " + cl);
            try
            {
               
                Thread.currentThread().setContextClassLoader(_webApp.getClassLoader());

                // find if there is a META-INF/context.xml file
                URL contextXmlUrl = _bundle.getEntry("/META-INF/jetty-webapp-context.xml");
                if (contextXmlUrl == null) return;

                // Apply it just as the standard jetty ContextProvider would do
                LOG.info("Applying " + contextXmlUrl + " to " + _webApp);

                XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXmlUrl);
                HashMap properties = new HashMap();
                properties.put("Server", getDeploymentManager().getServer());
                xmlConfiguration.getProperties().putAll(properties);
                xmlConfiguration.configure(_webApp);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(cl);
            }
        }
        
        private File getFile (String file, File bundleInstall)
        {
            if (file == null)
                return null;
            
            if (file.startsWith("/") || file.startsWith("file:/"))
                return new File(file);
            else
                return new File(bundleInstall, file);
        }
    }
    
    
    public BundleWebAppProvider (ServerInstanceWrapper wrapper)
    {
        _serverWrapper = wrapper;
    }
    
    
    
    
    /* ------------------------------------------------------------ */
    protected void doStart() throws Exception
    {
        //register as an osgi service, advertising the name of the jetty Server instance we are related to
        Dictionary<String,String> properties = new Hashtable<String,String>();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, _serverWrapper.getManagedServerName());
        _serviceReg = FrameworkUtil.getBundle(this.getClass()).getBundleContext().registerService(this.getClass().getName(), this, properties);
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        //unregister ourselves
        if (_serviceReg != null)
        {
            try
            {
                _serviceReg.unregister();
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
        super.doStop();
    }
    
    
    /* ------------------------------------------------------------ */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }

    /* ------------------------------------------------------------ */
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get the parentLoaderPriority.
     * 
     * @return the parentLoaderPriority
     */
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the parentLoaderPriority.
     * 
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the defaultsDescriptor.
     * 
     * @return the defaultsDescriptor
     */
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the defaultsDescriptor.
     * 
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }
    
    
    /* ------------------------------------------------------------ */
    public boolean isExtract()
    {
        return _extractWars;
    }
    
    
    /* ------------------------------------------------------------ */
    public void setExtract(boolean extract)
    {
        _extractWars = extract;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param tldBundles Comma separated list of bundles that contain tld jars
     *            that should be setup on the jetty instances created here.
     */
    public void setTldBundles(String tldBundles)
    {
        _tldBundles = tldBundles;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return The list of bundles that contain tld jars that should be setup on
     *         the jetty instances created here.
     */
    public String getTldBundles()
    {
        return _tldBundles;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _configurationClasses = configurations == null ? null : (String[]) configurations.clone();
    }

    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public String[] getConfigurationClasses()
    {
        return _configurationClasses;
    }

    /* ------------------------------------------------------------ */
    public void setServerInstanceWrapper(ServerInstanceWrapper wrapper)
    {
        _serverWrapper = wrapper;
    }
    
    
    /* ------------------------------------------------------------ */
    public ContextHandler createContextHandler(App app) throws Exception
    {
        if (app == null)
            return null;
        if (!(app instanceof BundleApp))
            throw new IllegalStateException(app+" is not a BundleApp");
        
        //Create a WebAppContext suitable to deploy in OSGi
        return ((BundleApp)app).getWebAppContext();
    }

    
    
    /* ------------------------------------------------------------ */
    /**
     * A bundle has been added that could be a webapp 
     * @param bundle
     */
    public boolean bundleAdded (Bundle bundle) 
    {
        if (bundle == null)
            return false;

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_serverWrapper.getParentClassLoaderForWebapps());
        try 
        {
            Dictionary headers = bundle.getHeaders();

            //does the bundle have a OSGiWebappConstants.JETTY_WAR_FOLDER_PATH 
            if (headers.get(OSGiWebappConstants.JETTY_WAR_FOLDER_PATH) != null)
            {
                String base = (String)headers.get(OSGiWebappConstants.JETTY_WAR_FOLDER_PATH);
                String contextPath = getContextPath(bundle);
                String originId = getOriginId(bundle, base);

                BundleApp app = new BundleApp(_deploymentManager, this, bundle, originId);
                app.setWebAppPath(base);
                app.setContextPath(contextPath);
                _appMap.put(originId,app);
                _bundleMap.put(bundle, app);
                _deploymentManager.addApp(app);

                return true;
            }


            //does the bundle have a WEB-INF/web.xml
            if (bundle.getEntry("/WEB-INF/web.xml") != null)
            {
                String base = ".";
                String contextPath = getContextPath(bundle);
                String originId = getOriginId(bundle, base);

                BundleApp app = new BundleApp(_deploymentManager, this, bundle, originId);
                app.setContextPath(contextPath);
                app.setWebAppPath(base);
                _appMap.put(originId,app);
                _bundleMap.put(bundle, app);
                _deploymentManager.addApp(app);
                return true;
            }

            //does the bundle define a OSGiWebappConstants.RFC66_WEB_CONTEXTPATH
            if (headers.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH) != null)
            {
                //Could be a static webapp with no web.xml
                String base = ".";
                String contextPath = (String)headers.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
                String originId = getOriginId(bundle,base);

                BundleApp app = new BundleApp(_deploymentManager, this, bundle, originId);
                app.setContextPath(contextPath);
                app.setWebAppPath(base);
                _appMap.put(originId,app);
                _bundleMap.put(bundle, app);
                _deploymentManager.addApp(app);
                return true;
            }

            return false;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    
    /* ------------------------------------------------------------ */
    /** 
     * Bundle has been removed. If it was a webapp we deployed, undeploy it.
     * @param bundle
     * 
     * @return true if this was a webapp we had deployed, false otherwise
     */
    public boolean bundleRemoved (Bundle bundle)
    {
        App app = _bundleMap.remove(bundle);
        if (app != null)
        {
            _appMap.remove(app.getOriginId());
            _deploymentManager.removeApp(app);
            return true;
        }
        return false;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * A webapp that was deployed as an osgi service has been added,
     * and we want to deploy it.
     * 
     * @param webApp
     */
    public void serviceAdded (ServiceReference serviceRef, WebAppContext webApp)
    {   
        Dictionary properties = new Hashtable<String,String>();
        
        String contextPath = (String)serviceRef.getProperty(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
        if (contextPath == null)
            contextPath = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_CONTEXT_PATH);
     
        String base = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_WAR_FOLDER_PATH);
        if (base == null)
            base = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_WAR);
        
        String webdefaultXml = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH);
        if (webdefaultXml == null)
            webdefaultXml = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_DEFAULT_WEB_XML_PATH);
        if (webdefaultXml != null)
            properties.put(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH, webdefaultXml);

        String webXml = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_WEB_XML_PATH);
        if (webXml == null)
            webXml = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_WEB_XML_PATH);
        if (webXml != null)
            properties.put(OSGiWebappConstants.JETTY_WEB_XML_PATH, webXml);

        String extraClassPath = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH);
        if (extraClassPath == null)
            extraClassPath = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_EXTRA_CLASSPATH);
        if (extraClassPath != null)
            properties.put(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH, extraClassPath);

        String bundleInstallOverride = (String)serviceRef.getProperty(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        if (bundleInstallOverride == null)
            bundleInstallOverride = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        if (bundleInstallOverride != null)
            properties.put(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE, bundleInstallOverride);

        String  requiredTlds = (String)serviceRef.getProperty(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
        if (requiredTlds == null)
            requiredTlds = (String)serviceRef.getProperty(OSGiWebappConstants.SERVICE_PROP_REQUIRE_TLD_BUNDLE);
        if (requiredTlds != null)
            properties.put(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, requiredTlds);

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_serverWrapper.getParentClassLoaderForWebapps());
        try
        {
            String originId = getOriginId(serviceRef.getBundle(), base);
            BundleApp app = new BundleApp(_deploymentManager, this, serviceRef.getBundle(), properties, originId);
            app.setContextPath(contextPath);
            app.setWebAppPath(base);
            app.setWebAppContext(webApp); //set the pre=made webapp instance
            _appMap.put(originId,app);
            _bundleMap.put(serviceRef.getBundle(), app);
            _deploymentManager.addApp(app);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(cl); 
        }
    }
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param webApp
     */
    public boolean serviceRemoved (ServiceReference serviceRef, WebAppContext webApp)
    {
        App app = _bundleMap.remove(serviceRef.getBundle());
        if (app != null)
        {
            _appMap.remove(app.getOriginId());
            _deploymentManager.removeApp(app);
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    private static String getContextPath(Bundle bundle)
    {
        Dictionary<?, ?> headers = bundle.getHeaders();
        String contextPath = (String) headers.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
        if (contextPath == null)
        {
            // extract from the last token of the bundle's location:
            // (really ?could consider processing the symbolic name as an alternative
            // the location will often reflect the version.
            // maybe this is relevant when the file is a war)
            String location = bundle.getLocation();
            String toks[] = location.replace('\\', '/').split("/");
            contextPath = toks[toks.length - 1];
            // remove .jar, .war etc:
            int lastDot = contextPath.lastIndexOf('.');
            if (lastDot != -1)
                contextPath = contextPath.substring(0, lastDot);
        }
        if (!contextPath.startsWith("/"))
            contextPath = "/" + contextPath;
 
        return contextPath;
    }
    
    
    /* ------------------------------------------------------------ */
    private static String getOriginId(Bundle contributor, String path)
    {
        return contributor.getSymbolicName() + "-" + contributor.getVersion().toString() + (path.startsWith("/") ? path : "/" + path);
    }
    
}

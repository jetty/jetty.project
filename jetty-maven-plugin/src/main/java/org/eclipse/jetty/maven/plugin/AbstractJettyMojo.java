//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin;


import java.io.File;
import java.io.FileInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;



/**
 * AbstractJettyMojo
 *
 *
 */
public abstract class AbstractJettyMojo extends AbstractMojo
{
  
    
    /**
     * Whether or not to include dependencies on the plugin's classpath with &lt;scope&gt;provided&lt;/scope&gt;
     * Use WITH CAUTION as you may wind up with duplicate jars/classes.
     * 
     * @since jetty-7.5.2
     * @parameter  default-value="false"
     */
    protected boolean useProvidedScope;
    
    
    /**
     * List of goals that are NOT to be used
     * 
     * @since jetty-7.5.2
     * @parameter
     */
    protected String[] excludedGoals;
    

    
    /**
     * List of connectors to use. If none are configured
     * then the default is a single SelectChannelConnector at port 8080. You can
     * override this default port number by using the system property jetty.port
     * on the command line, eg:  mvn -Djetty.port=9999 jetty:run. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file. 
     * 
     * @parameter 
     */
    protected Connector[] connectors;
    
    
    /**
     * List of other contexts to set up. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file. 
     * Optional.
     * 
     * 
     * @parameter
     */
    protected ContextHandler[] contextHandlers;
    
    
    /**
     * List of security realms to set up. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file. 
     * Optional.
     * 
     * 
     * @parameter
     */
    protected LoginService[] loginServices;
    


    /**
     * A RequestLog implementation to use for the webapp at runtime.
     * Consider using instead the &lt;jettyXml&gt; element to specify external jetty xml config file. 
     * Optional.
     * 
     *
     * @parameter
     */
    protected RequestLog requestLog;
    
    

    /**
     * An instance of org.eclipse.jetty.webapp.WebAppContext that represents the webapp.
     * Use any of its setters to configure the webapp. This is the preferred and most
     * flexible method of configuration, rather than using the (deprecated) individual
     * parameters like "tmpDirectory", "contextPath" etc.
     * 
     * @parameter alias="webAppConfig"
     */
    protected JettyWebAppContext webApp;



    /**
     * The context path for the webapp. Defaults to the
     * name of the webapp's artifact.
     *
     * @deprecated Use &lt;webApp&gt;&lt;contextPath&gt; instead.
     * @parameter expression="/${project.artifactId}"
     * @required
     * @readonly
     */
    protected String contextPath;


    /**
     * The temporary directory to use for the webapp.
     * Defaults to target/tmp.  
     *
     * @deprecated Use %lt;webApp&gt;&lt;tempDirectory&gt; instead.
     * @parameter expression="${project.build.directory}/tmp"
     * @required
     * @readonly
     */
    protected File tmpDirectory;



    /**
     * The interval in seconds to scan the webapp for changes 
     * and restart the context if necessary. Ignored if reload
     * is enabled. Disabled by default.
     * 
     * @parameter expression="${jetty.scanIntervalSeconds}" default-value="0"
     * @required
     */
    protected int scanIntervalSeconds;
    
    
    /**
     * reload can be set to either 'automatic' or 'manual'
     *
     * if 'manual' then the context can be reloaded by a linefeed in the console
     * if 'automatic' then traditional reloading on changed files is enabled.
     * 
     * @parameter expression="${jetty.reload}" default-value="automatic"
     */
    protected String reload;
    
    /**
     * File containing system properties to be set before execution
     * 
     * Note that these properties will NOT override System properties
     * that have been set on the command line, by the JVM, or directly 
     * in the POM via systemProperties. Optional.
     * 
     * @parameter expression="${jetty.systemPropertiesFile}"
     */
    protected File systemPropertiesFile;

    /**
     * System properties to set before execution. 
     * Note that these properties will NOT override System properties 
     * that have been set on the command line or by the JVM. They WILL 
     * override System properties that have been set via systemPropertiesFile.
     * Optional.
     * @parameter
     */
    protected SystemProperties systemProperties;
    
    
    
    /**
     * Comma separated list of a jetty xml configuration files whose contents 
     * will be applied before any plugin configuration. Optional.
     * 
     * 
     * @parameter alias="jettyConfig"
     */
    protected String jettyXml;
    
    
    /**
     * Port to listen to stop jetty on executing -DSTOP.PORT=&lt;stopPort&gt; 
     * -DSTOP.KEY=&lt;stopKey&gt; -jar start.jar --stop
     * 
     * @parameter
     */
    protected int stopPort;
    
    /**
     * Key to provide when stopping jetty on executing java -DSTOP.KEY=&lt;stopKey&gt; 
     * -DSTOP.PORT=&lt;stopPort&gt; -jar start.jar --stop
     * 
     * @parameter
     */
    protected String stopKey;

    /**
     * <p>
     * Determines whether or not the server blocks when started. The default
     * behavior (daemon = false) will cause the server to pause other processes
     * while it continues to handle web requests. This is useful when starting the
     * server with the intent to work with it interactively.
     * </p><p>
     * Often, it is desirable to let the server start and continue running subsequent
     * processes in an automated build environment. This can be facilitated by setting
     * daemon to true.
     * </p>
     * 
     * @parameter expression="${jetty.daemon}" default-value="false"
     */
    protected boolean daemon;
    
    /**  
     * Skip this mojo execution.
     * 
     * @parameter expression="${jetty.skip}" default-value="false"
     */
    protected boolean skip;

    
    /**
     * Location of a context xml configuration file whose contents
     * will be applied to the webapp AFTER anything in &lt;webApp&gt;.Optional.
     * 
     * 
     * @parameter alias="webAppXml"
     */
    protected String contextXml;


    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @readonly
     */
    protected MavenProject project;

    
    /**
     * The artifacts for the project.
     * 
     * @parameter expression="${project.artifacts}"
     * @readonly
     */
    protected Set projectArtifacts;
    
    
    /** 
     * @parameter expression="${mojoExecution}" 
     * @readonly
     */
    private org.apache.maven.plugin.MojoExecution execution;
    
    

    /**
     * The artifacts for the plugin itself.
     * 
     * @parameter expression="${plugin.artifacts}"
     * @readonly
     */
    private List pluginArtifacts;
    
    
    
    /**
     * A wrapper for the Server object
     */
    protected JettyServer server;
    
    /**
     * A scanner to check for changes to the webapp
     */
    protected Scanner scanner;
    
    /**
     *  List of files and directories to scan
     */
    protected ArrayList<File> scanList;
    
    /**
     * List of Listeners for the scanner
     */
    protected ArrayList<Scanner.BulkListener> scannerListeners;
    
    
    /**
     * A scanner to check ENTER hits on the console
     */
    protected Thread consoleScanner;

    
    public String PORT_SYSPROPERTY = "jetty.port";
    

    public abstract void restartWebApp(boolean reconfigureScanner) throws Exception;

    public abstract void checkPomConfiguration() throws MojoExecutionException;
    
    public abstract void configureScanner () throws MojoExecutionException;
    

    


    public void execute() throws MojoExecutionException, MojoFailureException
    {
        getLog().info("Configuring Jetty for project: " + getProject().getName());
        if (skip)
        {
            getLog().info("Skipping Jetty start: jetty.skip==true");
            return;
        }

        if (isExcluded(execution.getMojoDescriptor().getGoal()))
        {
            getLog().info("The goal \""+execution.getMojoDescriptor().getFullGoalName()+
                          "\" has been made unavailable for this web application by an <excludedGoal> configuration.");
            return;
        }
        
        configurePluginClasspath();
        PluginLog.setLog(getLog());
        checkPomConfiguration();
        startJetty();
    }
    
    
    public void configurePluginClasspath() throws MojoExecutionException
    {  
        //if we are configured to include the provided dependencies on the plugin's classpath
        //(which mimics being on jetty's classpath vs being on the webapp's classpath), we first
        //try and filter out ones that will clash with jars that are plugin dependencies, then
        //create a new classloader that we setup in the parent chain.
        if (useProvidedScope)
        {
            try
            {
                List<URL> provided = new ArrayList<URL>();
                URL[] urls = null;
               
                for ( Iterator<Artifact> iter = projectArtifacts.iterator(); iter.hasNext(); )
                {                   
                    Artifact artifact = iter.next();
                    if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()) && !isPluginArtifact(artifact))
                    {
                        provided.add(artifact.getFile().toURI().toURL());
                        if (getLog().isDebugEnabled()) { getLog().debug("Adding provided artifact: "+artifact);}
                    }
                }

                if (!provided.isEmpty())
                {
                    urls = new URL[provided.size()];
                    provided.toArray(urls);
                    URLClassLoader loader  = new URLClassLoader(urls, getClass().getClassLoader());
                    Thread.currentThread().setContextClassLoader(loader);
                    getLog().info("Plugin classpath augmented with <scope>provided</scope> dependencies: "+Arrays.toString(urls));
                }
            }
            catch (MalformedURLException e)
            {
                throw new MojoExecutionException("Invalid url", e);
            }
        }
    }
    
    
    public boolean isPluginArtifact(Artifact artifact)
    {
        if (pluginArtifacts == null || pluginArtifacts.isEmpty())
            return false;
        
        boolean isPluginArtifact = false;
        for (Iterator<Artifact> iter = pluginArtifacts.iterator(); iter.hasNext() && !isPluginArtifact; )
        {
            Artifact pluginArtifact = iter.next();
            if (getLog().isDebugEnabled()) { getLog().debug("Checking "+pluginArtifact);}
            if (pluginArtifact.getGroupId().equals(artifact.getGroupId()) && pluginArtifact.getArtifactId().equals(artifact.getArtifactId()))
                isPluginArtifact = true;
        }
        
        return isPluginArtifact;
    }

    public void finishConfigurationBeforeStart() throws Exception
    {
        HandlerCollection contexts = (HandlerCollection)server.getChildHandlerByClass(ContextHandlerCollection.class);
        if (contexts==null)
            contexts = (HandlerCollection)server.getChildHandlerByClass(HandlerCollection.class);
        
        for (int i=0; (this.contextHandlers != null) && (i < this.contextHandlers.length); i++)
        {
            contexts.addHandler(this.contextHandlers[i]);
        }
    }

   
   
    
    public void applyJettyXml() throws Exception
    {
        if (getJettyXmlFiles() == null)
            return;
        
        for ( File xmlFile : getJettyXmlFiles() )
        {
            getLog().info( "Configuring Jetty from xml configuration file = " + xmlFile.getCanonicalPath() );        
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.toURL(xmlFile));
            xmlConfiguration.configure(this.server);
        }
    }



    public void startJetty () throws MojoExecutionException
    {
        try
        {
            getLog().debug("Starting Jetty Server ...");

            printSystemProperties();
            this.server = new JettyServer();
            setServer(this.server);

            //apply any config from a jetty.xml file first which is able to
            //be overwritten by config in the pom.xml
            applyJettyXml ();

          

            // if the user hasn't configured their project's pom to use a
            // different set of connectors,
            // use the default
            Connector[] connectors = this.server.getConnectors();
            if (connectors == null|| connectors.length == 0)
            {
                //try using ones configured in pom
                this.server.setConnectors(this.connectors);

                connectors = this.server.getConnectors();
                if (connectors == null || connectors.length == 0)
                {
                    //if a SystemProperty -Djetty.port=<portnum> has been supplied, use that as the default port
                    this.connectors = new Connector[] { this.server.createDefaultConnector(server, System.getProperty(PORT_SYSPROPERTY, null)) };
                    this.server.setConnectors(this.connectors);
                }
            }


            //set up a RequestLog if one is provided
            if (this.requestLog != null)
                getServer().setRequestLog(this.requestLog);

            //set up the webapp and any context provided
            this.server.configureHandlers();
            configureWebApplication();
            this.server.addWebApplication(webApp);

            // set up security realms
            for (int i = 0; (this.loginServices != null) && i < this.loginServices.length; i++)
            {
                getLog().debug(this.loginServices[i].getClass().getName() + ": "+ this.loginServices[i].toString());
                getServer().addBean(this.loginServices[i]);
            }

            //do any other configuration required by the
            //particular Jetty version
            finishConfigurationBeforeStart();

            // start Jetty
            this.server.start();

            getLog().info("Started Jetty Server");
            
            if(stopPort>0 && stopKey!=null)
            {
                Monitor monitor = new Monitor(stopPort, stopKey, new Server[]{server}, !daemon);
                monitor.start();
            }
            
            // start the scanner thread (if necessary) on the main webapp
            configureScanner ();
            startScanner();
            
            // start the new line scanner thread if necessary
            startConsoleScanner();

            // keep the thread going if not in daemon mode
            if (!daemon )
            {
                server.join();
            }
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Failure", e);
        }
        finally
        {
            if (!daemon )
            {
                getLog().info("Jetty server exiting.");
            }            
        }
        
    }
    
    

    /**
     * Subclasses should invoke this to setup basic info
     * on the webapp
     * 
     * @throws MojoExecutionException
     */
    public void configureWebApplication () throws Exception
    {
        //As of jetty-7, you must use a <webAppConfig> element
        if (webApp == null)
            webApp = new JettyWebAppContext();
        
        //Apply any context xml file to set up the webapp
        //CAUTION: if you've defined a <webAppConfig> element then the
        //context xml file can OVERRIDE those settings
        if (contextXml != null)
        {
            File file = FileUtils.getFile(contextXml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.toURL(file));
            getLog().info("Applying context xml file "+contextXml);
            xmlConfiguration.configure(webApp);   
        }

        
        //If no contextPath was specified, go with our default
        String cp = webApp.getContextPath();
        if (cp == null || "".equals(cp))
        {
            webApp.setContextPath((contextPath.startsWith("/") ? contextPath : "/"+ contextPath));
        }

        //If no tmp directory was specified, and we have one, use it
        if (webApp.getTempDirectory() == null && tmpDirectory != null)
        {
            if (!tmpDirectory.exists())
                tmpDirectory.mkdirs();
            
            webApp.setTempDirectory(tmpDirectory);
        }
      
        getLog().info("Context path = " + webApp.getContextPath());
        getLog().info("Tmp directory = "+ (webApp.getTempDirectory()== null? " determined at runtime": webApp.getTempDirectory()));
        getLog().info("Web defaults = "+(webApp.getDefaultsDescriptor()==null?" jetty default":webApp.getDefaultsDescriptor()));
        getLog().info("Web overrides = "+(webApp.getOverrideDescriptor()==null?" none":webApp.getOverrideDescriptor()));
    }

    /**
     * Run a scanner thread on the given list of files and directories, calling
     * stop/start on the given list of LifeCycle objects if any of the watched
     * files change.
     *
     */
    private void startScanner() throws Exception
    {
        // check if scanning is enabled
        if (getScanIntervalSeconds() <= 0) return;

        // check if reload is manual. It disables file scanning
        if ( "manual".equalsIgnoreCase( reload ) )
        {
            // issue a warning if both scanIntervalSeconds and reload
            // are enabled
            getLog().warn("scanIntervalSeconds is set to " + scanIntervalSeconds + " but will be IGNORED due to manual reloading");
            return;
        }

        scanner = new Scanner();
        scanner.setReportExistingFilesOnStartup(false);
        scanner.setScanInterval(getScanIntervalSeconds());
        scanner.setScanDirs(getScanList());
        scanner.setRecursive(true);
        List listeners = getScannerListeners();
        Iterator itor = (listeners==null?null:listeners.iterator());
        while (itor!=null && itor.hasNext())
            scanner.addListener((Scanner.Listener)itor.next());
        getLog().info("Starting scanner at interval of " + getScanIntervalSeconds()+ " seconds.");
        scanner.start();
    }
    
    /**
     * Run a thread that monitors the console input to detect ENTER hits.
     */
    protected void startConsoleScanner() throws Exception
    {
        if ( "manual".equalsIgnoreCase( reload ) )
        {
            getLog().info("Console reloading is ENABLED. Hit ENTER on the console to restart the context.");
            consoleScanner = new ConsoleScanner(this);
            consoleScanner.start();
        }       
    }

    private void printSystemProperties ()
    {
        // print out which system properties were set up
        if (getLog().isDebugEnabled())
        {
            if (systemProperties != null)
            {
                Iterator itor = systemProperties.getSystemProperties().iterator();
                while (itor.hasNext())
                {
                    SystemProperty prop = (SystemProperty)itor.next();
                    getLog().debug("Property "+prop.getName()+"="+prop.getValue()+" was "+ (prop.isSet() ? "set" : "skipped"));
                }
            }
        }
    }

    /**
     * Try and find a jetty-web.xml file, using some
     * historical naming conventions if necessary.
     * @param webInfDir
     * @return the jetty web xml file
     */
    public File findJettyWebXmlFile (File webInfDir)
    {
        if (webInfDir == null)
            return null;
        if (!webInfDir.exists())
            return null;

        File f = new File (webInfDir, "jetty-web.xml");
        if (f.exists())
            return f;

        //try some historical alternatives
        f = new File (webInfDir, "web-jetty.xml");
        if (f.exists())
            return f;
        
        return null;
    }


    public Scanner getScanner ()
    {
        return scanner;
    }
    
    public MavenProject getProject()
    {
        return this.project;
    }

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public File getTmpDirectory()
    {
        return this.tmpDirectory;
    }

    public void setTmpDirectory(File tmpDirectory) {
        this.tmpDirectory = tmpDirectory;
    }

    /**
     * @return Returns the contextPath.
     */
    public String getContextPath()
    {
        return this.contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    /**
     * @return Returns the scanIntervalSeconds.
     */
    public int getScanIntervalSeconds()
    {
        return this.scanIntervalSeconds;
    }

    public void setScanIntervalSeconds(int scanIntervalSeconds) {
        this.scanIntervalSeconds = scanIntervalSeconds;
    }

    /**
     * @return returns the path to the systemPropertiesFile
     */
    public File getSystemPropertiesFile()
    {
        return this.systemPropertiesFile;
    }
    
    public void setSystemPropertiesFile(File file) throws Exception
    {
        this.systemPropertiesFile = file;
        FileInputStream propFile = new FileInputStream(systemPropertiesFile);
        Properties properties = new Properties();
        properties.load(propFile);
        
        if (this.systemProperties == null )
            this.systemProperties = new SystemProperties();
        
        for (Enumeration keys = properties.keys(); keys.hasMoreElements();  )
        {
            String key = (String)keys.nextElement();
            if ( ! systemProperties.containsSystemProperty(key) )
            {
                SystemProperty prop = new SystemProperty();
                prop.setKey(key);
                prop.setValue(properties.getProperty(key));
                
                this.systemProperties.setSystemProperty(prop);
            }
        }
        
    }
    
    public void setSystemProperties(SystemProperties systemProperties)
    {
        if (this.systemProperties == null)
            this.systemProperties = systemProperties;
        else
        {
            Iterator itor = systemProperties.getSystemProperties().iterator();
            while (itor.hasNext())
            {
                SystemProperty prop = (SystemProperty)itor.next();
                this.systemProperties.setSystemProperty(prop);
            }   
        }
    }

    public SystemProperties getSystemProperties ()
    {
        return this.systemProperties;
    }

    public List<File> getJettyXmlFiles()
    {
        if ( this.jettyXml == null )
        {
            return null;
        }
        
        List<File> jettyXmlFiles = new ArrayList<File>();
        
        if ( this.jettyXml.indexOf(',') == -1 )
        {
            jettyXmlFiles.add( new File( this.jettyXml ) );
        }
        else
        {
            String[] files = this.jettyXml.split(",");
            
            for ( String file : files )
            {
                jettyXmlFiles.add( new File(file) );
            }
        }
        
        return jettyXmlFiles;
    }


    public JettyServer getServer ()
    {
        return this.server;
    }

    public void setServer (JettyServer server)
    {
        this.server = server;
    }


    public void setScanList (ArrayList<File> list)
    {
        this.scanList = new ArrayList<File>(list);
    }

    public ArrayList<File> getScanList ()
    {
        return this.scanList;
    }


    public void setScannerListeners (ArrayList<Scanner.BulkListener> listeners)
    {
        this.scannerListeners = new ArrayList<Scanner.BulkListener>(listeners);
    }

    public ArrayList getScannerListeners()
    {
        return this.scannerListeners;
    }

    public JettyWebAppContext getWebAppConfig()
    {
        return webApp;
    }

    public void setWebAppConfig(JettyWebAppContext webAppConfig)
    {
        this.webApp = webAppConfig;
    }

    public RequestLog getRequestLog()
    {
        return requestLog;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        this.requestLog = requestLog;
    }

    public LoginService[] getLoginServices()
    {
        return loginServices;
    }

    public void setLoginServices(LoginService[] loginServices)
    {
        this.loginServices = loginServices;
    }

    public ContextHandler[] getContextHandlers()
    {
        return contextHandlers;
    }

    public void setContextHandlers(ContextHandler[] contextHandlers)
    {
        this.contextHandlers = contextHandlers;
    }

    public Connector[] getConnectors()
    {
        return connectors;
    }

    public void setConnectors(Connector[] connectors)
    {
        this.connectors = connectors;
    }

    public String getReload()
    {
        return reload;
    }

    public void setReload(String reload)
    {
        this.reload = reload;
    }

    public String getJettyConfig()
    {
        return jettyXml;
    }

    public void setJettyConfig(String jettyConfig)
    {
        this.jettyXml = jettyConfig;
    }

    public String getWebAppXml()
    {
        return contextXml;
    }

    public void setWebAppXml(String webAppXml)
    {
        this.contextXml = webAppXml;
    }

    public boolean isSkip()
    {
        return skip;
    }

    public void setSkip(boolean skip)
    {
        this.skip = skip;
    }

    public boolean isDaemon()
    {
        return daemon;
    }

    public void setDaemon(boolean daemon)
    {
        this.daemon = daemon;
    }

    public String getStopKey()
    {
        return stopKey;
    }

    public void setStopKey(String stopKey)
    {
        this.stopKey = stopKey;
    }

    public int getStopPort()
    {
        return stopPort;
    }

    public void setStopPort(int stopPort)
    {
        this.stopPort = stopPort;
    }
    
    public List getPluginArtifacts()
    {
        return pluginArtifacts;
    }

    public void setPluginArtifacts(List pluginArtifacts)
    {
        this.pluginArtifacts = pluginArtifacts;
    }
    
    
    public boolean isExcluded (String goal)
    {
        if (excludedGoals == null || goal == null)
            return false;
        
        goal = goal.trim();
        if ("".equals(goal))
            return false;
        
        boolean excluded = false;
        for (int i=0; i<excludedGoals.length && !excluded; i++)
        {
            if (excludedGoals[i].equalsIgnoreCase(goal))
                excluded = true;
        }
        
        return excluded;
    }
}

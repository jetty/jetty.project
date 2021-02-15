//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ShutdownMonitor;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * Common base class for most jetty mojos.
 */
public abstract class AbstractJettyMojo extends AbstractMojo
{
    /**
     * Whether or not to include dependencies on the plugin's classpath with &lt;scope&gt;provided&lt;/scope&gt;
     * Use WITH CAUTION as you may wind up with duplicate jars/classes.
     *
     * @since jetty-7.5.2
     */
    @Parameter(defaultValue = "false")
    protected boolean useProvidedScope;

    /**
     * List of goals that are NOT to be used
     *
     * @since jetty-7.5.2
     */
    @Parameter
    protected String[] excludedGoals;

    /**
     * List of other contexts to set up. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file.
     * Optional.
     */
    @Parameter
    protected ContextHandler[] contextHandlers;

    /**
     * List of security realms to set up. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file.
     * Optional.
     */
    @Parameter
    protected LoginService[] loginServices;

    /**
     * A RequestLog implementation to use for the webapp at runtime.
     * Consider using instead the &lt;jettyXml&gt; element to specify external jetty xml config file.
     * Optional.
     */
    @Parameter
    protected RequestLog requestLog;

    /**
     * An instance of org.eclipse.jetty.webapp.WebAppContext that represents the webapp.
     * Use any of its setters to configure the webapp. This is the preferred and most
     * flexible method of configuration, rather than using the (deprecated) individual
     * parameters like "tmpDirectory", "contextPath" etc.
     */
    @Parameter(alias = "webAppConfig")
    protected JettyWebAppContext webApp;

    /**
     * The interval in seconds to scan the webapp for changes
     * and restart the context if necessary. Ignored if reload
     * is enabled. Disabled by default.
     */
    @Parameter(property = "jetty.scanIntervalSeconds", defaultValue = "0", required = true)
    protected int scanIntervalSeconds;

    /**
     * reload can be set to either 'automatic' or 'manual'
     *
     * if 'manual' then the context can be reloaded by a linefeed in the console
     * if 'automatic' then traditional reloading on changed files is enabled.
     */
    @Parameter(property = "jetty.reload", defaultValue = "automatic")
    protected String reload;

    /**
     * File containing system properties to be set before execution
     *
     * Note that these properties will NOT override System properties
     * that have been set on the command line, by the JVM, or directly
     * in the POM via systemProperties. Optional.
     */
    @Parameter(property = "jetty.systemPropertiesFile")
    protected File systemPropertiesFile;

    /**
     * System properties to set before execution.
     * Note that these properties will NOT override System properties
     * that have been set on the command line or by the JVM. They WILL
     * override System properties that have been set via systemPropertiesFile.
     * Optional.
     */
    @Parameter
    protected SystemProperties systemProperties;

    /**
     * Comma separated list of a jetty xml configuration files whose contents
     * will be applied before any plugin configuration. Optional.
     */
    @Parameter(alias = "jettyConfig")
    protected String jettyXml;

    /**
     * Port to listen to stop jetty on executing -DSTOP.PORT=&lt;stopPort&gt;
     * -DSTOP.KEY=&lt;stopKey&gt; -jar start.jar --stop
     */
    @Parameter
    protected int stopPort;

    /**
     * Key to provide when stopping jetty on executing java -DSTOP.KEY=&lt;stopKey&gt;
     * -DSTOP.PORT=&lt;stopPort&gt; -jar start.jar --stop
     */
    @Parameter
    protected String stopKey;

    /**
     * Use the dump() facility of jetty to print out the server configuration to logging
     */
    @Parameter(property = "dumponStart", defaultValue = "false")
    protected boolean dumpOnStart;

    /**
     * Skip this mojo execution.
     */
    @Parameter(property = "jetty.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * Location of a context xml configuration file whose contents
     * will be applied to the webapp AFTER anything in &lt;webApp&gt;.Optional.
     */
    @Parameter(alias = "webAppXml")
    protected String contextXml;

    /**
     * The maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    /**
     * The artifacts for the project.
     */
    @Parameter(defaultValue = "${project.artifacts}", readonly = true)
    protected Set<Artifact> projectArtifacts;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    protected MojoExecution execution;

    /**
     * The artifacts for the plugin itself.
     */
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true)
    protected List<Artifact> pluginArtifacts;

    /**
     * A ServerConnector to use.
     */
    @Parameter
    protected MavenServerConnector httpConnector;

    /**
     * A wrapper for the Server object
     */
    @Parameter
    protected Server server;

    /**
     * A scanner to check for changes to the webapp
     */
    protected Scanner scanner;

    /**
     * A scanner to check ENTER hits on the console
     */
    protected Thread consoleScanner;

    protected ServerSupport serverSupport;

    /**
     * <p>
     * Determines whether or not the server blocks when started. The default
     * behavior (false) will cause the server to pause other processes
     * while it continues to handle web requests. This is useful when starting the
     * server with the intent to work with it interactively. This is the
     * behaviour of the jetty:run, jetty:run-war, jetty:run-war-exploded goals.
     * </p><p>
     * If true, the server will not block the execution of subsequent code. This
     * is the behaviour of the jetty:start and default behaviour of the jetty:deploy goals.
     * </p>
     */
    @Parameter(defaultValue = "false")
    protected boolean nonBlocking = false;

    /**
     * Per default this goal support only <code>war</code> packaging.
     * If your project use an other type please configure it here.
     */
    @Parameter
    protected List<String> supportedPackagings = Collections.singletonList("war");

    public abstract void restartWebApp(boolean reconfigureScanner) throws Exception;

    public boolean checkPomConfiguration() throws MojoExecutionException
    {
        return true;
    }

    public abstract void configureScanner() throws MojoExecutionException;

    protected String getSkipMessage(String reason)
    {
        String projectName = project.getName();
        if (StringUtils.isBlank(projectName))
        {
            projectName = project.getGroupId() + ":" + project.getArtifactId();
        }
        return "Skipping " + projectName + " : " + reason;
    }

    public boolean checkPackagingConfiguration()
    {
        if (!supportedPackagings.contains(project.getPackaging()))
        {
            getLog().info(getSkipMessage("packaging type [" + project.getPackaging() + "] is unsupported"));
            return false;
        }
        return true;
    }

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (isConfigurationSupported())
        {
            if (skip)
            {
                getLog().info(getSkipMessage("jetty.skip==true"));
                return;
            }

            getLog().info("Configuring Jetty for project: " + this.project.getName());

            if (isExcluded(execution.getMojoDescriptor().getGoal()))
            {
                getLog().info("The goal \"" + execution.getMojoDescriptor().getFullGoalName() +
                    "\" has been made unavailable for this web application by an <excludedGoal> configuration.");
                return;
            }

            configurePluginClasspath();
            PluginLog.setLog(getLog());
            startJetty();
        }
    }

    public boolean isConfigurationSupported() throws MojoExecutionException
    {
        return (checkPackagingConfiguration() && checkPomConfiguration());
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
                List<URL> provided = new ArrayList<>();

                for (Artifact artifact : projectArtifacts)
                {
                    if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()) && !isPluginArtifact(artifact))
                    {
                        provided.add(artifact.getFile().toURI().toURL());
                        if (getLog().isDebugEnabled())
                        {
                            getLog().debug("Adding provided artifact: " + artifact);
                        }
                    }
                }

                if (!provided.isEmpty())
                {
                    URL[] urls = provided.stream().toArray(URL[]::new);
                    URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader());
                    Thread.currentThread().setContextClassLoader(loader);
                    getLog().info("Plugin classpath augmented with <scope>provided</scope> dependencies: " + Arrays.toString(urls));
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

        for (Artifact pluginArtifact : pluginArtifacts)
        {
            if (getLog().isDebugEnabled())
            {
                getLog().debug("Checking " + pluginArtifact);
            }
            if (pluginArtifact.getGroupId().equals(artifact.getGroupId()) &&
                pluginArtifact.getArtifactId().equals(artifact.getArtifactId()))
                return true;
        }

        return false;
    }

    public void finishConfigurationBeforeStart() throws Exception
    {
        HandlerCollection contexts = server.getChildHandlerByClass(ContextHandlerCollection.class);
        if (contexts == null)
            contexts = server.getChildHandlerByClass(HandlerCollection.class);

        for (int i = 0; (this.contextHandlers != null) && (i < this.contextHandlers.length); i++)
        {
            contexts.addHandler(this.contextHandlers[i]);
        }
    }

    public void applyJettyXml() throws Exception
    {
        Server tmp = ServerSupport.applyXmlConfigurations(server, getJettyXmlFiles());
        if (server == null)
            server = tmp;

        if (server == null)
            server = new Server();
    }

    public void startJetty() throws MojoExecutionException
    {
        try
        {
            getLog().debug("Starting Jetty Server ...");

            //make sure Jetty does not use URLConnection caches with the plugin
            Resource.setDefaultUseCaches(false);

            configureMonitor();

            printSystemProperties();

            //apply any config from a jetty.xml file first which is able to
            //be overwritten by config in the pom.xml
            applyJettyXml();

            // if a <httpConnector> was specified in the pom, use it
            if (httpConnector != null)
            {

                // check that its port was set
                if (httpConnector.getPort() <= 0)
                {
                    //use any jetty.http.port settings provided
                    String tmp = System.getProperty(MavenServerConnector.PORT_SYSPROPERTY, //
                        System.getProperty("jetty.port", MavenServerConnector.DEFAULT_PORT_STR));
                    httpConnector.setPort(Integer.parseInt(tmp.trim()));
                }
                httpConnector.setServer(server);
            }

            ServerSupport.configureConnectors(server, httpConnector);

            //set up a RequestLog if one is provided and the handle structure
            ServerSupport.configureHandlers(server, this.requestLog);

            //Set up list of default Configurations to apply to a webapp
            ServerSupport.configureDefaultConfigurationClasses(server);
            configureWebApplication();
            ServerSupport.addWebApplication(server, webApp);

            // set up security realms
            ServerSupport.configureLoginServices(server, loginServices);

            //do any other configuration required by the
            //particular Jetty version
            finishConfigurationBeforeStart();

            // start Jetty
            this.server.start();

            getLog().info("Started Jetty Server");

            if (dumpOnStart)
            {
                getLog().info(this.server.dump());
            }

            // start the scanner thread (if necessary) on the main webapp
            if (isScanningEnabled())
            {
                scanner = new Scanner();
                scanner.setScanInterval(scanIntervalSeconds);
                scanner.setScanDepth(Scanner.MAX_SCAN_DEPTH); //always fully walk directory hierarchies
                scanner.setReportExistingFilesOnStartup(false);
                scanner.addListener(new Scanner.BulkListener()
                {
                    public void filesChanged(List<String> changes)
                    {
                        try
                        {
                            boolean reconfigure = changes.contains(project.getFile().getCanonicalPath());
                            restartWebApp(reconfigure);
                        }
                        catch (Exception e)
                        {
                            getLog().error("Error reconfiguring/restarting webapp after change in watched files", e);
                        }
                    }
                });
                configureScanner();
                startScanner();
            }

            // start the new line scanner thread if necessary
            startConsoleScanner();

            // keep the thread going if not in daemon mode
            if (!nonBlocking)
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
            if (!nonBlocking)
            {
                getLog().info("Jetty server exiting.");
            }
        }
    }

    public void configureMonitor()
    {
        if (stopPort > 0 && stopKey != null)
        {
            ShutdownMonitor monitor = ShutdownMonitor.getInstance();
            monitor.setPort(stopPort);
            monitor.setKey(stopKey);
            monitor.setExitVm(!nonBlocking);
        }
    }

    /**
     * Subclasses should invoke this to setup basic info
     * on the webapp
     *
     * @throws Exception if unable to configure web application
     */
    public void configureWebApplication() throws Exception
    {
        //As of jetty-7, you must use a <webApp> element
        if (webApp == null)
            webApp = new JettyWebAppContext();

        //Apply any context xml file to set up the webapp
        //CAUTION: if you've defined a <webApp> element then the
        //context xml file can OVERRIDE those settings
        if (contextXml != null)
        {
            Path path = Paths.get(contextXml);
            if (!path.isAbsolute())
            {
                Path workDir = Paths.get(System.getProperty("user.dir"));
                path = workDir.resolve(path);
                contextXml = path.toFile().getAbsolutePath();
            }
            
            getLog().info("Applying context xml file " + contextXml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.toURL(path.toFile()));
            xmlConfiguration.configure(webApp);
        }

        //If no contextPath was specified, go with default of project artifactid
        String cp = webApp.getContextPath();
        if (cp == null || "".equals(cp))
        {
            cp = "/" + project.getArtifactId();
            webApp.setContextPath(cp);
        }

        //If no tmp directory was specified, and we have one, use it
        if (webApp.getTempDirectory() == null)
        {
            File target = new File(project.getBuild().getDirectory());
            File tmp = new File(target, "tmp");
            if (!tmp.exists())
            {
                if (!tmp.mkdirs())
                {
                    throw new MojoFailureException("Unable to create temp directory: " + tmp);
                }
            }
            webApp.setTempDirectory(tmp);
        }

        getLog().info("Context path = " + webApp.getContextPath());
        getLog().info("Tmp directory = " + (webApp.getTempDirectory() == null ? " determined at runtime" : webApp.getTempDirectory()));
        getLog().info("Web defaults = " + (webApp.getDefaultsDescriptor() == null ? " jetty default" : webApp.getDefaultsDescriptor()));
        getLog().info("Web overrides = " + (webApp.getOverrideDescriptor() == null ? " none" : webApp.getOverrideDescriptor()));
    }

    /**
     * Run a scanner thread on the given list of files and directories, calling
     * stop/start on the given list of LifeCycle objects if any of the watched
     * files change.
     *
     * @throws Exception if unable to start scanner
     */
    public void startScanner() throws Exception
    {
        if (!isScanningEnabled())
            return;

        scanner.start();
    }

    public boolean isScanningEnabled()
    {
        return scanIntervalSeconds > 0 && !"manual".equalsIgnoreCase(reload);
    }

    public void stopScanner() throws Exception
    {
        if (!isScanningEnabled())
            return;

        if (scanner != null)
            scanner.stop();
    }

    /**
     * Run a thread that monitors the console input to detect ENTER hits.
     *
     * @throws Exception if unable to start the console
     */
    protected void startConsoleScanner() throws Exception
    {
        if ("manual".equalsIgnoreCase(reload))
        {
            getLog().info("Console reloading is ENABLED. Hit ENTER on the console to restart the context.");
            consoleScanner = new ConsoleScanner(this);
            consoleScanner.start();
        }
    }

    protected void printSystemProperties()
    {
        // print out which system properties were set up
        if (getLog().isDebugEnabled())
        {
            if (systemProperties != null)
            {
                systemProperties.getSystemProperties().stream().forEach(prop ->
                {
                    getLog().debug("Property " + prop.getName() + "=" + prop.getValue() + " was " + (prop.isSet() ? "set" : "skipped"));
                });
            }
        }
    }

    /**
     * Try and find a jetty-web.xml file, using some
     * historical naming conventions if necessary.
     *
     * @param webInfDir the web inf directory
     * @return the jetty web xml file
     */
    public File findJettyWebXmlFile(File webInfDir)
    {
        if (webInfDir == null)
            return null;
        if (!webInfDir.exists())
            return null;

        File f = new File(webInfDir, "jetty-web.xml");
        if (f.exists())
            return f;

        //try some historical alternatives
        f = new File(webInfDir, "web-jetty.xml");
        if (f.exists())
            return f;

        return null;
    }

    public void setSystemPropertiesFile(File file) throws Exception
    {
        this.systemPropertiesFile = file;
        Properties properties = new Properties();
        try (InputStream propFile = new FileInputStream(systemPropertiesFile))
        {
            properties.load(propFile);
        }
        if (this.systemProperties == null)
            this.systemProperties = new SystemProperties();

        for (Enumeration<?> keys = properties.keys(); keys.hasMoreElements(); )
        {
            String key = (String)keys.nextElement();
            if (!systemProperties.containsSystemProperty(key))
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
            for (SystemProperty prop : systemProperties.getSystemProperties())
            {
                this.systemProperties.setSystemProperty(prop);
            }
        }
    }

    public List<File> getJettyXmlFiles()
    {
        if (this.jettyXml == null)
        {
            return null;
        }

        List<File> jettyXmlFiles = new ArrayList<File>();

        if (this.jettyXml.indexOf(',') == -1)
        {
            jettyXmlFiles.add(new File(this.jettyXml));
        }
        else
        {
            String[] files = StringUtil.csvSplit(this.jettyXml);

            for (String file : files)
            {
                jettyXmlFiles.add(new File(file));
            }
        }

        return jettyXmlFiles;
    }

    public boolean isExcluded(String goal)
    {
        if (excludedGoals == null || goal == null)
            return false;

        goal = goal.trim();
        if ("".equals(goal))
            return false;

        boolean excluded = false;
        for (int i = 0; i < excludedGoals.length && !excluded; i++)
        {
            if (excludedGoals[i].equalsIgnoreCase(goal))
                excluded = true;
        }

        return excluded;
    }
}

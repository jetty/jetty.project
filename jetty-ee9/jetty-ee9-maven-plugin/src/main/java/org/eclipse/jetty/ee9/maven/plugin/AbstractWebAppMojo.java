//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jetty.ee9.maven.plugin.utils.MavenProjectHelper;
import org.eclipse.jetty.ee9.security.LoginService;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * AbstractWebAppMojo
 *
 * Base class for common behaviour of jetty mojos.
 */
public abstract class AbstractWebAppMojo extends AbstractMojo
{
    public static final String JETTY_HOME_GROUPID = "org.eclipse.jetty";
    public static final String JETTY_HOME_ARTIFACTID = "jetty-home";
    public static final String FAKE_WEBAPP = "webapp-synth";

    public enum DeploymentMode 
    {
        EMBED,
        FORK,
        HOME, //alias for EXTERNAL
        DISTRO, //alias for EXTERNAL
        EXTERNAL
    }

    /**
     * Max number of times to check to see if jetty has started correctly
     * when running in FORK or EXTERNAL mode.
     */
    @Parameter (defaultValue = "10")
    protected int maxChildStartChecks;

    /**
     * How long to wait in msec between checks to see if jetty has started
     * correctly when running in FORK or EXTERNAL mode.
     */
    @Parameter (defaultValue = "200")
    protected long maxChildStartCheckMs;
    /**
     * Whether or not to include dependencies on the plugin's classpath with &lt;scope&gt;provided&lt;/scope&gt;
     * Use WITH CAUTION as you may wind up with duplicate jars/classes.
     * 
     * @since jetty-7.5.2
     */
    @Parameter (defaultValue = "false")
    protected boolean useProvidedScope;
    

    /**
     * List of goals that are NOT to be used
     * 
     * @since jetty-7.5.2
     */
    @Parameter
    protected String[] excludedGoals;
    
    /**
     * An instance of org.eclipse.jetty.ee9.webapp.WebAppContext that represents the webapp.
     * Use any of its setters to configure the webapp. This is the preferred and most
     * flexible method of configuration, rather than using the (deprecated) individual
     * parameters like "tmpDirectory", "contextPath" etc.
     * 
     */
    @Parameter
    protected MavenWebAppContext webApp;

    /**  
     * Skip this mojo execution.
     */
    @Parameter (property  = "jetty.skip", defaultValue = "false")
    protected boolean skip;
    
    
    /**
     * Location of a context xml configuration file whose contents
     * will be applied to the webapp AFTER anything in &lt;webApp&gt;.Optional.
     */
    @Parameter
    protected String contextXml;


    /**
     * The maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    
    /**
     * The artifacts for the project.
     */
    @Parameter (defaultValue = "${project.artifacts}", readonly = true)
    protected Set<Artifact> projectArtifacts;
    
    /** 
     * The maven build executing.
     */    
    @Parameter (defaultValue = "${mojoExecution}", readonly = true)
    protected org.apache.maven.plugin.MojoExecution execution;
    

    /**
     * The artifacts for the plugin itself.
     */    
    @Parameter (defaultValue = "${plugin.artifacts}", readonly = true)
    protected List<Artifact> pluginArtifacts;
    

    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     */    
    @Parameter (defaultValue = "false")
    protected boolean useTestScope;
 
    /**
     * List of directories with ant-style &lt;include&gt; and &lt;exclude&gt; patterns
     * for extra targets to periodically scan for changes.Optional.
     */
    @Parameter
    protected List<ScanTargetPattern> scanTargetPatterns;
    
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    protected List<MavenProject> reactorProjects;
    
    /**
     * The target directory
     */
    @Parameter (defaultValue = "${project.build.directory}", required = true, readonly = true)
    protected File target;
    
    
    /**
     * List of jetty xml configuration files whose contents 
     * will be applied (in order declared) before any plugin configuration. Optional.
     */
    @Parameter
    protected List<File> jettyXmls;
    
    
    /**
     * Optional jetty properties to put on the command line
     */
    @Parameter
    protected Map<String, String> jettyProperties;

    
    /**
     * File containing system properties to be set before execution
     * 
     * Note that these properties will NOT override System properties
     * that have been set on the command line, by the JVM, or directly 
     * in the POM via systemProperties. Optional.
     * 
     * 
     */
    @Parameter (property = "jetty.systemPropertiesFile")
    protected File systemPropertiesFile;

    
    /**
     * System properties to set before execution. 
     * Note that these properties will NOT override System properties 
     * that have been set on the command line or by the JVM. They WILL 
     * override System properties that have been set via systemPropertiesFile.
     * Optional.
     */
    @Parameter
    protected Map<String, String> systemProperties;

    /**
     * Controls how to run jetty. Valid values are EMBED,FORK,EXTERNAL.
     */
    @Parameter (property = "jetty.deployMode", defaultValue = "EMBED") 
    protected DeploymentMode deployMode;
    
    
    /**
     * List of other contexts to set up. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file. 
     * Optional.
     */
    @Parameter
    protected List<ContextHandler> contextHandlers;
    
    /**
     * List of security realms to set up. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file. 
     * Optional.
     */
    @Parameter
    protected List<LoginService> loginServices;

    /**
     * A RequestLog implementation to use for the webapp at runtime.
     * Consider using instead the &lt;jettyXml&gt; element to specify external jetty xml config file. 
     * Optional.
     */
    @Parameter
    protected RequestLog requestLog;
    
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
    //End of EMBED only
    

    //Start of parameters only valid for FORK/EXTERNAL
    /**
     * Extra environment variables to be passed to the forked process
     */
    @Parameter
    protected Map<String, String> env = new HashMap<>();

    /**
     * Arbitrary jvm args to pass to the forked process
     */
    @Parameter (property = "jetty.jvmArgs")
    protected String jvmArgs;
    
    /**
     * Port to listen to stop jetty on executing -DSTOP.PORT=&lt;stopPort&gt; 
     * -DSTOP.KEY=&lt;stopKey&gt; -jar start.jar --stop
     * 
     */
    @Parameter
    protected int stopPort;
    
    /**
     * Key to provide when stopping jetty on executing java -DSTOP.KEY=&lt;stopKey&gt; 
     * -DSTOP.PORT=&lt;stopPort&gt; -jar start.jar --stop
     *
     */
    @Parameter
    protected String stopKey;
    //End of FORK or EXTERNAL parameters

    //Start of parameters only valid for EXTERNAL
    /**
     * Location of jetty home directory
     */
    @Parameter
    protected File jettyHome;
    
    /**
     * Location of jetty base directory
     */
    @Parameter
    protected File jettyBase;
    
    /**
     * Optional list of other modules to
     * activate.
     */
    @Parameter
    protected String[] modules;

    /**
     * Extra options that can be passed to the jetty command line
     */
    @Parameter (property = "jetty.options")
    protected String jettyOptions;

    //End of EXTERNAL only parameters

    //Start of parameters only valid for FORK
    /**
     * The file into which to generate the quickstart web xml for the forked process to use
     * 
     */
    @Parameter (defaultValue = "${project.build.directory}/fork-web.xml")
    protected File forkWebXml;
    //End of FORK only parameters
    
    /**
     * Helper for interacting with the maven project space
     */
    protected MavenProjectHelper mavenProjectHelper;
    
    /**
     * This plugin
     */
    @Parameter (defaultValue = "${plugin}", readonly = true, required = true)
    protected PluginDescriptor plugin;
    
    /**
     * The project's remote repositories to use for the resolution.
     */
    @Parameter (defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
    private List<ArtifactRepository> remoteRepositories;

    /**
     * 
     */
    @Component
    private RepositorySystem repositorySystem;
    
    /**
     * The current maven session
     */
    @Parameter (defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;
    
    /**
     * Default supported project type is <code>war</code> packaging.
     */
    @Parameter
    protected List<String> supportedPackagings = Collections.singletonList("war");
    
    /**
     * List of deps that are wars
     */
    protected List<Artifact> warArtifacts;
    
    /**
     * Webapp base before applying overlays etc
     */
    protected Resource originalBaseResource;
    
    /**
     * List of jars with scope=provided
     */
    protected List<File> providedJars;

    /**
     * System properties from both systemPropertyFile and systemProperties.
     */
    protected Map<String, String> mergedSystemProperties;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (isPackagingSupported())
        {
            if (skip)
            {
                getLog().info("Skipping Jetty start: jetty.skip==true");
                return;
            }

            if (isExcludedGoal(execution.getMojoDescriptor().getGoal()))
            {
                getLog().info("The goal \"" + execution.getMojoDescriptor().getFullGoalName() + 
                    "\" is unavailable for this web app because of an <excludedGoal> configuration.");
                return;
            }
            
            getLog().info("Configuring Jetty for project: " + getProjectName());
            mavenProjectHelper = new MavenProjectHelper(project, repositorySystem, remoteRepositories, session);
            mergedSystemProperties = mergeSystemProperties();
            configureSystemProperties();
            augmentPluginClasspath();
            PluginLog.setLog(getLog());
            verifyPomConfiguration();
            startJetty();
        }
        else
            getLog().info("Packaging type [" + project.getPackaging() + "] is unsupported");
    }

    protected void startJetty()
        throws MojoExecutionException, MojoFailureException
    {
        try
        {
            configureWebApp();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Webapp config failure", e);
        }
        
        switch (deployMode)
        {
            case EMBED: 
            {
                startJettyEmbedded();
                break;
            }
            case FORK:
            {
                startJettyForked();
                break;
            }
            case DISTRO:
            case HOME:
            case EXTERNAL:
            {
                if (deployMode != DeploymentMode.EXTERNAL)
                    getLog().warn(deployMode + " mode is deprecated, use mode EXTERNAL");
                startJettyHome();
                break;
            }
            default:
                throw new MojoExecutionException("Unrecognized runType=" + deployMode);
        }

    }

    protected abstract void startJettyEmbedded() throws MojoExecutionException;
    
    protected abstract void startJettyForked() throws MojoExecutionException;
    
    protected abstract void startJettyHome() throws MojoExecutionException;

    protected JettyEmbedder newJettyEmbedder()
        throws Exception
    {
        JettyEmbedder jetty = new JettyEmbedder();
        jetty.setStopKey(stopKey);
        jetty.setStopPort(stopPort);
        jetty.setServer(server);
        jetty.setContextHandlers(contextHandlers);
        jetty.setRequestLog(requestLog);
        jetty.setJettyXmlFiles(jettyXmls);
        jetty.setHttpConnector(httpConnector);
        jetty.setJettyProperties(jettyProperties);
        jetty.setLoginServices(loginServices);
        jetty.setContextXml(contextXml);
        jetty.setWebApp(webApp);
        return jetty;
    }

    protected JettyForker newJettyForker()
        throws Exception
    {
        JettyForker jetty = new JettyForker();
        jetty.setServer(server);
        jetty.setWorkDir(project.getBasedir());
        jetty.setStopKey(stopKey);
        jetty.setStopPort(stopPort);
        jetty.setEnv(env);
        jetty.setJvmArgs(jvmArgs);
        jetty.setSystemProperties(mergedSystemProperties);
        jetty.setContainerClassPath(getContainerClassPath());
        jetty.setJettyXmlFiles(jettyXmls);
        jetty.setJettyProperties(jettyProperties);
        jetty.setForkWebXml(forkWebXml);
        jetty.setContextXml(contextXml);
        jetty.setWebAppPropsFile(new File(target, "webApp.props"));
        Random random = new Random();
        String token = Long.toString(random.nextLong() ^ System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
        jetty.setTokenFile(target.toPath().resolve(token + ".txt").toFile());
        jetty.setWebApp(webApp);
        return jetty;
    }

    protected JettyHomeForker newJettyHomeForker()
        throws Exception
    {
        JettyHomeForker jetty = new JettyHomeForker();
        jetty.setStopKey(stopKey);
        jetty.setStopPort(stopPort);
        jetty.setEnv(env);
        jetty.setJvmArgs(jvmArgs);
        jetty.setJettyOptions(jettyOptions);
        jetty.setJettyXmlFiles(jettyXmls);
        jetty.setJettyProperties(jettyProperties);
        jetty.setModules(modules);
        jetty.setSystemProperties(mergedSystemProperties);
        Random random = new Random();
        String token = Long.toString(random.nextLong() ^ System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
        jetty.setTokenFile(target.toPath().resolve(token + ".txt").toFile());

        List<File> libExtJars = new ArrayList<>();

        List<Dependency> pdeps = plugin.getPlugin().getDependencies();
        if (pdeps != null && !pdeps.isEmpty())
        {
            boolean warned = false;
            for (Dependency d:pdeps)
            {
                if (d.getGroupId().equalsIgnoreCase("org.eclipse.jetty"))
                {
                    if (!warned)
                    {
                        getLog().warn("Jetty jars detected in <pluginDependencies>: use <modules> in <configuration> parameter instead to select appropriate jetty modules.");
                        warned = true;
                    }
                }
                else
                {
                    libExtJars.add(mavenProjectHelper.resolveArtifact(d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getType()));
                }
            }
            jetty.setLibExtJarFiles(libExtJars);
        }

        jetty.setWebApp(webApp);
        jetty.setContextXml(contextXml);

        if (jettyHome == null)
            jetty.setJettyHomeZip(mavenProjectHelper.resolveArtifact(JETTY_HOME_GROUPID, JETTY_HOME_ARTIFACTID, plugin.getVersion(), "zip"));

        jetty.version = plugin.getVersion();
        jetty.setJettyHome(jettyHome);
        jetty.setJettyBase(jettyBase);
        jetty.setBaseDir(target);
        
        return jetty;
    }
    
    /**
     * Used by subclasses.
     * @throws MojoExecutionException
     */
    protected void verifyPomConfiguration() throws MojoExecutionException
    {
    }

    /**
     * Unite system properties set via systemPropertiesFile element and the systemProperties element.
     * Properties from the pom override properties from the file.
     * 
     * @return united properties map
     * @throws MojoExecutionException
     */
    protected Map<String, String> mergeSystemProperties()
        throws MojoExecutionException
    {
        Map<String, String> properties = new HashMap<>();
        
        //Get the properties from any file first
        if (systemPropertiesFile != null)
        {
            Properties tmp = new Properties();
            try (InputStream propFile = new FileInputStream(systemPropertiesFile))
            {
                tmp.load(propFile);
                for (Object k:tmp.keySet())
                    properties.put(k.toString(), tmp.get(k).toString());
            }
            catch (Exception e)
            {
                throw new MojoExecutionException("Problem applying system properties from file " + systemPropertiesFile.getName(), e);
            }
        }
        //Allow systemProperties defined in the pom to override the file
        if (systemProperties != null)
        {
            properties.putAll(systemProperties);
        }
        return properties;
    }

    protected void configureSystemProperties()
        throws MojoExecutionException
    {
        if (mergedSystemProperties != null)
        {
            for (Map.Entry<String, String> e : mergedSystemProperties.entrySet())
            {
                if (!StringUtil.isEmpty(e.getKey()) && !StringUtil.isEmpty(e.getValue()))
                {
                    System.setProperty(e.getKey(), e.getValue());
                    if (getLog().isDebugEnabled())
                        getLog().debug("Set system property " + e.getKey() + "=" + e.getValue());
                }
            }
        }
    }

    /**
     * Augment jetty's classpath with dependencies marked as scope=provided
     * if useProvidedScope==true.
     * 
     * @throws MojoExecutionException
     */
    protected void augmentPluginClasspath() throws MojoExecutionException
    {  
        //Filter out ones that will clash with jars that are plugin dependencies, then
        //create a new classloader that we setup in the parent chain.
        providedJars = getProvidedJars();

        if (!providedJars.isEmpty())
        {
            try
            {
                URL[] urls = new URL[providedJars.size()];
                int i = 0;
                for (File providedJar:providedJars)
                    urls[i++] = providedJar.toURI().toURL();
                URLClassLoader loader  = new URLClassLoader(urls, getClass().getClassLoader());
                Thread.currentThread().setContextClassLoader(loader);
                getLog().info("Plugin classpath augmented with <scope>provided</scope> dependencies: " + Arrays.toString(urls));
            }
            catch (MalformedURLException e)
            {
                throw new MojoExecutionException("Invalid url", e);
            }
        }
    }

    /**
     * Get any dependencies that are scope "provided" if useProvidedScope == true. Ensure
     * that only those dependencies that are not already present via the plugin are
     * included.
     * 
     * @return provided scope dependencies that are not also plugin dependencies.
     * @throws MojoExecutionException
     */
    protected List<File> getProvidedJars() throws MojoExecutionException
    {  
        if (useProvidedScope)
        {
            return project.getArtifacts()
                .stream()
                .filter(a -> Artifact.SCOPE_PROVIDED.equals(a.getScope()) && !isPluginArtifact(a))
                .map(a -> a.getFile()).collect(Collectors.toList());
        }
        else
            return Collections.emptyList();
    }

    /**
     * Synthesize a classpath appropriate for a forked jetty based off
     * the artifacts associated with the jetty plugin, plus any dependencies
     * that are marked as provided and useProvidedScope is true.
     * 
     * @return jetty classpath
     * @throws Exception
     */
    protected String getContainerClassPath() throws Exception
    {
        //Add in all the plugin artifacts
        StringBuilder classPath = new StringBuilder();
        for (Object obj : pluginArtifacts)
        {
            Artifact artifact = (Artifact)obj;
            if ("jar".equals(artifact.getType()))
            {
                if (classPath.length() > 0)
                    classPath.append(File.pathSeparator);
                classPath.append(artifact.getFile().getAbsolutePath());
            }
            else
            {
                if (artifact.getArtifactId().equals(plugin.getArtifactId())) //get the jetty-maven-plugin jar
                    classPath.append(artifact.getFile().getAbsolutePath());
            }
        }
        
        //Any jars that we need from the project's dependencies because we're useProvided
        if (providedJars != null && !providedJars.isEmpty())
        {
            for (File jar:providedJars)
            {
                classPath.append(File.pathSeparator);
                classPath.append(jar.getAbsolutePath());
                if (getLog().isDebugEnabled()) getLog().debug("Adding provided jar: " + jar);
            }
        }

        return classPath.toString();
    }

    /**
     * Check to see if the given artifact is one of the dependency artifacts for this plugin.
     * 
     * @param artifact to check
     * @return true if it is a plugin dependency, false otherwise
     */
    protected boolean isPluginArtifact(Artifact artifact)
    {
        if (pluginArtifacts == null)
            return false;
        
        return pluginArtifacts.stream().anyMatch(pa -> pa.getGroupId().equals(artifact.getGroupId()) && pa.getArtifactId().equals(artifact.getArtifactId()));
    }
    
    /**
     * Check if the goal that we're executing as is excluded or not.
     * 
     * @param goal the goal to check
     * @return true if the goal is excluded, false otherwise
     */
    protected boolean isExcludedGoal(String goal)
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

    protected boolean isPackagingSupported()
    {
        if (!supportedPackagings.contains(project.getPackaging()))
            return false;
        return true;
    }

    protected String getProjectName()
    {
        String projectName = project.getName();
        if (StringUtils.isBlank(projectName))
        {
            projectName = project.getGroupId() + ":" + project.getArtifactId();
        }
        return projectName;
    }

    /**
     * Ensure there is a webapp, and that some basic defaults are applied
     * if the user has not supplied them.
     * 
     * @throws Exception
     */
    protected void configureWebApp()
        throws Exception
    {
        if (webApp == null)
            webApp = new MavenWebAppContext();
        
        //If no contextPath was specified, go with default of project artifactid
        String cp = webApp.getContextPath();
        if (cp == null || "".equals(cp))
        {
            cp = "/" +  project.getArtifactId();
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
    }

    /**
     * Try and find a jetty-web.xml file, using some
     * historical naming conventions if necessary.
     * 
     * @param webInfDir the web inf directory
     * @return the jetty web xml file
     */
    protected File findJettyWebXmlFile(File webInfDir)
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
    
    /**
     * Get a file into which to write output from jetty.
     * 
     * @param name the name of the file
     * @return the created file
     * @throws Exception
     */
    protected File getJettyOutputFile(String name) throws Exception
    {
        File outputFile = new File(target, name);
        if (outputFile.exists())
            outputFile.delete();
        outputFile.createNewFile();
        return outputFile;
    }
    
    /**
     * Configure any extra files, directories or patterns thereof for the
     * scanner to watch for changes.
     * 
     * @param scanner Scanner that notices changes in files and dirs.
     * @throws IOException 
     */
    protected void configureScanTargetPatterns(Scanner scanner) throws IOException
    {
        //handle the extra scan patterns
        if (scanTargetPatterns != null)
        {
            for (ScanTargetPattern p : scanTargetPatterns)
            {
                IncludeExcludeSet<PathMatcher, Path> includesExcludes = scanner.addDirectory(p.getDirectory().toPath());
                p.configureIncludesExcludeSet(includesExcludes);
            }
        }
    }
}

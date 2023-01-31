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

package org.eclipse.jetty.maven;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.jetty.maven.utils.MavenProjectHelper;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.twdata.maven.mojoexecutor.MojoExecutor;

/**
 * AbstractWebAppMojo
 *
 * Base class for common behaviour of jetty mojos.
 */
public abstract class AbstractWebAppMojo extends AbstractMojo
{

    @Inject
    protected BuildPluginManager buildPluginManager;

    public enum DeploymentMode 
    {
        EMBED,
        FORK,
        HOME, //alias for EXTERNAL
        DISTRO, //alias for EXTERNAL
        EXTERNAL
    }

    /**
     * jetty environment value such ee8, ee9, ee10
     * if empty will be discovered from your dependencies
     */
    @Parameter (defaultValue = "ee10", property = "jetty.environment")
    private String environment;

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
     * An instance of org.eclipse.jetty.eeXX.webapp.WebAppContext that represents the webapp.
     * Use any of its setters to configure the webapp. This is the preferred and most
     * flexible method of configuration, rather than using the (deprecated) individual
     * parameters like "tmpDirectory", "contextPath" etc.
     * 
     */
    @Parameter(name = "webApp")
    protected PlexusConfiguration webApp;

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
    protected MojoExecution execution;
    

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
     * List of security realms (e.g LoginService) to set up.
     * @deprecated Consider using instead the &lt;jettyXml&gt; element to specify external jetty xml config file.
     * Optional.
     */
    @Parameter
    @Deprecated
    protected List<Object> loginServices;

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

    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    protected MojoExecution mojo;

    @Inject
    private MavenPluginManager mavenPluginManager;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        ClassRealm original = (ClassRealm)Thread.currentThread().getContextClassLoader();

        Plugin targetPlugin = MojoExecutor.plugin("org.eclipse.jetty." + getEnvironment(),
                "jetty-" + getEnvironment() + "-maven-plugin",
                plugin.getVersion());

        targetPlugin.setDependencies(plugin.getPlugin().getDependencies());
        RepositorySystemSession repositorySystemSession = session.getRepositorySession();
        List<RemoteRepository> remoteRepositories = session.getCurrentProject().getRemotePluginRepositories();

        try
        {
            PluginDescriptor pluginDescriptor =
                    mavenPluginManager.getPluginDescriptor(targetPlugin, remoteRepositories, repositorySystemSession);
            ClassRealm classRealm = buildPluginManager.getPluginRealm(session, pluginDescriptor);

            Xpp3Dom originalConfiguration = mojo.getConfiguration();

            Xpp3Dom newConfiguration = new Xpp3Dom(originalConfiguration.getName());
            for (Xpp3Dom child : originalConfiguration.getChildren())
            {
                //remove environment mojo
                if (!"mojo".equals(child.getName()) && !"environment".equals(child.getName()))
                {
                    newConfiguration.addChild(child);
                }
            }

            Thread.currentThread().setContextClassLoader(classRealm);
            MojoExecutor.executeMojo(
                    targetPlugin,
                    mojo.getGoal(),
                    newConfiguration,
                    MojoExecutor.executionEnvironment(project, session, buildPluginManager));
        }
        catch (PluginResolutionException | PluginManagerException | PluginDescriptorParsingException |
                 InvalidPluginDescriptorException e)
        {
            throw new MojoExecutionException(e);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(original);
        }

    }

    protected String getEnvironment()
    {
        // TODO generate warning if null
        // auto detect based on project dependencies (javax.servlet or jakarta.servlet then version of the spec..)
        return environment == null ? "ee10" : StringUtils.toRootLowerCase(environment);
    }

}

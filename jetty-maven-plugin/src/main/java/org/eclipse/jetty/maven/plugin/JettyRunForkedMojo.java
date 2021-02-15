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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * This goal is used to deploy your unassembled webapp into a forked JVM.
 * <p>
 * You need to define a jetty.xml file to configure connectors etc. You can use the normal setters of o.e.j.webapp.WebAppContext on the <b>webApp</b>
 * configuration element for this plugin. You may also need context xml file for any particularly complex webapp setup.
 *
 * <p>
 * Unlike the other jetty goals, this does NOT support the <b>scanIntervalSeconds</b> parameter: the webapp will be deployed only once.
 * <p>
 * The <b>stopKey</b>, <b>stopPort</b> configuration elements can be used to control the stopping of the forked process. By default, this plugin will launch
 * the forked jetty instance and wait for it to complete (in which case it acts much like the <b>jetty:run</b> goal, and you will need to Cntrl-C to stop).
 * By setting the configuration element <b>waitForChild</b> to <b>false</b>, the plugin will terminate after having forked the jetty process. In this case
 * you can use the <b>jetty:stop</b> goal to terminate the process.
 * <p>
 * See <a href="https://www.eclipse.org/jetty/documentation/">https://www.eclipse.org/jetty/documentation</a> for more information on this and other jetty plugins.
 *
 * Runs Jetty in forked JVM on an unassembled webapp
 */
@Mojo(name = "run-forked", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class JettyRunForkedMojo extends JettyRunMojo
{
    /**
     * The target directory
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File target;

    /**
     * The file into which to generate the quickstart web xml for the forked process to use
     */
    @Parameter(defaultValue = "${project.build.directory}/fork-web.xml")
    protected File forkWebXml;

    /**
     * Arbitrary jvm args to pass to the forked process
     */
    @Parameter(property = "jetty.jvmArgs")
    private String jvmArgs;

    /**
     * Optional list of jetty properties to put on the command line
     */
    @Parameter
    private String[] jettyProperties;

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true)
    private List<Artifact> pluginArtifacts;

    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor plugin;

    @Parameter(defaultValue = "true")
    private boolean waitForChild;

    /**
     * Max number of times to try checking if the
     * child has started successfully.
     */
    @Parameter(alias = "maxStartupLines", defaultValue = "50")
    private int maxChildChecks;

    /**
     * Millisecs to wait between each
     * check to see if the child started successfully.
     */
    @Parameter(defaultValue = "100")
    private long maxChildCheckInterval;

    /**
     * Extra environment variables to be passed to the forked process
     */
    @Parameter
    private Map<String, String> env = new HashMap<>();

    /**
     * The forked jetty instance
     */
    private Process forkedProcess;

    /**
     * Random number generator
     */
    private Random random;

    /**
     * Whether or not the plugin has explicit slf4j dependencies.
     * The maven environment will always have slf4j on the classpath,
     * which we don't want to put onto the forked process unless the
     * pom has an explicit dependency on it.
     */
    private boolean hasSlf4jDeps;

    @Parameter(property = "jetty.javaPath")
    private String javaPath;

    /**
     * ShutdownThread
     */
    public class ShutdownThread extends Thread
    {
        public ShutdownThread()
        {
            super("RunForkedShutdown");
        }

        @Override
        public void run()
        {
            if (forkedProcess != null && waitForChild)
            {
                forkedProcess.destroy();
            }
        }
    }

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        Runtime.getRuntime().addShutdownHook(new ShutdownThread());
        random = new Random();

        List<Dependency> deps = plugin.getPlugin().getDependencies();
        for (Dependency d : deps)
        {
            if (d.getGroupId().contains("slf4j"))
            {
                hasSlf4jDeps = true;
                break;
            }
        }

        super.execute();
    }

    @Override
    public void startJetty() throws MojoExecutionException
    {
        //Only do enough setup to be able to produce a quickstart-web.xml file to
        //pass onto the forked process to run     

        try
        {
            printSystemProperties();

            //do NOT apply the jettyXml configuration - as the jvmArgs may be needed for it to work 
            if (server == null)
                server = new Server();

            //ensure handler structure enabled
            ServerSupport.configureHandlers(server, null);

            ServerSupport.configureDefaultConfigurationClasses(server);

            //ensure config of the webapp based on settings in plugin
            configureWebApplication();

            //set the webapp up to do very little other than generate the quickstart-web.xml
            webApp.setCopyWebDir(false);
            webApp.setCopyWebInf(false);
            webApp.setGenerateQuickStart(true);

            if (webApp.getQuickStartWebDescriptor() == null)
            {
                if (forkWebXml == null)
                    forkWebXml = new File(target, "fork-web.xml");

                if (!forkWebXml.getParentFile().exists())
                    forkWebXml.getParentFile().mkdirs();
                if (!forkWebXml.exists())
                    forkWebXml.createNewFile();

                webApp.setQuickStartWebDescriptor(Resource.newResource(forkWebXml));
            }

            //add webapp to our fake server instance
            ServerSupport.addWebApplication(server, webApp);

            //if our server has a thread pool associated we can do annotation scanning multithreaded,
            //otherwise scanning will be single threaded
            QueuedThreadPool tpool = server.getBean(QueuedThreadPool.class);
            if (tpool != null)
                tpool.start();
            else
                webApp.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE.toString());

            //leave everything unpacked for the forked process to use
            webApp.setPersistTempDirectory(true);

            webApp.start(); //just enough to generate the quickstart

            //save config of the webapp BEFORE we stop
            File props = prepareConfiguration();

            webApp.stop();

            if (tpool != null)
                tpool.stop();

            List<String> cmd = new ArrayList<>();
            if (StringUtil.isNotBlank(javaPath))
            {
                cmd.add(javaPath);
            }
            else
            {
                cmd.add(getJavaBin());
            }

            if (jvmArgs != null)
            {
                String[] args = jvmArgs.split(" ");
                for (int i = 0; args != null && i < args.length; i++)
                {
                    if (args[i] != null && !"".equals(args[i]))
                        cmd.add(args[i].trim());
                }
            }

            String classPath = getContainerClassPath();
            if (classPath != null && classPath.length() > 0)
            {
                cmd.add("-cp");
                cmd.add(classPath);
            }
            cmd.add(Starter.class.getCanonicalName());

            if (stopPort > 0 && stopKey != null)
            {
                cmd.add("--stop-port");
                cmd.add(Integer.toString(stopPort));
                cmd.add("--stop-key");
                cmd.add(stopKey);
            }
            if (jettyXml != null)
            {
                cmd.add("--jetty-xml");
                cmd.add(jettyXml);
            }

            cmd.add("--props");
            cmd.add(props.getAbsolutePath());

            Path tokenFile = target.toPath().resolve(createToken() + ".txt");
            cmd.add("--token");
            cmd.add(tokenFile.toAbsolutePath().toString());

            if (jettyProperties != null)
            {
                for (String jettyProp : jettyProperties)
                {
                    cmd.add(jettyProp);
                }
            }

            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.directory(project.getBasedir());

            if (PluginLog.getLog().isDebugEnabled())
                PluginLog.getLog().debug("Forked cli:" + Arrays.toString(cmd.toArray()));

            PluginLog.getLog().info("Forked process starting");

            //set up extra environment vars if there are any
            if (!env.isEmpty())
            {
                builder.environment().putAll(env);
            }

            if (waitForChild)
            {
                builder.inheritIO();
            }
            else
            {
                builder.redirectOutput(new File(target, "jetty.out"));
                builder.redirectErrorStream(true);
            }

            forkedProcess = builder.start();

            if (waitForChild)
            {
                int exitcode = forkedProcess.waitFor();
                PluginLog.getLog().info("Forked execution exit: " + exitcode);
            }
            else
            {
                //just wait until the child has started successfully
                int attempts = maxChildChecks;
                while (!Files.exists(tokenFile) && attempts > 0)
                {
                    Thread.sleep(maxChildCheckInterval);
                    --attempts;
                }
                if (attempts <= 0)
                    getLog().info("Couldn't verify success of child startup");
            }
        }
        catch (InterruptedException ex)
        {
            if (forkedProcess != null && waitForChild)
                forkedProcess.destroy();

            throw new MojoExecutionException("Failed to start Jetty within time limit");
        }
        catch (Exception ex)
        {
            if (forkedProcess != null && waitForChild)
                forkedProcess.destroy();

            throw new MojoExecutionException("Failed to create Jetty process", ex);
        }
    }

    public List<String> getProvidedJars() throws MojoExecutionException
    {
        //if we are configured to include the provided dependencies on the plugin's classpath
        //(which mimics being on jetty's classpath vs being on the webapp's classpath), we first
        //try and filter out ones that will clash with jars that are plugin dependencies, then
        //create a new classloader that we setup in the parent chain.
        if (useProvidedScope)
        {

            List<String> provided = new ArrayList<>();
            for (Artifact artifact : project.getArtifacts())
            {
                if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()) && !isPluginArtifact(artifact))
                {
                    provided.add(artifact.getFile().getAbsolutePath());
                    if (getLog().isDebugEnabled())
                    {
                        getLog().debug("Adding provided artifact: " + artifact);
                    }
                }
            }
            return provided;
        }
        else
            return null;
    }

    public File prepareConfiguration() throws MojoExecutionException
    {
        try
        {
            //work out the configuration based on what is configured in the pom
            File propsFile = new File(target, "fork.props");
            WebAppPropertyConverter.toProperties(webApp, propsFile, contextXml);
            return propsFile;
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Prepare webapp configuration", e);
        }
    }

    @Override
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
            if (pluginArtifact.getGroupId().equals(artifact.getGroupId()) && pluginArtifact.getArtifactId().equals(artifact.getArtifactId()))
                return true;
        }

        return false;
    }

    private Set<Artifact> getExtraJars()
        throws Exception
    {
        Set<Artifact> extraJars = new HashSet<>();

        List l = pluginArtifacts;
        Artifact pluginArtifact = null;

        if (l != null)
        {

            Iterator itor = l.iterator();
            while (itor.hasNext() && pluginArtifact == null)
            {
                Artifact a = (Artifact)itor.next();
                if (a.getArtifactId().equals(plugin.getArtifactId())) //get the jetty-maven-plugin jar
                {
                    extraJars.add(a);
                }
            }
        }

        return extraJars;
    }

    public String getContainerClassPath() throws Exception
    {
        StringBuilder classPath = new StringBuilder();
        for (Artifact artifact : pluginArtifacts)
        {
            if ("jar".equals(artifact.getType()))
            {
                //ignore slf4j from inside maven
                if (artifact.getGroupId().contains("slf4j") && !hasSlf4jDeps)
                    continue;
                if (classPath.length() > 0)
                {
                    classPath.append(File.pathSeparator);
                }
                classPath.append(artifact.getFile().getAbsolutePath());
            }
        }

        //Any jars that we need from the plugin environment (like the ones containing Starter class)
        Set<Artifact> extraJars = getExtraJars();
        for (Artifact a : extraJars)
        {
            classPath.append(File.pathSeparator);
            classPath.append(a.getFile().getAbsolutePath());
        }

        //Any jars that we need from the project's dependencies because we're useProvided
        List<String> providedJars = getProvidedJars();
        if (providedJars != null && !providedJars.isEmpty())
        {
            for (String jar : providedJars)
            {
                classPath.append(File.pathSeparator);
                classPath.append(jar);
                if (getLog().isDebugEnabled())
                    getLog().debug("Adding provided jar: " + jar);
            }
        }

        return classPath.toString();
    }

    public static String pathSeparators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == ',') || (c == ':'))
            {
                ret.append(File.pathSeparatorChar);
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    private String createToken()
    {
        return Long.toString(random.nextLong() ^ System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
    }
}

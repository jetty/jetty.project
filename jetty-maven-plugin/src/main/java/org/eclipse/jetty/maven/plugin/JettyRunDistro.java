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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * This goal is used to deploy the unassembled webapp into a jetty distribution. If the location
 * of an existing unpacked distribution is not supplied as the configuration param jettyHome,
 * this goal will download and unpack the jetty distro matching the version of this plugin before deploying the webapp.
 *
 * The webapp will execute in the distro in a forked process.
 *
 * The <b>stopKey</b>, <b>stopPort</b> configuration elements can be used to control the stopping of the forked process. By default, this plugin will launch
 * the forked jetty instance and wait for it to complete (in which case it acts much like the <b>jetty:run</b> goal, and you will need to Cntrl-C to stop).
 * By setting the configuration element <b>waitForChild</b> to <b>false</b>, the plugin will terminate after having forked the jetty process. In this case
 * you can use the <b>jetty:stop</b> goal to terminate the process.
 *
 * This goal does NOT support the <b>scanIntervalSeconds</b> parameter: the webapp will be deployed only once.
 *
 * See <a href="https://www.eclipse.org/jetty/documentation/">https://www.eclipse.org/jetty/documentation</a> for more information on this and other jetty plugins.
 *
 * Runs unassembled webapp in a locally installed jetty distro
 */
@Mojo(name = "run-distro", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class JettyRunDistro extends JettyRunMojo
{

    public static final String JETTY_HOME_GROUPID = "org.eclipse.jetty";
    public static final String JETTY_HOME_ARTIFACTID = "jetty-home";

    /**
     * This plugin
     */
    @Parameter(defaultValue = "${plugin}", required = true, readonly = true)
    protected PluginDescriptor plugin;

    /**
     * The target directory
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File target;

    /**
     * Optional jetty.home dir
     */
    @Parameter
    private File jettyHome;

    /**
     * Optional jetty.base dir
     */
    @Parameter
    private File jettyBase;

    /**
     * Optional list of other modules to
     * activate.
     */
    @Parameter
    private String[] modules;

    /**
     * Arbitrary jvm args to pass to the forked process
     */
    @Parameter(property = "jetty.jvmArgs")
    private String jvmArgs;

    /**
     * Extra environment variables to be passed to the forked process
     */
    @Parameter
    private Map<String, String> env = new HashMap<>();

    /**
     * Optional list of jetty properties to put on the command line
     */
    @Parameter
    private String[] jettyProperties;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    /**
     * The project's remote repositories to use for the resolution.
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
    private List<ArtifactRepository> remoteRepositories;

    @Component
    private ArtifactResolver artifactResolver;

    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    private String pluginVersion;

    /**
     * Whether to wait for the child to finish or not.
     */
    @Parameter(defaultValue = "true")
    private boolean waitForChild;

    /**
     * Max number of times to try checking if the
     * child has started successfully.
     */
    @Parameter(defaultValue = "10")
    private int maxChildChecks;

    /**
     * Millisecs to wait between each
     * check to see if the child started successfully.
     */
    @Parameter(defaultValue = "100")
    private long maxChildCheckInterval;

    private File targetBase;

    private List<Dependency> libExtJars;

    private Random random;

    private Path tokenFile;

    @Parameter(property = "jetty.javaPath")
    private String javaPath;

    /**
     * @see org.eclipse.jetty.maven.plugin.JettyRunMojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        random = new Random();
        List<Dependency> pdeps = plugin.getPlugin().getDependencies();
        if (pdeps != null && !pdeps.isEmpty())
        {
            boolean warned = false;
            for (Dependency d : pdeps)
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
                    if (libExtJars == null)
                        libExtJars = new ArrayList<>();
                    libExtJars.add(d);
                }
            }
        }

        super.execute();
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#startJetty()
     */
    @Override
    public void startJetty() throws MojoExecutionException
    {
        //don't start jetty locally, set up enough configuration to run a new process
        //with a jetty distro
        try
        {
            printSystemProperties();

            //download and install jetty-home if necessary
            configureJettyHome();

            //ensure config of the webapp based on settings in plugin
            configureWebApplication();

            //configure jetty base
            configureJettyBase();

            //create the command to run the new process
            ProcessBuilder command = configureCommand();

            if (waitForChild)
            {
                command.inheritIO();
            }
            else
            {
                command.redirectOutput(new File(target, "jetty.out"));
                command.redirectErrorStream(true);
            }

            Process process = command.start();

            if (waitForChild)
                //keep executing until the child dies
                process.waitFor();
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
        catch (Exception e)
        {
            throw new MojoExecutionException("Failed to start Jetty", e);
        }
    }

    /**
     * If jetty home does not exist, download it and
     * unpack to build dir.
     *
     * @throws Exception if jetty distribution cannot be found neither downloaded
     */
    public void configureJettyHome() throws Exception
    {
        if (jettyHome == null)
        {
            //no jetty home, download from repo and unpack it. Get the same version as the plugin
            Artifact jettyHomeArtifact = resolveArtifact(JETTY_HOME_GROUPID, JETTY_HOME_ARTIFACTID, pluginVersion, "zip");
            JarResource res = (JarResource)JarResource.newJarResource(Resource.newResource(jettyHomeArtifact.getFile()));
            res.copyTo(target);
            //zip will unpack to target/jetty-home-<VERSION>
            jettyHome = new File(target, JETTY_HOME_ARTIFACTID + "-" + pluginVersion);
        }
        else
        {
            if (!jettyHome.exists())
                throw new IllegalStateException(jettyHome.getAbsolutePath() + " does not exist");
        }

        getLog().info("jetty.home = " + jettyHome.getAbsolutePath());
    }

    /**
     * Resolve an Artifact from remote repo if necessary.
     *
     * @param groupId the groupid of the artifact
     * @param artifactId the artifactId of the artifact
     * @param version the version of the artifact
     * @param extension the extension type of the artifact eg "zip", "jar"
     * @return the artifact from the local or remote repo
     * @throws ArtifactResolverException in case of an error while resolving the artifact
     */
    public Artifact resolveArtifact(String groupId, String artifactId, String version, String extension)
        throws ArtifactResolverException
    {
        DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
        coordinate.setGroupId(groupId);
        coordinate.setArtifactId(artifactId);
        coordinate.setVersion(version);
        coordinate.setExtension(extension);

        ProjectBuildingRequest buildingRequest =
            new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        buildingRequest.setRemoteRepositories(remoteRepositories);

        return artifactResolver.resolveArtifact(buildingRequest, coordinate).getArtifact();
    }

    /**
     * Create or configure a jetty base.
     *
     * @throws Exception if any error occurred while copying files
     */
    public void configureJettyBase() throws Exception
    {
        if (jettyBase != null && !jettyBase.exists())
            throw new IllegalStateException(jettyBase.getAbsolutePath() + " does not exist");

        targetBase = new File(target, "jetty-base");
        Path targetBasePath = targetBase.toPath();
        if (Files.exists(targetBasePath))
            IO.delete(targetBase);

        targetBase.mkdirs();

        if (jettyBase != null)
        {
            Path jettyBasePath = jettyBase.toPath();

            //copy the existing jetty base
            Files.walkFileTree(jettyBasePath, EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>()
                {
                    /**
                     * @see java.nio.file.SimpleFileVisitor#preVisitDirectory(java.lang.Object, java.nio.file.attribute.BasicFileAttributes)
                     */
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
                    {
                        Path targetDir = targetBasePath.resolve(jettyBasePath.relativize(dir));
                        try
                        {
                            Files.copy(dir, targetDir);
                        }
                        catch (FileAlreadyExistsException e)
                        {
                            if (!Files.isDirectory(targetDir)) //ignore attempt to recreate dir
                                throw e;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    /**
                     * @see java.nio.file.SimpleFileVisitor#visitFile(java.lang.Object, java.nio.file.attribute.BasicFileAttributes)
                     */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                    {
                        if (contextXml != null && Files.isSameFile(Paths.get(contextXml), file))
                            return FileVisitResult.CONTINUE; //skip copying the context xml file
                        Files.copy(file, targetBasePath.resolve(jettyBasePath.relativize(file)));
                        return FileVisitResult.CONTINUE;
                    }
                });
        }

        //make the jetty base structure
        Path modulesPath = Files.createDirectories(targetBasePath.resolve("modules"));
        Path etcPath = Files.createDirectories(targetBasePath.resolve("etc"));
        Path libPath = Files.createDirectories(targetBasePath.resolve("lib"));
        Path webappPath = Files.createDirectories(targetBasePath.resolve("webapps"));
        Path mavenLibPath = Files.createDirectories(libPath.resolve("maven"));

        //copy in the jetty-maven-plugin jar
        URI thisJar = TypeUtil.getLocationOfClass(this.getClass());
        if (thisJar == null)
            throw new IllegalStateException("Can't find jar for jetty-maven-plugin");

        try (InputStream jarStream = thisJar.toURL().openStream();
             FileOutputStream fileStream = new FileOutputStream(mavenLibPath.resolve("plugin.jar").toFile()))
        {
            IO.copy(jarStream, fileStream);
        }

        //copy in the maven.xml webapp file
        try (InputStream mavenXmlStream = getClass().getClassLoader().getResourceAsStream("maven.xml");
             FileOutputStream fileStream = new FileOutputStream(webappPath.resolve("maven.xml").toFile()))
        {
            IO.copy(mavenXmlStream, fileStream);
        }

        //copy in the maven.mod file
        try (InputStream mavenModStream = getClass().getClassLoader().getResourceAsStream("maven.mod");
             FileOutputStream fileStream = new FileOutputStream(modulesPath.resolve("maven.mod").toFile()))
        {
            IO.copy(mavenModStream, fileStream);
        }

        //copy in the jetty-maven.xml file
        try (InputStream jettyMavenStream = getClass().getClassLoader().getResourceAsStream("jetty-maven.xml");
             FileOutputStream fileStream = new FileOutputStream(etcPath.resolve("jetty-maven.xml").toFile()))
        {
            IO.copy(jettyMavenStream, fileStream);
        }

        //if there were plugin dependencies, copy them into lib/ext
        if (libExtJars != null && !libExtJars.isEmpty())
        {
            Path libExtPath = Files.createDirectories(libPath.resolve("ext"));
            for (Dependency d : libExtJars)
            {
                Artifact a = resolveArtifact(d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getType());
                try (InputStream jarStream = new FileInputStream(a.getFile());
                     FileOutputStream fileStream = new FileOutputStream(libExtPath.resolve(d.getGroupId() + "." + d.getArtifactId() + "-" + d.getVersion() + "." + d.getType()).toFile()))
                {
                    IO.copy(jarStream, fileStream);
                }
            }
        }

        //create properties file that describes the webapp
        createPropertiesFile(etcPath.resolve("maven.props").toFile());
    }

    /**
     * Convert webapp config to properties
     *
     * @param file the file to place the properties into
     * @throws Exception if any I/O exception during generating the properties file
     */
    public void createPropertiesFile(File file)
        throws Exception
    {
        WebAppPropertyConverter.toProperties(webApp, file, contextXml);
    }

    /**
     * Make the command to spawn a process to
     * run jetty from a distro.
     *
     * @return the command configured
     */
    public ProcessBuilder configureCommand()
    {
        List<String> cmd = new ArrayList<>();
        if (StringUtil.isNotBlank(javaPath))
        {
            cmd.add(javaPath);
        }
        else
        {
            cmd.add(getJavaBin());
        }
        cmd.add("-jar");
        cmd.add(new File(jettyHome, "start.jar").getAbsolutePath());

        cmd.add("-DSTOP.PORT=" + stopPort);
        if (stopKey != null)
            cmd.add("-DSTOP.KEY=" + stopKey);

        //add any args to the jvm
        if (jvmArgs != null)
        {
            String[] args = jvmArgs.split(" ");
            for (String a : args)
            {
                if (!StringUtil.isBlank(a))
                    cmd.add(a.trim());
            }
        }

        //set up enabled jetty modules
        StringBuilder tmp = new StringBuilder();
        tmp.append("--module=");
        tmp.append("server,http,webapp,deploy");
        if (modules != null)
        {
            for (String m : modules)
            {
                if (tmp.indexOf(m) < 0)
                    tmp.append("," + m);
            }
        }

        if (libExtJars != null && !libExtJars.isEmpty() && tmp.indexOf("ext") < 0)
            tmp.append(",ext");
        tmp.append(",maven");
        cmd.add(tmp.toString());

        //put any jetty properties onto the command line
        if (jettyProperties != null)
        {
            for (String p : jettyProperties)
            {
                cmd.add(p);
            }
        }

        //existence of this file signals process started
        tokenFile = target.toPath().resolve(createToken() + ".txt");
        cmd.add("jetty.token.file=" + tokenFile.toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(targetBase);

        //set up extra environment vars if there are any
        if (!env.isEmpty())
            builder.environment().putAll(env);

        return builder;
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#startScanner()
     */
    @Override
    public void startScanner() throws Exception
    {
        //don't scan
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#stopScanner()
     */
    @Override
    public void stopScanner() throws Exception
    {
        //don't scan
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#restartWebApp(boolean)
     */
    @Override
    public void restartWebApp(boolean reconfigureScanner) throws Exception
    {
        //do nothing
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureScanner()
     */
    @Override
    public void configureScanner() throws MojoExecutionException
    {
        //do nothing
    }

    private String createToken()
    {
        return Long.toString(random.nextLong() ^ System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
    }
}

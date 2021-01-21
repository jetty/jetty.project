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
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.maven.plugin.utils.MavenProjectHelper;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * This goal is used in-situ on a Maven project without first requiring that the project
 * is assembled into a war, saving time during the development cycle.
 * <p>
 * The plugin forks a parallel lifecycle to ensure that the "compile" phase has been completed before invoking Jetty. This means
 * that you do not need to explicitly execute a "mvn compile" first. It also means that a "mvn clean jetty:run" will ensure that
 * a full fresh compile is done before invoking Jetty.
 * <p>
 * Once invoked, the plugin can be configured to run continuously, scanning for changes in the project and automatically performing a
 * hot redeploy when necessary. This allows the developer to concentrate on coding changes to the project using their IDE of choice and have those changes
 * immediately and transparently reflected in the running web container, eliminating development time that is wasted on rebuilding, reassembling and redeploying.
 * <p>
 * You may also specify the location of a jetty.xml file whose contents will be applied before any plugin configuration.
 * This can be used, for example, to deploy a static webapp that is not part of your maven build.
 * <p>
 * There is a <a href="https://www.eclipse.org/jetty/documentation/current/maven-and-jetty.html">reference guide</a> to the configuration parameters for this plugin.
 *
 * Runs jetty directly from a maven project
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class JettyRunMojo extends AbstractJettyMojo
{
    public static final String DEFAULT_WEBAPP_SRC = "src" + File.separator + "main" + File.separator + "webapp";
    public static final String FAKE_WEBAPP = "webapp-tmp";

    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     */
    @Parameter(alias = "useTestClasspath", defaultValue = "false")
    protected boolean useTestScope;

    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     */
    @Parameter(defaultValue = "${maven.war.webxml}", readonly = true)
    protected String webXml;

    /**
     * The directory containing generated classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classesDirectory;

    /**
     * An optional pattern for includes/excludes of classes in the classesDirectory
     */
    @Parameter
    protected ScanPattern scanClassesPattern;

    /**
     * The directory containing generated test classes.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    protected File testClassesDirectory;

    /**
     * An optional pattern for includes/excludes of classes in the testClassesDirectory
     */
    @Parameter
    protected ScanPattern scanTestClassesPattern;

    /**
     * Root directory for all html/jsp etc files
     */
    @Parameter(defaultValue = "${maven.war.src}")
    protected File webAppSourceDirectory;

    /**
     * List of files or directories to additionally periodically scan for changes. Optional.
     */
    @Parameter
    protected File[] scanTargets;

    /**
     * List of directories with ant-style &lt;include&gt; and &lt;exclude&gt; patterns
     * for extra targets to periodically scan for changes. Can be used instead of,
     * or in conjunction with &lt;scanTargets&gt;.Optional.
     */
    @Parameter
    protected ScanTargetPattern[] scanTargetPatterns;

    /**
     * maven-war-plugin reference
     */
    protected WarPluginInfo warPluginInfo;

    /**
     * List of deps that are wars
     */
    protected List<Artifact> warArtifacts;

    protected Resource originalBaseResource;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        warPluginInfo = new WarPluginInfo(project);
        super.execute();
    }

    /**
     * Verify the configuration given in the pom.
     */
    @Override
    public boolean checkPomConfiguration() throws MojoExecutionException
    {
        // check the location of the static content/jsps etc
        try
        {
            if ((webAppSourceDirectory == null) || !webAppSourceDirectory.exists())
            {
                getLog().info("webAppSourceDirectory" + (webAppSourceDirectory == null ? " not set." : (webAppSourceDirectory.getAbsolutePath() + " does not exist.")) + " Trying " + DEFAULT_WEBAPP_SRC);
                webAppSourceDirectory = new File(project.getBasedir(), DEFAULT_WEBAPP_SRC);
                if (!webAppSourceDirectory.exists())
                {
                    getLog().info("webAppSourceDirectory " + webAppSourceDirectory.getAbsolutePath() + " does not exist. Trying " + project.getBuild().getDirectory() + File.separator + FAKE_WEBAPP);

                    //try last resort of making a fake empty dir
                    File target = new File(project.getBuild().getDirectory());
                    webAppSourceDirectory = new File(target, FAKE_WEBAPP);
                    if (!webAppSourceDirectory.exists())
                        webAppSourceDirectory.mkdirs();
                }
            }
            else
                getLog().info("Webapp source directory = " + webAppSourceDirectory.getCanonicalPath());
        }
        catch (IOException e)
        {
            throw new MojoExecutionException("Webapp source directory does not exist", e);
        }

        // check reload mechanic
        if (!"automatic".equalsIgnoreCase(reload) && !"manual".equalsIgnoreCase(reload))
        {
            throw new MojoExecutionException("invalid reload mechanic specified, must be 'automatic' or 'manual'");
        }
        else
        {
            getLog().info("Reload Mechanic: " + reload);
        }
        getLog().info("nonBlocking:" + nonBlocking);

        // check the classes to form a classpath with
        try
        {
            //allow a webapp with no classes in it (just jsps/html)
            if (classesDirectory != null)
            {
                if (!classesDirectory.exists())
                    getLog().info("Classes directory " + classesDirectory.getCanonicalPath() + " does not exist");
                else
                    getLog().info("Classes = " + classesDirectory.getCanonicalPath());
            }
            else
                getLog().info("Classes directory not set");
        }
        catch (IOException e)
        {
            throw new MojoExecutionException("Location of classesDirectory does not exist");
        }

        return true;
    }

    @Override
    public void finishConfigurationBeforeStart() throws Exception
    {
        server.setStopAtShutdown(true); //as we will normally be stopped with a cntrl-c, ensure server stopped 
        super.finishConfigurationBeforeStart();
    }

    @Override
    public void configureWebApplication() throws Exception
    {
        super.configureWebApplication();

        //Set up the location of the webapp.
        //There are 2 parts to this: setWar() and setBaseResource(). On standalone jetty,
        //the former could be the location of a packed war, while the latter is the location
        //after any unpacking. With this mojo, you are running an unpacked, unassembled webapp,
        //so the two locations should be equal.
        Resource webAppSourceDirectoryResource = Resource.newResource(webAppSourceDirectory.getCanonicalPath());
        if (webApp.getWar() == null)
            webApp.setWar(webAppSourceDirectoryResource.toString());

        //The first time we run, remember the original base dir
        if (originalBaseResource == null)
        {
            if (webApp.getBaseResource() == null)
                originalBaseResource = webAppSourceDirectoryResource;
            else
                originalBaseResource = webApp.getBaseResource();
        }

        //On every subsequent re-run set it back to the original base dir before
        //we might have applied any war overlays onto it
        webApp.setBaseResource(originalBaseResource);

        if (classesDirectory != null)
            webApp.setClasses(classesDirectory);
        if (useTestScope && (testClassesDirectory != null))
            webApp.setTestClasses(testClassesDirectory);

        MavenProjectHelper mavenProjectHelper = new MavenProjectHelper(project);
        List<File> webInfLibs = getWebInfLibArtifacts(project.getArtifacts()).stream()
            .map(a ->
            {
                Path p = mavenProjectHelper.getArtifactPath(a);
                getLog().debug("Artifact " + a.getId() + " loaded from " + p + " added to WEB-INF/lib");
                return p.toFile();
            }).collect(Collectors.toList());
        getLog().debug("WEB-INF/lib initialized (at root)");
        webApp.setWebInfLib(webInfLibs);

        //if we have not already set web.xml location, need to set one up
        if (webApp.getDescriptor() == null)
        {
            //Has an explicit web.xml file been configured to use?
            if (webXml != null)
            {
                Resource r = Resource.newResource(webXml);
                if (r.exists() && !r.isDirectory())
                {
                    webApp.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: try the resourceBase of the webapp, if it is set
            if (webApp.getDescriptor() == null && webApp.getBaseResource() != null)
            {
                Resource r = webApp.getBaseResource().addPath("WEB-INF/web.xml");
                if (r.exists() && !r.isDirectory())
                {
                    webApp.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: finally try the configured static resource directory if there is one
            if (webApp.getDescriptor() == null && (webAppSourceDirectory != null))
            {
                File f = new File(new File(webAppSourceDirectory, "WEB-INF"), "web.xml");
                if (f.exists() && f.isFile())
                {
                    webApp.setDescriptor(f.getCanonicalPath());
                }
            }
        }

        //process any overlays and the war type artifacts
        List<Overlay> overlays = getOverlays();
        unpackOverlays(overlays); //this sets up the base resource collection

        getLog().info("web.xml file = " + webApp.getDescriptor());
        getLog().info("Webapp directory = " + webAppSourceDirectory.getCanonicalPath());
    }

    @Override
    public void configureScanner()
        throws MojoExecutionException
    {
        try
        {
            gatherScannables();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error forming scan list", e);
        }
    }

    public void gatherScannables() throws Exception
    {
        if (webApp.getDescriptor() != null)
        {
            Resource r = Resource.newResource(webApp.getDescriptor());
            scanner.addFile(r.getFile().toPath());
        }

        if (webApp.getJettyEnvXml() != null)
            scanner.addFile(new File(webApp.getJettyEnvXml()).toPath());

        if (webApp.getDefaultsDescriptor() != null)
        {
            if (!WebAppContext.WEB_DEFAULTS_XML.equals(webApp.getDefaultsDescriptor()))
                scanner.addFile(new File(webApp.getDefaultsDescriptor()).toPath());
        }

        if (webApp.getOverrideDescriptor() != null)
        {
            scanner.addFile(new File(webApp.getOverrideDescriptor()).toPath());
        }

        File jettyWebXmlFile = findJettyWebXmlFile(new File(webAppSourceDirectory, "WEB-INF"));
        if (jettyWebXmlFile != null)
        {
            scanner.addFile(jettyWebXmlFile.toPath());
        }

        //make sure each of the war artifacts is added to the scanner
        for (Artifact a : getWarArtifacts())
        {
            File f = a.getFile();
            if (a.getFile().isDirectory())
                scanner.addDirectory(f.toPath());
            else
                scanner.addFile(f.toPath());
        }

        //handle the explicit extra scan targets
        if (scanTargets != null)
        {
            for (File f : scanTargets)
            {
                if (f.isDirectory())
                {
                    scanner.addDirectory(f.toPath());
                }
                else
                    scanner.addFile(f.toPath());
            }
        }
        
        scanner.addFile(project.getFile().toPath());
        
        //handle the extra scan patterns
        if (scanTargetPatterns != null)
        {
            for (ScanTargetPattern p : scanTargetPatterns)
            {
                IncludeExcludeSet<PathMatcher, Path> includesExcludes = scanner.addDirectory(p.getDirectory().toPath());
                p.configureIncludesExcludeSet(includesExcludes);
            }
        }

        if (webApp.getTestClasses() != null && webApp.getTestClasses().exists())
        {
            Path p = webApp.getTestClasses().toPath();
            IncludeExcludeSet<PathMatcher, Path> includeExcludeSet = scanner.addDirectory(p);

            if (scanTestClassesPattern != null)
            {
                for (String s : scanTestClassesPattern.getExcludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludeSet.exclude(p.getFileSystem().getPathMatcher(s));
                }
                for (String s : scanTestClassesPattern.getIncludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludeSet.include(p.getFileSystem().getPathMatcher(s));
                }
            }
        }

        if (webApp.getClasses() != null && webApp.getClasses().exists())
        {
            Path p = webApp.getClasses().toPath();
            IncludeExcludeSet<PathMatcher, Path> includeExcludes = scanner.addDirectory(p);
            if (scanClassesPattern != null)
            {
                for (String s : scanClassesPattern.getExcludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludes.exclude(p.getFileSystem().getPathMatcher(s));
                }

                for (String s : scanClassesPattern.getIncludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludes.include(p.getFileSystem().getPathMatcher(s));
                }
            }
        }

        if (webApp.getWebInfLib() != null)
        {
            for (File f : webApp.getWebInfLib())
            {
                if (f.isDirectory())
                    scanner.addDirectory(f.toPath());
                else
                    scanner.addFile(f.toPath());
            }
        }
    }

    @Override
    public void restartWebApp(boolean reconfigureScanner) throws Exception
    {
        getLog().info("restarting " + webApp);
        getLog().debug("Stopping webapp ...");
        stopScanner();
        webApp.stop();

        getLog().debug("Reconfiguring webapp ...");

        checkPomConfiguration();
        configureWebApplication();

        // check if we need to reconfigure the scanner,
        // which is if the pom changes
        if (reconfigureScanner)
        {
            getLog().info("Reconfiguring scanner after change to pom.xml ...");
            scanner.reset();
            warArtifacts = null;
            configureScanner();
        }

        getLog().debug("Restarting webapp ...");
        webApp.start();
        startScanner();
        getLog().info("Restart completed at " + new Date().toString());
    }

    private Collection<Artifact> getWebInfLibArtifacts(Set<Artifact> artifacts)
    {
        return artifacts.stream()
            .filter(this::canPutArtifactInWebInfLib)
            .collect(Collectors.toList());
    }

    private boolean canPutArtifactInWebInfLib(Artifact artifact)
    {
        if ("war".equalsIgnoreCase(artifact.getType()))
        {
            return false;
        }
        if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
        {
            return false;
        }
        return !Artifact.SCOPE_TEST.equals(artifact.getScope()) || useTestScope;
    }

    private List<Overlay> getOverlays()
        throws Exception
    {
        //get copy of a list of war artifacts
        Set<Artifact> matchedWarArtifacts = new HashSet<>();
        List<Overlay> overlays = new ArrayList<>();
        for (OverlayConfig config : warPluginInfo.getMavenWarOverlayConfigs())
        {
            //overlays can be individually skipped
            if (config.isSkip())
                continue;

            //an empty overlay refers to the current project - important for ordering
            if (config.isCurrentProject())
            {
                Overlay overlay = new Overlay(config, null);
                overlays.add(overlay);
                continue;
            }

            //if a war matches an overlay config
            Artifact a = getArtifactForOverlay(config, getWarArtifacts());
            if (a != null)
            {
                matchedWarArtifacts.add(a);
                SelectiveJarResource r = new SelectiveJarResource(new URL("jar:" + Resource.toURL(a.getFile()).toString() + "!/"));
                r.setIncludes(config.getIncludes());
                r.setExcludes(config.getExcludes());
                Overlay overlay = new Overlay(config, r);
                overlays.add(overlay);
            }
        }

        //iterate over the left over war artifacts and unpack them (without include/exclude processing) as necessary
        for (Artifact a : getWarArtifacts())
        {
            if (!matchedWarArtifacts.contains(a))
            {
                Overlay overlay = new Overlay(null, Resource.newResource(new URL("jar:" + Resource.toURL(a.getFile()).toString() + "!/")));
                overlays.add(overlay);
            }
        }
        return overlays;
    }

    public void unpackOverlays(List<Overlay> overlays)
        throws Exception
    {
        if (overlays == null || overlays.isEmpty())
            return;

        List<Resource> resourceBaseCollection = new ArrayList<>();

        for (Overlay o : overlays)
        {
            //can refer to the current project in list of overlays for ordering purposes
            if (o.getConfig() != null && o.getConfig().isCurrentProject() && webApp.getBaseResource().exists())
            {
                resourceBaseCollection.add(webApp.getBaseResource());
                continue;
            }

            Resource unpacked = unpackOverlay(o);
            //_unpackedOverlayResources.add(unpacked); //remember the unpacked overlays for later so we can delete the tmp files
            resourceBaseCollection.add(unpacked); //add in the selectively unpacked overlay in the correct order to the webapps resource base
        }

        if (!resourceBaseCollection.contains(webApp.getBaseResource()) && webApp.getBaseResource().exists())
        {
            if (webApp.getBaseAppFirst())
            {
                resourceBaseCollection.add(0, webApp.getBaseResource());
            }
            else
            {
                resourceBaseCollection.add(webApp.getBaseResource());
            }
        }
        webApp.setBaseResource(new ResourceCollection(resourceBaseCollection.toArray(new Resource[resourceBaseCollection.size()])));
    }

    public Resource unpackOverlay(Overlay overlay)
        throws IOException
    {
        if (overlay.getResource() == null)
            return null; //nothing to unpack

        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        String name = overlay.getResource().getName();
        if (name.endsWith("!/"))
            name = name.substring(0, name.length() - 2);
        int i = name.lastIndexOf('/');
        if (i > 0)
            name = name.substring(i + 1);
        name = StringUtil.replace(name, '.', '_');
        //name = name+(++COUNTER); //add some digits to ensure uniqueness
        File overlaysDir = new File(project.getBuild().getDirectory(), "jetty_overlays");
        File dir = new File(overlaysDir, name);

        //if specified targetPath, unpack to that subdir instead
        File unpackDir = dir;
        if (overlay.getConfig() != null && overlay.getConfig().getTargetPath() != null)
            unpackDir = new File(dir, overlay.getConfig().getTargetPath());

        //only unpack if the overlay is newer
        if (!unpackDir.exists() || (overlay.getResource().lastModified() > unpackDir.lastModified()))
        {
            boolean made = unpackDir.mkdirs();
            overlay.getResource().copyTo(unpackDir);
        }

        //use top level of unpacked content
        return Resource.newResource(dir.getCanonicalPath());
    }

    private List<Artifact> getWarArtifacts()
    {
        if (warArtifacts != null)
            return warArtifacts;

        warArtifacts = new ArrayList<>();
        for (Artifact artifact : projectArtifacts)
        {
            if (artifact.getType().equals("war") || artifact.getType().equals("zip"))
            {
                try
                {
                    warArtifacts.add(artifact);
                    getLog().info("Dependent war artifact " + artifact.getId());
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
        return warArtifacts;
    }

    protected Artifact getArtifactForOverlay(OverlayConfig o, List<Artifact> warArtifacts)
    {
        if (o == null || warArtifacts == null || warArtifacts.isEmpty())
            return null;

        for (Artifact a : warArtifacts)
        {
            if (o.matchesArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier()))
            {
                return a;
            }
        }

        return null;
    }

    protected String getJavaBin()
    {
        String[] javaexes = new String[]
            {"java", "java.exe"};

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir, fileSeparators("bin/" + javaexe));
            if (javabin.exists() && javabin.isFile())
            {
                return javabin.getAbsolutePath();
            }
        }

        return "java";
    }

    public static String fileSeparators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == '/') || (c == '\\'))
            {
                ret.append(File.separatorChar);
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }
}

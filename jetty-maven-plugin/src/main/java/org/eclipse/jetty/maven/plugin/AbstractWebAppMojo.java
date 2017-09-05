//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

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
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
/**
 * UnassembledWebAppMojo
 *
 */
public abstract class AbstractWebAppMojo extends AbstractMojo
{
    
    public static final String JETTY_HOME_GROUPID = "org.eclipse.jetty";
    public static final String JETTY_HOME_ARTIFACTID = "jetty-home";
    public static final String DEFAULT_WEBAPP_SRC = "src"+File.separator+"main"+File.separator+"webapp";
    public static final String FAKE_WEBAPP = "webapp-tmp";
    

    public enum RunTypes
    {
        INPROCESS,
        FORKED,
        DISTRO
    }
    
    
    /**
     * Whether or not to include dependencies on the plugin's classpath with &lt;scope&gt;provided&lt;/scope&gt;
     * Use WITH CAUTION as you may wind up with duplicate jars/classes.
     * 
     * @since jetty-7.5.2
     */
    @Parameter (defaultValue="false")
    protected boolean useProvidedScope;
    

    /**
     * List of goals that are NOT to be used
     * 
     * @since jetty-7.5.2
     */
    @Parameter
    protected String[] excludedGoals;
    
    /**
     * An instance of org.eclipse.jetty.webapp.WebAppContext that represents the webapp.
     * Use any of its setters to configure the webapp. This is the preferred and most
     * flexible method of configuration, rather than using the (deprecated) individual
     * parameters like "tmpDirectory", "contextPath" etc.
     * 
     */
    @Parameter
    protected JettyWebAppContext webApp;

    /**  
     * Skip this mojo execution.
     */
    @Parameter (property="jetty.skip", defaultValue="false")
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
    @Parameter(defaultValue="${project}", readonly=true)
    protected MavenProject project;

    
    /**
     * The artifacts for the project.
     */
    @Parameter (defaultValue="${project.artifacts}", readonly=true)
    protected Set<Artifact> projectArtifacts;
    
    /** 
     * The maven build executing.
     */    
    @Parameter (defaultValue="${mojoExecution}", readonly=true)
    protected org.apache.maven.plugin.MojoExecution execution;
    

    /**
     * The artifacts for the plugin itself.
     */    
    @Parameter (defaultValue="${plugin.artifacts}", readonly=true)
    protected List<Artifact> pluginArtifacts;
    

    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     */    
    @Parameter (defaultValue="false")
    protected boolean useTestScope;
    
    /**
     * The directory containing generated test classes.
     * 
     */
    @Parameter (defaultValue="${project.build.testOutputDirectory}", required=true)
    protected File testClassesDirectory;
    
    /**
     * An optional pattern for includes/excludes of classes in the testClassesDirectory
     */
    @Parameter
    protected ScanPattern scanTestClassesPattern;

    /**
     * The directory containing generated classes.
     */
    @Parameter (defaultValue="${project.build.outputDirectory}", required=true)
    protected File classesDirectory;
    

    /**
     * An optional pattern for includes/excludes of classes in the classesDirectory
     */
    @Parameter
    protected ScanPattern scanClassesPattern;
    

    /**
     * Root directory for all html/jsp etc files
     *
     * 
     * 
     */
    @Parameter (defaultValue="${maven.war.src}")
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
     * @parameter
     */
    protected ScanTargetPattern[] scanTargetPatterns;
    

    @Parameter(defaultValue = "${reactorProjects}", readonly=true, required=true)
    protected List<MavenProject> reactorProjects;
    
    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     */
    @Parameter (defaultValue="${maven.war.webxml}", readonly=true)
    protected String webXml;
    
    /**
     * The target directory
     */
    @Parameter (defaultValue="${project.build.directory}", required=true, readonly=true)
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
    protected Map<String,String> jettyProperties;

    
    /**
     * File containing system properties to be set before execution
     * 
     * Note that these properties will NOT override System properties
     * that have been set on the command line, by the JVM, or directly 
     * in the POM via systemProperties. Optional.
     * 
     * 
     */
    @Parameter (property="jetty.systemPropertiesFile")
    protected File systemPropertiesFile;

    
    /**
     * System properties to set before execution. 
     * Note that these properties will NOT override System properties 
     * that have been set on the command line or by the JVM. They WILL 
     * override System properties that have been set via systemPropertiesFile.
     * Optional.
     */
    @Parameter
    protected Map<String,String> systemProperties;
    
    /** 
     * Controls how to run jetty. Valid values are inprocess,forked,distro.
     * 
     * 
     */
    @Parameter (property="jetty.runType", defaultValue="INPROCESS") 
    protected RunTypes runType;
    
    
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
     * A ServerConnector to use.
     */
    @Parameter
    protected MavenServerConnector httpConnector;
    
    
    /**
     * A wrapper for the Server object
     */
    @Parameter
    protected Server server;
    //End of inprocess only
    

    //Start of parameters only valid for forked/distro
    /**
     * Extra environment variables to be passed to the forked process
     */
    @Parameter
    protected Map<String,String> env = new HashMap<String,String>();

    /**
     * Arbitrary jvm args to pass to the forked process
     */
    @Parameter (property="jetty.jvmArgs")
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
    //End of forked/distro parameters
    
    
    //Start of parameters only valid for distro
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
    //End of distro-only parameters
    
    
    //Start of parameters on valid for fork
    /**
     * The file into which to generate the quickstart web xml for the forked process to use
     * 
     */
    @Parameter (defaultValue="${project.build.directory}/fork-web.xml")
    protected File forkWebXml;
    //End of fork-only parameters
    /**
     * maven-war-plugin reference
     */
    protected WarPluginInfo warPluginInfo;
    
    /**
     * This plugin
     * 
     * @required
     */
    @Parameter (defaultValue="${plugin}", readonly=true, required=true)
    protected PluginDescriptor plugin;
    
    /**
     * The project's remote repositories to use for the resolution.
     *
     * @parameter default-value="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    private List<ArtifactRepository> remoteRepositories;

    /**
     * 
     */
    @Component
    private ArtifactResolver artifactResolver;
    
    /**
     * The current maven session
     */
    @Parameter (defaultValue="${session}", required=true, readonly=true)
    private MavenSession session;
    
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


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        //Doesn't apply to non-webapp projects
        if ( !"war".equals( project.getPackaging() ) || skip )
            return;



        getLog().info("Configuring Jetty for project: " + this.project.getName());
        if (skip)
        {
            getLog().info("Skipping Jetty start: jetty.skip==true");
            return;
        }

        if (isExcludedGoal(execution.getMojoDescriptor().getGoal()))
        {
            getLog().info("The goal \""+execution.getMojoDescriptor().getFullGoalName()+
                    "\" has been made unavailable for this web application by an <excludedGoal> configuration.");
            return;
        }

        warPluginInfo = new WarPluginInfo(project);
        configureSystemProperties();
        augmentPluginClasspath();
        PluginLog.setLog(getLog());
        verifyPomConfiguration();
        startJetty();
        
    }


    public void startJetty()
            throws MojoExecutionException
    {
        switch (runType)
        {
            case INPROCESS: 
            {
                startJettyInProcess();
                break;
            }
            case FORKED:
            {
                startJettyForked();
                break;
            }
            case DISTRO:
            {
                startJettyDistro();
                break;
            }
            default:
                throw new MojoExecutionException("Unrecognized runType="+runType);
        }
    }
    

    public abstract void startJettyInProcess() throws MojoExecutionException;
    
    public abstract void startJettyForked() throws MojoExecutionException;
    
    public abstract void startJettyDistro() throws MojoExecutionException;



    public JettyEmbedder newJettyEmbedder()
    throws Exception
    {
        JettyEmbedder jetty = new JettyEmbedder();
        jetty.setServer(server);
        jetty.setContextHandlers(contextHandlers);
        jetty.setRequestLog(requestLog);
        jetty.setJettyXmlFiles(jettyXmls);
        jetty.setHttpConnector(httpConnector);
        jetty.setJettyProperties(jettyProperties);
        jetty.setRequestLog(requestLog);
        jetty.setContextHandlers(contextHandlers);
        jetty.setLoginServices(loginServices);

        configureWebApp();

        Properties webAppProperties = null;
        if (contextXml != null)
        {
            webAppProperties = new Properties();
            webAppProperties.put("context.xml", contextXml);
        }
        jetty.setWebApp(webApp, webAppProperties);
        return jetty;
    }



    public JettyForker newJettyForker()
    throws Exception
    {

        JettyForker jetty = new JettyForker();
        jetty.setServer(server);
        jetty.setWorkDir(project.getBasedir());
        jetty.setStopKey(stopKey);
        jetty.setStopPort(stopPort);
        jetty.setEnv(env);
        jetty.setJvmArgs(jvmArgs);
        //TODO systemproperties systempropertiesfile
        jetty.setContainerClassPath(getContainerClassPath());
        jetty.setJettyXmlFiles(jettyXmls);
        jetty.setJettyProperties(jettyProperties);
        jetty.setForkWebXml(forkWebXml);
        
        jetty.setWebAppPropsFile(new File(target, "webApp.props"));
        Random random = new Random();
        String token = Long.toString(random.nextLong()^System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
        jetty.setTokenFile(target.toPath().resolve(token+".txt").toFile());

        configureWebApp();
        
        Properties webAppProperties = null;
        if (contextXml != null)
        {
            webAppProperties = new Properties();
            webAppProperties.put("context.xml", contextXml);
        }
        jetty.setWebApp(webApp, webAppProperties);
        return jetty;
    }

    
    public JettyDistroForker newJettyDistroForker()
    throws Exception
    {
        JettyDistroForker jetty = new JettyDistroForker();
       
        jetty.setStopKey(stopKey);
        jetty.setStopPort(stopPort);
        jetty.setEnv(env);
        jetty.setJvmArgs(jvmArgs);
        jetty.setJettyXmlFiles(jettyXmls);
        jetty.setJettyProperties(jettyProperties);
        jetty.setModules(modules);
        //TODO systemproperties systempropertiesfile
        Random random = new Random();
        String token = Long.toString(random.nextLong()^System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
        jetty.setTokenFile(target.toPath().resolve(token+".txt").toFile());
       
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
                    libExtJars.add(resolveDependency(d));
                }
            }
            jetty.setLibExtJarFiles(libExtJars);
        }

        configureWebApp();

        jetty.setWebApp(webApp);     
        jetty.setContextXmlFile(new File(contextXml));

        if (jettyHome == null)        
            jetty.setJettyDistro(resolve(JETTY_HOME_GROUPID, JETTY_HOME_ARTIFACTID, plugin.getVersion(), "zip"));

        jetty.setJettyHome(jettyHome);
        jetty.setJettyBase(jettyBase);
        jetty.setBaseDir(target);
        
        return jetty;
    }


    public File resolveArtifact (Artifact a)
    throws ArtifactResolverException
    {
        return resolve (a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getType());
    }
    
    public File resolveDependency (Dependency d)
    throws ArtifactResolverException
    {
        return resolve (d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getType());
    }

    public File resolve(String groupId, String artifactId, String version, String type)
    throws ArtifactResolverException
    {

        DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
        coordinate.setGroupId(groupId);
        coordinate.setArtifactId(artifactId);
        coordinate.setVersion(version);
        coordinate.setExtension(type);

        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        buildingRequest.setRemoteRepositories(remoteRepositories);

        Artifact a = artifactResolver.resolveArtifact( buildingRequest, coordinate ).getArtifact();

        if (a != null)
            return a.getFile();
        return null;
    }

    

    /**
     * @return
     */
    public List<File> getProjectDependencyFiles()
    {
        List<File> dependencyFiles = new ArrayList<>();
        for ( Iterator<Artifact> iter = projectArtifacts.iterator(); iter.hasNext(); )
        {
            Artifact artifact = iter.next();
            
            // Include runtime and compile time libraries, and possibly test libs too
            if(artifact.getType().equals("war"))
            {
                continue;
            }
            MavenProject mavenProject = getProjectReferences( artifact, project );
            if (mavenProject != null)
            {
                File projectPath = Paths.get(mavenProject.getBuild().getOutputDirectory()).toFile();
                getLog().debug( "Adding project directory " + projectPath.toString() );
                dependencyFiles.add( projectPath );
                continue;
            }

            if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
                continue; //never add dependencies of scope=provided to the webapp's classpath (see also <useProvidedScope> param)

            if (Artifact.SCOPE_TEST.equals(artifact.getScope()) && !useTestScope)
                continue; //only add dependencies of scope=test if explicitly required

            dependencyFiles.add(artifact.getFile());
            getLog().debug( "Adding artifact " + artifact.getFile().getName() + " with scope "+artifact.getScope()+" for WEB-INF/lib " );   
        }

        return dependencyFiles; 
    }


    public MavenProject getProjectReferences( Artifact artifact, MavenProject project )
    {
        if ( project.getProjectReferences() == null || project.getProjectReferences().isEmpty() )
        {
            return null;
        }
        Collection<MavenProject> mavenProjects = project.getProjectReferences().values();
        for ( MavenProject mavenProject : mavenProjects )
        {
            if ( StringUtils.equals( mavenProject.getId(), artifact.getId() ) )
            {
                return mavenProject;
            }
        }
        return null;
    }

    public List<Overlay> getOverlays()
            throws Exception
    {
        //get copy of a list of war artifacts
        Set<Artifact> matchedWarArtifacts = new HashSet<Artifact>();
        List<Overlay> overlays = new ArrayList<Overlay>();
        for (OverlayConfig config:warPluginInfo.getMavenWarOverlayConfigs())
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
                SelectiveJarResource r = new SelectiveJarResource(new URL("jar:"+Resource.toURL(a.getFile()).toString()+"!/"));
                r.setIncludes(config.getIncludes());
                r.setExcludes(config.getExcludes());
                Overlay overlay = new Overlay(config, r);
                overlays.add(overlay);
            }
        }

        //iterate over the left over war artifacts and unpack them (without include/exclude processing) as necessary
        for (Artifact a: getWarArtifacts())
        {
            if (!matchedWarArtifacts.contains(a))
            {
                Overlay overlay = new Overlay(null, Resource.newResource(new URL("jar:"+Resource.toURL(a.getFile()).toString()+"!/")));
                overlays.add(overlay);
            }
        }
        return overlays;
    }


    public void unpackOverlays (List<Overlay> overlays)
    throws Exception
    {
        if (overlays == null || overlays.isEmpty())
            return;

        List<Resource> resourceBaseCollection = new ArrayList<Resource>();

        for (Overlay o:overlays)
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




    public  Resource unpackOverlay (Overlay overlay)
    throws IOException
    {        
        if (overlay.getResource() == null)
            return null; //nothing to unpack

        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        String name = overlay.getResource().getName();
        if (name.endsWith("!/"))
            name = name.substring(0,name.length()-2);
        int i = name.lastIndexOf('/');
        if (i>0)
            name = name.substring(i+1,name.length());
        name = name.replace('.', '_');
        //name = name+(++COUNTER); //add some digits to ensure uniqueness
        File overlaysDir = new File (project.getBuild().getDirectory(), "jetty_overlays");
        File dir = new File(overlaysDir, name);

        //if specified targetPath, unpack to that subdir instead
        File unpackDir = dir;
        if (overlay.getConfig() != null && overlay.getConfig().getTargetPath() != null)
            unpackDir = new File (dir, overlay.getConfig().getTargetPath());

        //only unpack if the overlay is newer
        if (!unpackDir.exists() || (overlay.getResource().lastModified() > unpackDir.lastModified()))
        {
            boolean made=unpackDir.mkdirs();
            overlay.getResource().copyTo(unpackDir);
        }

        //use top level of unpacked content
        return Resource.newResource(dir.getCanonicalPath());
    }


    /**
     * @return
     */
    public List<Artifact> getWarArtifacts ()
    {
        if (warArtifacts != null)
            return warArtifacts;       

        warArtifacts = new ArrayList<>();
        for ( Iterator<Artifact> iter = projectArtifacts.iterator(); iter.hasNext(); )
        {
            Artifact artifact = iter.next();
            if (artifact.getType().equals("war") || artifact.getType().equals("zip"))
            {
                try
                {                  
                    warArtifacts.add(artifact);
                    getLog().info("Dependent war artifact "+artifact.getId());
                }
                catch(Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
        return warArtifacts;
    }

    protected Artifact getArtifactForOverlay (OverlayConfig o, List<Artifact> warArtifacts)
    {
        if (o == null || warArtifacts == null || warArtifacts.isEmpty())
            return null;

        for (Artifact a:warArtifacts)
        {
            if (o.matchesArtifact (a.getGroupId(), a.getArtifactId(), a.getClassifier()))
            {
                return a;
            }
        }

        return null;
    }
    
    
    /**
     * Verify the configuration given in the pom.
     * 
     * 
     */
    public void verifyPomConfiguration () throws MojoExecutionException
    {
        // check the location of the static content/jsps etc
        try
        {
            if ((webAppSourceDirectory == null) || !webAppSourceDirectory.exists())
            {  
                getLog().info("webAppSourceDirectory"+(webAppSourceDirectory == null ? " not set." : (webAppSourceDirectory.getAbsolutePath()+" does not exist."))+" Trying "+DEFAULT_WEBAPP_SRC);
                webAppSourceDirectory = new File (project.getBasedir(), DEFAULT_WEBAPP_SRC);             
                if (!webAppSourceDirectory.exists())
                {
                    getLog().info("webAppSourceDirectory "+webAppSourceDirectory.getAbsolutePath()+" does not exist. Trying "+project.getBuild().getDirectory()+File.separator+FAKE_WEBAPP);

                    //try last resort of making a fake empty dir
                    File target = new File(project.getBuild().getDirectory());
                    webAppSourceDirectory = new File(target, FAKE_WEBAPP);
                    if (!webAppSourceDirectory.exists())
                        webAppSourceDirectory.mkdirs();              
                }
            }
            else
                getLog().info( "Webapp source directory = " + webAppSourceDirectory.getCanonicalPath());
        }
        catch (IOException e)
        {
            throw new MojoExecutionException("Webapp source directory does not exist", e);
        }

        // check the classes to form a classpath with
        try
        {
            //allow a webapp with no classes in it (just jsps/html)
            if (classesDirectory != null)
            {
                if (!classesDirectory.exists())
                    getLog().info( "Classes directory "+ classesDirectory.getCanonicalPath()+ " does not exist");
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
    }


    public void configureSystemProperties ()
    throws MojoExecutionException
    {
        //Apply the file first
        if (systemPropertiesFile != null)
        {
            Properties properties = new Properties();
            try (InputStream propFile = new FileInputStream(systemPropertiesFile))
            {
                properties.load(propFile);
                System.setProperties(properties);
            }
            catch (Exception e)
            {
                throw new MojoExecutionException("Problem applying system properties from file "+systemPropertiesFile.getName(),e);
            }

        }
        
        //Then any system properties in pom.xml
        if (systemProperties != null)
        {
            for (Map.Entry<String, String> e:systemProperties.entrySet())
            {
                System.setProperty(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Augment jetty's classpath with dependencies marked as scope=provided
     * if useProvidedScope==true.
     * 
     * @throws MojoExecutionException
     */
    public void augmentPluginClasspath() throws MojoExecutionException
    {  
        //Filter out ones that will clash with jars that are plugin dependencies, then
        //create a new classloader that we setup in the parent chain.
        providedJars = getProvidedJars();

        if (providedJars != null && !providedJars.isEmpty())
        {
            try
            {
                URL[] urls = new URL[providedJars.size()];
                int i=0;
                for (File providedJar:providedJars)
                    urls[i++] = providedJar.toURI().toURL();
                URLClassLoader loader  = new URLClassLoader(urls, getClass().getClassLoader());
                Thread.currentThread().setContextClassLoader(loader);
                getLog().info("Plugin classpath augmented with <scope>provided</scope> dependencies: "+Arrays.toString(urls));
            }
            catch (MalformedURLException e)
            {
                throw new MojoExecutionException("Invalid url", e);
            }
        }
    }

    public List<File> getProvidedJars() throws MojoExecutionException
    {  
        //if we are configured to include the provided dependencies on the plugin's classpath
        //(which mimics being on jetty's classpath vs being on the webapp's classpath), we first
        //try and filter out ones that will clash with jars that are plugin dependencies, then
        //create a new classloader that we setup in the parent chain.
        if (useProvidedScope)
        {
            List<File> provided = new ArrayList<>();        
            for ( Iterator<Artifact> iter = project.getArtifacts().iterator(); iter.hasNext(); )
            {                   
                Artifact artifact = iter.next();
                if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()) && !isPluginArtifact(artifact))
                    provided.add(artifact.getFile());
            }
            return provided;
        }
        else
            return null;
    }

    public String getContainerClassPath() throws Exception
    {
        //Add in all the plugin artifacts
        StringBuilder classPath = new StringBuilder();
        for (Object obj : pluginArtifacts)
        {
            Artifact artifact = (Artifact) obj;
            if ("jar".equals(artifact.getType()) && !artifact.getGroupId().contains("slf4j"))
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
                if (getLog().isDebugEnabled()) getLog().debug("Adding provided jar: "+jar);
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
    public boolean isPluginArtifact(Artifact artifact)
    {
        if (pluginArtifacts == null || pluginArtifacts.isEmpty())
            return false;
        
        boolean isPluginArtifact = false;
        for (Artifact pluginArtifact: pluginArtifacts)
        {
            if (getLog().isDebugEnabled()) { getLog().debug("Checking "+pluginArtifact);}
            if (pluginArtifact.getGroupId().equals(artifact.getGroupId()) && pluginArtifact.getArtifactId().equals(artifact.getArtifactId()))
                break;
        }
        
        return isPluginArtifact;
    }
    
    
    /**
     * Check if the goal that we're executing as is excluded or not.
     * 
     * @param goal the goal to check
     * @return true if the goal is excluded, false otherwise
     */
    public boolean isExcludedGoal (String goal)
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
    
    
    public void configureWebApp ()
    throws Exception
    {
        if (webApp == null)
            webApp = new JettyWebAppContext();
        
        //If no contextPath was specified, go with default of project artifactid
        String cp = webApp.getContextPath();
        if (cp == null || "".equals(cp))
        {
            cp = "/"+project.getArtifactId();
            webApp.setContextPath(cp);
        }        

        //If no tmp directory was specified, and we have one, use it
        if (webApp.getTempDirectory() == null)
        {
            File target = new File(project.getBuild().getDirectory());
            File tmp = new File(target,"tmp");
            if (!tmp.exists())
                tmp.mkdirs();            
            webApp.setTempDirectory(tmp);
        }
        
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
        {
            webApp.setClasses (classesDirectory);
            System.err.println("Classes dir="+classesDirectory);
        }
        else
            System.err.println("NO CLASSES DIR");
        if (useTestScope && (testClassesDirectory != null))
            webApp.setTestClasses (testClassesDirectory);

        webApp.setWebInfLib(getProjectDependencyFiles());


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
                 File f = new File (new File (webAppSourceDirectory, "WEB-INF"), "web.xml");
                 if (f.exists() && f.isFile())
                 {
                    webApp.setDescriptor(f.getCanonicalPath());
                 }
             }
        }

        //process any overlays and the war type artifacts
        List<Overlay> overlays = getOverlays();
        unpackOverlays(overlays); //this sets up the base resource collection

        getLog().info("web.xml file = "+webApp.getDescriptor());       
        getLog().info("Webapp directory = " + webAppSourceDirectory.getCanonicalPath());
        getLog().info("Context path = " + webApp.getContextPath());
        getLog().info("Tmp directory = "+ (webApp.getTempDirectory()== null? " determined at runtime": webApp.getTempDirectory()));
        getLog().info("Web defaults = "+(webApp.getDefaultsDescriptor()==null?" jetty default":webApp.getDefaultsDescriptor()));
        getLog().info("Web overrides = "+(webApp.getOverrideDescriptor()==null?" none":webApp.getOverrideDescriptor()));
    }
    
    
    /**
     * Try and find a jetty-web.xml file, using some
     * historical naming conventions if necessary.
     * @param webInfDir the web inf directory
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
}

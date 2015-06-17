//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jetty.util.PathWatcher;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 *  This goal is used in-situ on a Maven project without first requiring that the project 
 *  is assembled into a war, saving time during the development cycle.
 *  <p>
 *  The plugin forks a parallel lifecycle to ensure that the "compile" phase has been completed before invoking Jetty. This means
 *  that you do not need to explicity execute a "mvn compile" first. It also means that a "mvn clean jetty:run" will ensure that
 *  a full fresh compile is done before invoking Jetty.
 *  <p>
 *  Once invoked, the plugin can be configured to run continuously, scanning for changes in the project and automatically performing a 
 *  hot redeploy when necessary. This allows the developer to concentrate on coding changes to the project using their IDE of choice and have those changes
 *  immediately and transparently reflected in the running web container, eliminating development time that is wasted on rebuilding, reassembling and redeploying.
 *  <p>
 *  You may also specify the location of a jetty.xml file whose contents will be applied before any plugin configuration.
 *  This can be used, for example, to deploy a static webapp that is not part of your maven build. 
 *  <p>
 *  There is a <a href="http://www.eclipse.org/jetty/documentation/current/maven-and-jetty.html">reference guide</a> to the configuration parameters for this plugin.
 * 
 * @goal run
 * @requiresDependencyResolution test
 * @execute phase="test-compile"
 * @description Runs jetty directly from a maven project
 */
public class JettyRunMojo extends AbstractJettyMojo
{
    public static final String DEFAULT_WEBAPP_SRC = "src"+File.separator+"main"+File.separator+"webapp";
    public static final String FAKE_WEBAPP = "webapp-tmp";
    
    

    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     * 
     * @parameter alias="useTestClasspath" default-value="false"
     */
    protected boolean useTestScope;
    
  
    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     * 
     * @parameter expression="${maven.war.webxml}"
     * @readonly
     */
    protected String webXml;
    
    
    /**
     * The directory containing generated classes.
     *
     * @parameter expression="${project.build.outputDirectory}"
     * @required
     * 
     */
    protected File classesDirectory;
    
    /**
     * An optional pattern for includes/excludes of classes in the classesDirectory
     * @parameter
     */
    protected ScanPattern classesPattern;
    
    
    
    
    /**
     * The directory containing generated test classes.
     * 
     * @parameter expression="${project.build.testOutputDirectory}"
     * @required
     */
    protected File testClassesDirectory;
    
    /**
     * An optional pattern for includes/excludes of classes in the testClassesDirectory
     * @parameter
     */
    protected ScanPattern testClassesPattern;
    
   
    
    /**
     * Root directory for all html/jsp etc files
     *
     * @parameter expression="${maven.war.src}"
     * 
     */
    protected File webAppSourceDirectory;
    
 
    /**
     * List of files or directories to additionally periodically scan for changes. Optional.
     * @parameter
     */
    protected File[] scanTargets;
    
    
    /**
     * List of directories with ant-style &lt;include&gt; and &lt;exclude&gt; patterns
     * for extra targets to periodically scan for changes. Can be used instead of,
     * or in conjunction with &lt;scanTargets&gt;.Optional.
     * @parameter
     */
    protected ScanTargetPattern[] scanTargetPatterns;

    
    /**
     * maven-war-plugin reference
     */
    protected WarPluginInfo warPluginInfo;
    
    
    /**
     * List of deps that are wars
     */
    protected List<Artifact> warArtifacts;
    
    
    
    
    
    
    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        warPluginInfo = new WarPluginInfo(project);
        super.execute();
    }
    
    
    
    
    /**
     * Verify the configuration given in the pom.
     * 
     * @see AbstractJettyMojo#checkPomConfiguration()
     */
    public void checkPomConfiguration () throws MojoExecutionException
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
        
        // check reload mechanic
        if ( !"automatic".equalsIgnoreCase( reload ) && !"manual".equalsIgnoreCase( reload ) )
        {
            throw new MojoExecutionException( "invalid reload mechanic specified, must be 'automatic' or 'manual'" );
        }
        else
        {
            getLog().info("Reload Mechanic: " + reload );
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

   


    @Override
    public void finishConfigurationBeforeStart() throws Exception
    {
        server.setStopAtShutdown(true); //as we will normally be stopped with a cntrl-c, ensure server stopped 
        super.finishConfigurationBeforeStart();
    }




    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureWebApplication()
     */
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
       
       if (webApp.getBaseResource() == null)
               webApp.setBaseResource(webAppSourceDirectoryResource);

       if (classesDirectory != null)
           webApp.setClasses (classesDirectory);
       if (useTestScope && (testClassesDirectory != null))
           webApp.setTestClasses (testClassesDirectory);
       
       webApp.setWebInfLib (getDependencyFiles());

       //get copy of a list of war artifacts
       Set<Artifact> matchedWarArtifacts = new HashSet<Artifact>();



       //process any overlays and the war type artifacts
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

       webApp.setOverlays(overlays);
       
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
        getLog().info( "web.xml file = "+webApp.getDescriptor());       
        getLog().info("Webapp directory = " + webAppSourceDirectory.getCanonicalPath());
    }
    
    

    
    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureScanner()
     */
    public void configureScanner ()
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

        scanner.addListener(new PathWatcher.EventListListener()
        {

            @Override
            public void onPathWatchEvents(List<PathWatchEvent> events)
            {
                try
                {
                    boolean reconfigure = false;
                    if (events != null)
                    {
                        for (PathWatchEvent e:events)
                        {
                            if (e.getPath().equals(project.getFile().toPath()))
                            {
                                reconfigure = true;
                                break;
                            }
                        }
                    }

                    restartWebApp(reconfigure);
                }
                catch (Exception e)
                {
                    getLog().error("Error reconfiguring/restarting webapp after change in watched files",e);
                }
            }
        });
    }

    
    public void gatherScannables() throws Exception
    {
        if (webApp.getDescriptor() != null)
        {
            Resource r = Resource.newResource(webApp.getDescriptor());
            scanner.watch(r.getFile().toPath());
        }
        
        if (webApp.getJettyEnvXml() != null)
            scanner.watch(new File(webApp.getJettyEnvXml()).toPath());

        if (webApp.getDefaultsDescriptor() != null)
        {
            if (!WebAppContext.WEB_DEFAULTS_XML.equals(webApp.getDefaultsDescriptor()))
                scanner.watch(new File(webApp.getDefaultsDescriptor()).toPath());
        }

        if (webApp.getOverrideDescriptor() != null)
        {
            scanner.watch(new File(webApp.getOverrideDescriptor()).toPath());
        }
        
        File jettyWebXmlFile = findJettyWebXmlFile(new File(webAppSourceDirectory,"WEB-INF"));
        if (jettyWebXmlFile != null)
        {
            scanner.watch(jettyWebXmlFile.toPath());
        }
        
        //make sure each of the war artifacts is added to the scanner
        for (Artifact a:getWarArtifacts())
        {
            scanner.watch(a.getFile().toPath());
        }
        
        //handle the explicit extra scan targets
        if (scanTargets != null)
        {
            for (File f:scanTargets)
            {
                if (f.isDirectory())
                {
                    PathWatcher.Config config = new PathWatcher.Config(f.toPath());
                    config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                    scanner.watch(config);
                }
                else
                    scanner.watch(f.toPath());
            }
        }
        
        //handle the extra scan patterns
        if (scanTargetPatterns != null)
        {
            for (ScanTargetPattern p:scanTargetPatterns)
            {
                PathWatcher.Config config = new PathWatcher.Config(p.getDirectory().toPath());
                config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                for (String pattern:p.getExcludes())
                    config.addExcludeGlobRelative(pattern);
                for (String pattern:p.getIncludes())
                    config.addIncludeGlobRelative(pattern);
                scanner.watch(config);
            }
        }
      

        scanner.watch(project.getFile().toPath());

        if (webApp.getTestClasses() != null)
        {
            PathWatcher.Config config = new PathWatcher.Config(webApp.getTestClasses().toPath());
            config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);           
            if (testClassesPattern != null)
            {
                for (String p:testClassesPattern.getExcludes())
                    config.addExcludeGlobRelative(p);
                for (String p:testClassesPattern.getIncludes())
                    config.addIncludeGlobRelative(p);
            }
            scanner.watch(config);
        }
        
        if (webApp.getClasses() != null)
        {
            PathWatcher.Config config = new PathWatcher.Config(webApp.getClasses().toPath());
            config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
            if (classesPattern != null)
            {
                for (String p:classesPattern.getExcludes())
                    config.addExcludeGlobRelative(p);

                for (String p:classesPattern.getIncludes())
                    config.addIncludeGlobRelative(p);

            }
            scanner.watch(config);
        }

        if (webApp.getWebInfLib() != null)
        {
            for (File f:webApp.getWebInfLib())
            {
                PathWatcher.Config config = new PathWatcher.Config(f.toPath());
                config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                scanner.watch(config);
            }
        }
    }

    
    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#restartWebApp(boolean)
     */
    public void restartWebApp(boolean reconfigureScanner) throws Exception 
    {
        getLog().info("restarting "+webApp);
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
        getLog().info("Restart completed at "+new Date().toString());
    }
    
    
    
    
    /**
     * @return
     */
    private List<File> getDependencyFiles ()
    {
        List<File> dependencyFiles = new ArrayList<File>();
        for ( Iterator<Artifact> iter = projectArtifacts.iterator(); iter.hasNext(); )
        {
            Artifact artifact = (Artifact) iter.next();
            
            // Include runtime and compile time libraries, and possibly test libs too
            if(artifact.getType().equals("war"))
            {
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
    
    
    
    
    /**
     * @return
     */
    private List<Artifact> getWarArtifacts ()
    {
        if (warArtifacts != null)
            return warArtifacts;       
        
        warArtifacts = new ArrayList<Artifact>();
        for ( Iterator<Artifact> iter = projectArtifacts.iterator(); iter.hasNext(); )
        {
            Artifact artifact = (Artifact) iter.next(); 
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
}

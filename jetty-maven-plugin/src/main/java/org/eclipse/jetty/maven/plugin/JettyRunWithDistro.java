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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * JettyRunWithDistro
 *
 * @goal run-distro
 * @requiresDependencyResolution test
 * @execute phase="test-compile"
 * @description Runs unassembled webapp in a locally installed jetty distro
 */
public class JettyRunWithDistro extends JettyRunMojo
{
    
    public static final String JETTY_HOME_GROUPID = "org.eclipse.jetty";
    public static final String JETTY_HOME_ARTIFACTID = "jetty-home";
    /**
     * The target directory
     * 
     * @parameter default-value="${project.build.directory}"
     * @required
     * @readonly
     */
    protected File target;
    
    
    /**
     * 
     * @parameter
     */
    private File jettyHome;
    
    
    /**
     * 
     * @parameter
     */
    private File jettyBase;
    
    /**
     * Optional list of other modules to
     * activate.
     * @parameter
     */
    private String[] modules;
    
    
    /**
     * Optional list of jetty properties to put on the command line
     * @parameter
     */
    private String[] properties;

    /**
     * @parameter default-value="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    /**
     * The project's remote repositories to use for the resolution.
     *
     * @parameter default-value="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    private List<ArtifactRepository> remoteRepositories;

    /**
     * @component
     */
    private ArtifactResolver artifactResolver;

    
    /**
     * @parameter default-value="${plugin.version}"
     * @readonly
     */
    private String pluginVersion;
    
    
 
    private File targetBase;
    
    
    // IDEAS:
    // 2. put out a warning if pluginDependencies are configured (use a jetty-home and configure)
    // 3. don't copy existing jetty-base/webapps because a context.xml will confuse the deployer - or
    //    do copy webapps but if the contextXml file matches a file in webapps, don't copy that over
    // 4. try to make the maven.xml configure a JettyWebAppContext which uses helper classes to configure
    //    itself and apply the context.xml file: that way we can configure the normal jetty deployer
    // 5. try to use the scanner as normal and remake the properties and context.xml file to get the
    //    deployer to automatically redeploy it on changes.

    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#startJetty()
     */
    @Override
    public void startJetty() throws MojoExecutionException
    {
        //don't start jetty locally, set up enough configuration to fork a jetty distro
        try
        {
            printSystemProperties();
            
            //download and install jetty-home if necessary
            configureJettyHome();

            //ensure config of the webapp based on settings in plugin
            configureWebApplication();
            
            //configure jetty.base
            configureJettyBase();
            
            ProcessBuilder command = configureCommand();
            Process process = command.start();
            process.waitFor();
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
     * @throws Exception
     */
    public void configureJettyHome() throws Exception
    {
        if (jettyHome == null)
        {
            //no jetty home, download from repo and unpack it. Get the same version as the plugin
            DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
            coordinate.setGroupId( JETTY_HOME_GROUPID );
            coordinate.setArtifactId( JETTY_HOME_ARTIFACTID );
            coordinate.setVersion( pluginVersion );
            coordinate.setExtension( "zip" );

            ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest( session.getProjectBuildingRequest() );

            buildingRequest.setRemoteRepositories( remoteRepositories );

            Artifact jettyHomeArtifact = artifactResolver.resolveArtifact( buildingRequest, coordinate ).getArtifact();

            JarResource res = (JarResource) JarResource.newJarResource(Resource.newResource(jettyHomeArtifact.getFile()));
            res.copyTo(target);
            //zip will unpack to target/jetty-home-<VERSION>
            jettyHome = new File (target, JETTY_HOME_ARTIFACTID+"-"+pluginVersion);
        }
        else
        {
            if  (!jettyHome.exists())
                throw new IllegalStateException(jettyHome.getAbsolutePath()+" does not exist");
        }
        
        getLog().info("jetty.home = "+jettyHome.getAbsolutePath());
    }



    /**
     * Create or configure a jetty base.
     * 
     * @throws Exception
     */
    public void configureJettyBase() throws Exception
    {
        if (jettyBase != null && !jettyBase.exists())
            throw new IllegalStateException(jettyBase.getAbsolutePath() +" does not exist");
        
        targetBase = new File(target, "jetty-base");
        Path basePath = targetBase.toPath();
        
        if (targetBase.exists())
            IO.delete(targetBase);
        
        targetBase.mkdirs();
        
        if (jettyBase != null)
        {
            //copy the existing jetty base, but remove the deployer as we will be doing the
            //deployment instead
            IO.copyDir(jettyBase, targetBase);
            Path deployIni = basePath.resolve("start.d/deploy.ini");
            if (Files.exists(deployIni))
                Files.delete(deployIni);
        }
        
        //make the jetty base structure
        Path modulesPath = Files.createDirectories(basePath.resolve("modules"));
        Path etcPath = Files.createDirectories(basePath.resolve("etc"));
        Path libPath = Files.createDirectories(basePath.resolve("lib/maven"));

        //copy in the jetty-maven-plugin jar
        URI thisJar = TypeUtil.getLocationOfClass(this.getClass());
        if (thisJar == null)
            throw new IllegalStateException("Can't find jar for jetty-maven-plugin");
        
        try(InputStream jarStream = thisJar.toURL().openStream();
            FileOutputStream fileStream =  new FileOutputStream(libPath.resolve("plugin.jar").toFile()))
        {
            IO.copy(jarStream,fileStream);
        }
        
        //copy in the maven.xml and maven.mod file
        try (InputStream mavenXmlStream = getClass().getClassLoader().getResourceAsStream("maven.xml"); 
                FileOutputStream fileStream = new FileOutputStream(etcPath.resolve("maven.xml").toFile()))
        {
            IO.copy(mavenXmlStream, fileStream);
        }

        try (InputStream mavenModStream = getClass().getClassLoader().getResourceAsStream("maven.mod");
                FileOutputStream fileStream = new FileOutputStream(modulesPath.resolve("maven.mod").toFile()))
        {
            IO.copy(mavenModStream, fileStream);
        }
        
        createPropertiesFile(basePath, etcPath);
    }
    
    
    /**
     * Convert webapp config to properties
     * 
     * @param basePath
     * @param etcPath
     * @throws Exception
     */
    public void createPropertiesFile (Path basePath, Path etcPath)
    throws Exception
    {
        File propsFile = Files.createFile(etcPath.resolve("maven.props")).toFile();
        convertWebAppToProperties(propsFile);
    }
    
    
    /**
     * Make the command to spawn a process to
     * run jetty from a distro.
     * 
     * @return
     */
    public ProcessBuilder configureCommand()
    {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(new File(jettyHome, "start.jar").getAbsolutePath());
        StringBuilder tmp = new StringBuilder();
        tmp.append("--module=");
        tmp.append("server,http,webapp");
        if (modules != null)
        {
            for (String m:modules)
                tmp.append(","+m);
        }
        tmp.append(",maven");
   
        
        cmd.add(tmp.toString());
        
        
        if (properties != null)
        {
            tmp.delete(0, tmp.length());
            for (String p:properties)
                tmp.append(" "+p);
            cmd.add(tmp.toString());
            
        }
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(targetBase);
        builder.inheritIO();
        
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

}

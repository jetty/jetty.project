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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;

/**
 * JettyRunWithDistro
 *
 * @goal run-distro
 * @requiresDependencyResolution test
 * @execute phase="test-compile"
 * @description Runs unassembled webapp in a local jetty distro
 */
public class JettyRunWithDistro extends JettyRunMojo
{
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
     * @required
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
    
 
    private File targetBase;
    

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

            //ensure config of the webapp based on settings in plugin
            configureWebApplication();
            
            //configure jetty.base
            configureJettyBase();
            
            ProcessBuilder command = configureCommand();
            
            //fork the command
            Process process = command.start();
            process.waitFor();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Failed to start Jetty", e);
        }

    }



    public void configureJettyBase() throws Exception
    {
        if (jettyBase != null && !jettyBase.exists())
            throw new IllegalStateException(jettyBase.getAbsolutePath() +" does not exist");
        
        File etc;
        File modules;

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
        Path modulesPath = Files.createDirectory(basePath.resolve("modules"));
        System.err.println(modulesPath+" exists:"+Files.exists(modulesPath));
        Path etcPath = Files.createDirectory(basePath.resolve("etc"));
        System.err.println(etcPath+" exists:"+Files.exists(etcPath));
        Path libPath = Files.createDirectories(basePath.resolve("lib/maven"));
        System.err.println(libPath+" exists:"+Files.exists(libPath));
        //copy in the jetty-maven-plugin jar
        URI thisJar = TypeUtil.getLocationOfClass(this.getClass());
        if (thisJar == null)
            throw new IllegalStateException("Can't find jar for jetty-maven-plugin");
        
        try(InputStream jarStream = thisJar.toURL().openStream();
            FileOutputStream fileStream =  new FileOutputStream(libPath.resolve("plugin.jar").toFile()))
        {
            IO.copy(jarStream,fileStream);
            System.err.println("copied "+thisJar+" to plugin.jar");
        }
        
        //copy in the maven.xml and maven.mod file
        try (InputStream mavenXmlStream = getClass().getClassLoader().getResourceAsStream("maven.xml"); 
                FileOutputStream fileStream = new FileOutputStream(etcPath.resolve("maven.xml").toFile()))
        {
            IO.copy(mavenXmlStream, fileStream);
            System.err.println("Copied maven.xml to etc/maven.xml");
        }

        try (InputStream mavenModStream = getClass().getClassLoader().getResourceAsStream("maven.mod");
                FileOutputStream fileStream = new FileOutputStream(modulesPath.resolve("maven.mod").toFile()))
        {
            IO.copy(mavenModStream, fileStream);
            System.err.println("Copied maven.mod to modules/maven.mod");
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
    
    
    public ProcessBuilder configureCommand()
    {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(new File(jettyHome, "start.jar").getAbsolutePath());
        StringBuilder tmp = new StringBuilder();
        tmp.append("--module=server,http,webapp,plus,annotations,quickstart,maven");
        if (modules != null)
        {
            for (String m:modules)
                tmp.append(","+m);
        }
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
        
        System.err.println(builder.command());
        
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

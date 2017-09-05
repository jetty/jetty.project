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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * JettyDistroForker
 *
 */
public class JettyDistroForker extends AbstractForker
{
    protected JettyWebAppContext webApp;


    protected File contextXmlFile;

    /**
     * Location of existing jetty home directory
     */
    protected File jettyHome;

    
    /**
     * Zip of jetty distro
     */
    protected File jettyDistro;
    
    /**
     * Location of existing jetty base directory
     */

    protected File jettyBase;
    
    
 
    
    protected File baseDir;
    

    /**
     * Optional list of other modules to
     * activate.
     */

    protected String[] modules;
    
    
    protected List<File> libExtJarFiles;
    
    protected Path modulesPath;
    protected Path etcPath;
    protected Path libPath;
    protected Path webappPath;
    protected Path mavenLibPath;
    

    public List<File> getLibExtJarFiles()
    {
        return libExtJarFiles;
    }

    public void setLibExtJarFiles(List<File> libExtJarFiles)
    {
        this.libExtJarFiles = libExtJarFiles;
    }

    public File getJettyHome()
    {
        return jettyHome;
    }

    public void setJettyHome(File jettyHome)
    {
        this.jettyHome = jettyHome;
    }

    public File getJettyBase()
    {
        return jettyBase;
    }

    public void setJettyBase(File jettyBase)
    {
        this.jettyBase = jettyBase;
    }

    public String[] getModules()
    {
        return modules;
    }

    public void setModules(String[] modules)
    {
        this.modules = modules;
    }

    public File getContextXmlFile()
    {
        return contextXmlFile;
    }

    public void setContextXmlFile(File contextXmlFile)
    {
        this.contextXmlFile = contextXmlFile;
    }
    
    
    public File getJettyDistro()
    {
        return jettyDistro;
    }

    public void setJettyDistro(File jettyDistro)
    {
        this.jettyDistro = jettyDistro;
    }

    public void configureJettyHome ()
    throws Exception
    {
        if (jettyHome == null && jettyDistro == null)
            throw new IllegalStateException ("No jettyDistro");
        
        if (baseDir == null)
            throw new IllegalStateException ("No baseDir");
        
        if (jettyHome == null)
        {
            JarResource res = (JarResource) JarResource.newJarResource(Resource.newResource(jettyDistro));
            res.copyTo(baseDir);
            //zip will unpack to target/jetty-home-<VERSION>
            String name = jettyDistro.getName();
            int i = name.lastIndexOf('.');
            name = (i>0?name.substring(0, i):"distro");             
            jettyHome = new File (baseDir, name);
            
            System.err.println("JETTY HOME = "+jettyHome);
        }
    }

   

    public JettyWebAppContext getWebApp()
    {
        return webApp;
    }

    public void setWebApp(JettyWebAppContext webApp)
    {
        this.webApp = webApp;
    }

    public File getBaseDir()
    {
        return baseDir;
    }

    public void setBaseDir(File baseDir)
    {
        this.baseDir = baseDir;
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractForker#createCommand()
     */
    @Override
    public ProcessBuilder createCommand()
    {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(new File(jettyHome, "start.jar").getAbsolutePath());
        
        cmd.add("-DSTOP.PORT="+stopPort);
        if (stopKey != null)
            cmd.add("-DSTOP.KEY="+stopKey);
        
        //add any args to the jvm
        if (jvmArgs != null)
        {
            String[] args = jvmArgs.split(" ");
            for (String a:args)
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
            for (String m:modules)
            {
                if (tmp.indexOf(m) < 0)
                    tmp.append(","+m);
            }
        }

        if (libExtJarFiles != null && !libExtJarFiles.isEmpty() && tmp.indexOf("ext") < 0)
            tmp.append(",ext");
        tmp.append(",maven");
        cmd.add(tmp.toString());

        //put any jetty properties onto the command line
        if (jettyProperties != null)
        {
            for (Map.Entry<String, String> e:jettyProperties.entrySet())
            {
                cmd.add(e.getKey()+"="+e.getValue());
            }
        }

        //existence of this file signals process started
        cmd.add("jetty.token.file="+tokenFile.getAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(workDir);

        //set up extra environment vars if there are any
        if (!env.isEmpty())
            builder.environment().putAll(env);

        System.err.println("COMMAND: "+builder.command());

        if (waitForChild)
            builder.inheritIO();
        else
        {
            builder.redirectOutput(jettyOutputFile);
            builder.redirectErrorStream(true);
        }
        return builder;
    }

    @Override
    public void start() throws Exception
    {
        //set up a jetty-home
        configureJettyHome();
        
        if (jettyHome == null || !jettyHome.exists())
            throw new IllegalStateException ("No jetty home");
        
        //set up a jetty-base
        configureJettyBase();
        
        //convert the webapp to properties
        WebAppPropertyConverter.toProperties(webApp, etcPath.resolve("maven.props").toFile(), contextXmlFile.getAbsolutePath());
        
        super.start();
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
        
        File targetJettyBase = new File(baseDir, "jetty-base");
        Path targetBasePath = targetJettyBase.toPath();
        if (Files.exists(targetBasePath))
            IO.delete(targetJettyBase);
        
        targetJettyBase.mkdirs();
        
        //jetty-base will be the working directory for the forked command
        workDir = targetJettyBase;
        
        //if there is an existing jetty base, copy parts of it
        if (jettyBase != null)
        {
            Path jettyBasePath = jettyBase.toPath();
            
            //copy the existing jetty base
            Files.walkFileTree(jettyBasePath,EnumSet.of(FileVisitOption.FOLLOW_LINKS), 
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
                    if (contextXmlFile != null && Files.isSameFile(contextXmlFile.toPath(), file))
                        return FileVisitResult.CONTINUE; //skip copying the context xml file
                    Files.copy(file, targetBasePath.resolve(jettyBasePath.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }

            });
        }

        //make the jetty base structure
        modulesPath = Files.createDirectories(targetBasePath.resolve("modules"));
        etcPath = Files.createDirectories(targetBasePath.resolve("etc"));
        libPath = Files.createDirectories(targetBasePath.resolve("lib"));
        webappPath = Files.createDirectories(targetBasePath.resolve("webapps"));
        mavenLibPath = Files.createDirectories(libPath.resolve("maven"));

        //copy in the jetty-maven-plugin jar
        URI thisJar = TypeUtil.getLocationOfClass(this.getClass());
        if (thisJar == null)
            throw new IllegalStateException("Can't find jar for jetty-maven-plugin");

        try(InputStream jarStream = thisJar.toURL().openStream();
            FileOutputStream fileStream =  new FileOutputStream(mavenLibPath.resolve("plugin.jar").toFile()))
        {
            IO.copy(jarStream,fileStream);
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
        if (libExtJarFiles != null && !libExtJarFiles.isEmpty())
        {
            Path libExtPath = Files.createDirectories(libPath.resolve("ext"));
            for (File f:libExtJarFiles)
            {
                try (InputStream jarStream = new FileInputStream(f);
                    FileOutputStream fileStream = new FileOutputStream(libExtPath.resolve(f.getName()).toFile()))
                {
                    IO.copy(jarStream, fileStream);
                }
            }
        } 
    }
}

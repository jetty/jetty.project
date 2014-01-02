//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;


/**
 * <p>
 *  This goal is used to assemble your webapp into a war and automatically deploy it to Jetty in a forked JVM.
 *  </p>
 *  <p>
 *  You need to define a jetty.xml file to configure connectors etc and a context xml file that sets up anything special
 *  about your webapp. This plugin will fill in the:
 *  <ul>
 *  <li>context path
 *  <li>classes
 *  <li>web.xml
 *  <li>root of the webapp
 *  </ul>
 *  Based on a combination of information that you supply and the location of files in your unassembled webapp.
 *  </p>
 *  <p>
 *  There is a <a href="run-war-mojo.html">reference guide</a> to the configuration parameters for this plugin, and more detailed information
 *  with examples in the <a href="http://docs.codehaus.org/display/JETTY/Maven+Jetty+Plugin/">Configuration Guide</a>.
 *  </p>
 * 
 * @goal run-forked
 * @requiresDependencyResolution test
 * @execute phase="test-compile"
 * @description Runs Jetty in forked JVM on an unassembled webapp
 *
 */
public class JettyRunForkedMojo extends AbstractMojo
{    
    public static final String DEFAULT_WEBAPP_SRC = "src"+File.separator+"main"+File.separator+"webapp";
    public static final String FAKE_WEBAPP = "webapp-tmp";
    
    
    public String PORT_SYSPROPERTY = "jetty.port";
    
    /**
     * Whether or not to include dependencies on the plugin's classpath with &lt;scope&gt;provided&lt;/scope&gt;
     * Use WITH CAUTION as you may wind up with duplicate jars/classes.
     * @parameter  default-value="false"
     */
    protected boolean useProvidedScope;
    
    
    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    
    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     * @parameter alias="useTestClasspath" default-value="false"
     */
    private boolean useTestScope;
    
    
    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webAppConfig&gt;&lt;descriptor&gt; is not set.
     * 
     * @parameter expression="${basedir}/src/main/webapp/WEB-INF/web.xml"
     * @readonly
     */
    private String webXml;
    
    
    /**
     * The target directory
     * 
     * @parameter expression="${project.build.directory}"
     * @required
     * @readonly
     */
    protected File target;
    
    
    /**
     * The temporary directory to use for the webapp.
     * Defaults to target/tmp
     *
     * @parameter alias="tmpDirectory" expression="${project.build.directory}/tmp"
     * @required
     * @readonly
     */
    protected File tempDirectory;
    
    
    
    /**
     * Whether temporary directory contents should survive webapp restarts.
     * 
     * @parameter default-value="false"
     */
    private boolean persistTempDirectory;

    
    /**
     * The directory containing generated classes.
     *
     * @parameter expression="${project.build.outputDirectory}"
     * @required
     * 
     */
    private File classesDirectory;    
    
    
    /**
     * The directory containing generated test classes.
     * 
     * @parameter expression="${project.build.testOutputDirectory}"
     * @required
     */
    private File testClassesDirectory;
   
    
    /**
     * Root directory for all html/jsp etc files
     *
     * @parameter expression="${basedir}/src/main/webapp"
     *
     */
    private File webAppSourceDirectory;

    /**
     * Resource Bases
     *
     * @parameter
     *
     */
     private String[] resourceBases;

    /**
     * If true, the webAppSourceDirectory will be first on the list of 
     * resources that form the resource base for the webapp. If false, 
     * it will be last.
     * 
     * @parameter  default-value="true"
     */
    private boolean baseAppFirst;
    

    /**
     * Location of jetty xml configuration files whose contents 
     * will be applied before any plugin configuration. Optional.
     * @parameter
     */
    private String jettyXml;
    
    /**
     * The context path for the webapp. Defaults to / for jetty-9
     *
     * @parameter expression="/"
     */
    private String contextPath;


    /**
     * Location of a context xml configuration file whose contents
     * will be applied to the webapp AFTER anything in &lt;webAppConfig&gt;.Optional.
     * @parameter
     */
    private String contextXml;

    
    /**  
     * @parameter expression="${jetty.skip}" default-value="false"
     */
    private boolean skip;

    
    /**
     * Port to listen to stop jetty on executing -DSTOP.PORT=&lt;stopPort&gt; 
     * -DSTOP.KEY=&lt;stopKey&gt; -jar start.jar --stop
     * @parameter
     * @required
     */
    protected int stopPort;
 
    
    /**
     * Key to provide when stopping jetty on executing java -DSTOP.KEY=&lt;stopKey&gt; 
     * -DSTOP.PORT=&lt;stopPort&gt; -jar start.jar --stop
     * @parameter
     * @required
     */
    protected String stopKey;

    
    /**
     * Arbitrary jvm args to pass to the forked process
     * @parameter
     */
    private String jvmArgs;
    
    
    /**
     * @parameter expression="${plugin.artifacts}"
     * @readonly
     */
    private List pluginArtifacts;
    
    
    /**
     * @parameter expression="${plugin}"
     * @readonly
     */
    private PluginDescriptor plugin;
    
    
    /**
     * @parameter expression="true" default-value="true"
     */
    private boolean waitForChild;

    /**
     * @parameter default-value="50"
     */
    private int maxStartupLines;

    /**
     * The forked jetty instance
     */
    private Process forkedProcess;
    
    
    /**
     * Random number generator
     */
    private Random random;    
    
    
    
    
    
    
    /**
     * ShutdownThread
     *
     *
     */
    public class ShutdownThread extends Thread
    {
        public ShutdownThread()
        {
            super("RunForkedShutdown");
        }
        
        public void run ()
        {
            if (forkedProcess != null && waitForChild)
            {
                forkedProcess.destroy();
            }
        }
    }
    

    
    
    /**
     * ConsoleStreamer
     * 
     * Simple streamer for the console output from a Process
     */
    private static class ConsoleStreamer implements Runnable
    {
        private String mode;
        private BufferedReader reader;

        public ConsoleStreamer(String mode, InputStream is)
        {
            this.mode = mode;
            this.reader = new BufferedReader(new InputStreamReader(is));
        }


        public void run()
        {
            String line;
            try
            {
                while ((line = reader.readLine()) != (null))
                {
                    System.out.println("[" + mode + "] " + line);
                }
            }
            catch (IOException ignore)
            {
                /* ignore */
            }
            finally
            {
                IO.close(reader);
            }
        }
    }
    
    
    
    
    
    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        getLog().info("Configuring Jetty for project: " + project.getName());
        if (skip)
        {
            getLog().info("Skipping Jetty start: jetty.skip==true");
            return;
        }
        PluginLog.setLog(getLog());
        Runtime.getRuntime().addShutdownHook(new ShutdownThread());
        random = new Random();
        startJettyRunner();
    }
    
    
    
    
    /**
     * @return
     * @throws MojoExecutionException
     */
    public List<String> getProvidedJars() throws MojoExecutionException
    {  
        //if we are configured to include the provided dependencies on the plugin's classpath
        //(which mimics being on jetty's classpath vs being on the webapp's classpath), we first
        //try and filter out ones that will clash with jars that are plugin dependencies, then
        //create a new classloader that we setup in the parent chain.
        if (useProvidedScope)
        {
            
                List<String> provided = new ArrayList<String>();        
                for ( Iterator<Artifact> iter = project.getArtifacts().iterator(); iter.hasNext(); )
                {                   
                    Artifact artifact = iter.next();
                    if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()) && !isPluginArtifact(artifact))
                    {
                        provided.add(artifact.getFile().getAbsolutePath());
                        if (getLog().isDebugEnabled()) { getLog().debug("Adding provided artifact: "+artifact);}
                    }
                }
                return provided;

        }
        else
            return null;
    }
    
   
    
    
    /**
     * @return
     * @throws MojoExecutionException
     */
    public File prepareConfiguration() throws MojoExecutionException
    {
        try
        {   
            //work out the configuration based on what is configured in the pom
            File propsFile = new File (target, "fork.props");
            if (propsFile.exists())
                propsFile.delete();   

            propsFile.createNewFile();
            //propsFile.deleteOnExit();

            Properties props = new Properties();


            //web.xml
            if (webXml != null)
                props.put("web.xml", webXml);

            //sort out the context path
            if (contextPath != null)
                props.put("context.path", contextPath);

            //sort out the tmp directory (make it if it doesn't exist)
            if (tempDirectory != null)
            {
                if (!tempDirectory.exists())
                    tempDirectory.mkdirs();
                props.put("tmp.dir", tempDirectory.getAbsolutePath());
            }
            
            props.put("tmp.dir.persist", Boolean.toString(persistTempDirectory));

            if (resourceBases == null)
            {
                //sort out base dir of webapp
                if (webAppSourceDirectory == null || !webAppSourceDirectory.exists())
                {
                    webAppSourceDirectory = new File (project.getBasedir(), DEFAULT_WEBAPP_SRC);
                    if (!webAppSourceDirectory.exists())
                    {
                        //try last resort of making a fake empty dir
                        File target = new File(project.getBuild().getDirectory());
                        webAppSourceDirectory = new File(target, FAKE_WEBAPP);
                        if (!webAppSourceDirectory.exists())
                            webAppSourceDirectory.mkdirs();
                    }
                }
                resourceBases = new String[] { webAppSourceDirectory.getAbsolutePath() };
            }
            StringBuffer rb = new StringBuffer(resourceBases[0]);
            for (int i=1; i<resourceBases.length; i++) 
            {
                rb.append(File.pathSeparator);
                rb.append(resourceBases[i]);
            }
            props.put("base.dirs", rb.toString());

            //sort out the resource base directories of the webapp
            StringBuilder builder = new StringBuilder();
            props.put("base.first", Boolean.toString(baseAppFirst));

            //web-inf classes
            List<File> classDirs = getClassesDirs();
            StringBuffer strbuff = new StringBuffer();
            for (int i=0; i<classDirs.size(); i++)
            {
                File f = classDirs.get(i);
                strbuff.append(f.getAbsolutePath());
                if (i < classDirs.size()-1)
                    strbuff.append(",");
            }

            if (classesDirectory != null)
            {
                props.put("classes.dir", classesDirectory.getAbsolutePath());
            }
            
            if (useTestScope && testClassesDirectory != null)
            {
                props.put("testClasses.dir", testClassesDirectory.getAbsolutePath());
            }

            //web-inf lib
            List<File> deps = getDependencyFiles();
            strbuff.setLength(0);
            for (int i=0; i<deps.size(); i++)
            {
                File d = deps.get(i);
                strbuff.append(d.getAbsolutePath());
                if (i < deps.size()-1)
                    strbuff.append(",");
            }
            props.put("lib.jars", strbuff.toString());

            //any war files
            List<Artifact> warArtifacts = getWarArtifacts(); 
            for (int i=0; i<warArtifacts.size(); i++)
            {
                strbuff.setLength(0);           
                Artifact a  = warArtifacts.get(i);
                strbuff.append(a.getGroupId()+",");
                strbuff.append(a.getArtifactId()+",");
                strbuff.append(a.getFile().getAbsolutePath());
                props.put("maven.war.artifact."+i, strbuff.toString());
            }
          
            
            //any overlay configuration
            WarPluginInfo warPlugin = new WarPluginInfo(project);
            
            //add in the war plugins default includes and excludes
            props.put("maven.war.includes", toCSV(warPlugin.getDependentMavenWarIncludes()));
            props.put("maven.war.excludes", toCSV(warPlugin.getDependentMavenWarExcludes()));
            
            
            List<OverlayConfig> configs = warPlugin.getMavenWarOverlayConfigs();
            int i=0;
            for (OverlayConfig c:configs)
            {
                props.put("maven.war.overlay."+(i++), c.toString());
            }
            
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(propsFile)))
            {
                props.store(out, "properties for forked webapp");
            }
            return propsFile;
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Prepare webapp configuration", e);
        }
    }
    

    
    
    /**
     * @return
     */
    private List<File> getClassesDirs ()
    {
        List<File> classesDirs = new ArrayList<File>();
        
        //if using the test classes, make sure they are first
        //on the list
        if (useTestScope && (testClassesDirectory != null))
            classesDirs.add(testClassesDirectory);
        
        if (classesDirectory != null)
            classesDirs.add(classesDirectory);
        
        return classesDirs;
    }
  
    
  
    
    /**
     * @return
     * @throws MalformedURLException
     * @throws IOException
     */
    private List<Artifact> getWarArtifacts()
    throws MalformedURLException, IOException
    {
        List<Artifact> warArtifacts = new ArrayList<Artifact>();
        for ( Iterator<Artifact> iter = project.getArtifacts().iterator(); iter.hasNext(); )
        {
            Artifact artifact = (Artifact) iter.next();  
            
            if (artifact.getType().equals("war"))
                warArtifacts.add(artifact);
        }

        return warArtifacts;
    }
    
    
    
    
    /**
     * @return
     */
    private List<File> getDependencyFiles ()
    {
        List<File> dependencyFiles = new ArrayList<File>();

        for ( Iterator<Artifact> iter = project.getArtifacts().iterator(); iter.hasNext(); )
        {
            Artifact artifact = (Artifact) iter.next();
            // Test never appears here !
            if (((!Artifact.SCOPE_PROVIDED.equals(artifact.getScope())) && (!Artifact.SCOPE_TEST.equals( artifact.getScope())))
                    ||
                (useTestScope && Artifact.SCOPE_TEST.equals( artifact.getScope())))
            {
                dependencyFiles.add(artifact.getFile());
                getLog().debug( "Adding artifact " + artifact.getFile().getName() + " for WEB-INF/lib " );
            }
        }
        
        return dependencyFiles; 
    }
    
    
    
    
    /**
     * @param artifact
     * @return
     */
    public boolean isPluginArtifact(Artifact artifact)
    {
        if (pluginArtifacts == null || pluginArtifacts.isEmpty())
            return false;
        
        boolean isPluginArtifact = false;
        for (Iterator<Artifact> iter = pluginArtifacts.iterator(); iter.hasNext() && !isPluginArtifact; )
        {
            Artifact pluginArtifact = iter.next();
            if (getLog().isDebugEnabled()) { getLog().debug("Checking "+pluginArtifact);}
            if (pluginArtifact.getGroupId().equals(artifact.getGroupId()) && pluginArtifact.getArtifactId().equals(artifact.getArtifactId()))
                isPluginArtifact = true;
        }
        
        return isPluginArtifact;
    }
    
    
    
    
    /**
     * @return
     * @throws Exception
     */
    private Set<Artifact> getExtraJars()
    throws Exception
    {
        Set<Artifact> extraJars = new HashSet<Artifact>();
  
        
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

    

    
    /**
     * @throws MojoExecutionException
     */
    public void startJettyRunner() throws MojoExecutionException
    {      
        try
        {
        
            File props = prepareConfiguration();
            
            List<String> cmd = new ArrayList<String>();
            cmd.add(getJavaBin());
            
            if (jvmArgs != null)
            {
                String[] args = jvmArgs.split(" ");
                for (int i=0;args != null && i<args.length;i++)
                {
                    if (args[i] !=null && !"".equals(args[i]))
                        cmd.add(args[i].trim());
                }
            }
            
            String classPath = getClassPath();
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
        
            if (contextXml != null)
            {
                cmd.add("--context-xml");
                cmd.add(contextXml);
            }
            
            cmd.add("--props");
            cmd.add(props.getAbsolutePath());
            
            String token = createToken();
            cmd.add("--token");
            cmd.add(token);
            
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.directory(project.getBasedir());
            
            if (PluginLog.getLog().isDebugEnabled())
                PluginLog.getLog().debug(Arrays.toString(cmd.toArray()));
            
            PluginLog.getLog().info("Forked process starting");

            if (waitForChild)
            {
                forkedProcess = builder.start();
                startPump("STDOUT",forkedProcess.getInputStream());
                startPump("STDERR",forkedProcess.getErrorStream());
                int exitcode = forkedProcess.waitFor();            
                PluginLog.getLog().info("Forked execution exit: "+exitcode);
            }
            else
            {   //merge stderr and stdout from child
                builder.redirectErrorStream(true);
                forkedProcess = builder.start();

                //wait for the child to be ready before terminating.
                //child indicates it has finished starting by printing on stdout the token passed to it
                try
                {
                    String line = "";
                    try (InputStream is = forkedProcess.getInputStream();
                            LineNumberReader reader = new LineNumberReader(new InputStreamReader(is)))
                    {
                        int attempts = maxStartupLines; //max lines we'll read trying to get token
                        while (attempts>0 && line != null)
                        {
                            --attempts;
                            line = reader.readLine();
                            if (line != null && line.startsWith(token))
                                break;
                        }

                    }

                    if (line != null && line.trim().equals(token))
                        PluginLog.getLog().info("Forked process started.");
                    else
                    {
                        String err = (line == null?"":(line.startsWith(token)?line.substring(token.length()):line));
                        PluginLog.getLog().info("Forked process startup errors"+(!"".equals(err)?", received: "+err:""));
                    }
                }
                catch (Exception e)
                {
                    throw new MojoExecutionException ("Problem determining if forked process is ready: "+e.getMessage());
                }

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
    
 

    
    /**
     * @return
     * @throws Exception
     */
    public String getClassPath() throws Exception
    {
        StringBuilder classPath = new StringBuilder();
        for (Object obj : pluginArtifacts)
        {
            Artifact artifact = (Artifact) obj;
            if ("jar".equals(artifact.getType()))
            {
                if (classPath.length() > 0)
                {
                    classPath.append(File.pathSeparator);
                }
                classPath.append(artifact.getFile().getAbsolutePath());

            }
        }
        
        //Any jars that we need from the plugin environment (like the ones containing Starter class)
        Set<Artifact> extraJars = getExtraJars();
        for (Artifact a:extraJars)
        { 
            classPath.append(File.pathSeparator);
            classPath.append(a.getFile().getAbsolutePath());
        }
        
        
        //Any jars that we need from the project's dependencies because we're useProvided
        List<String> providedJars = getProvidedJars();
        if (providedJars != null && !providedJars.isEmpty())
        {
            for (String jar:providedJars)
            {
                classPath.append(File.pathSeparator);
                classPath.append(jar);
                if (getLog().isDebugEnabled()) getLog().debug("Adding provided jar: "+jar);
            }
        }

        return classPath.toString();
    }

    

    
    /**
     * @return
     */
    private String getJavaBin()
    {
        String javaexes[] = new String[]
        { "java", "java.exe" };

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir,fileSeparators("bin/" + javaexe));
            if (javabin.exists() && javabin.isFile())
            {
                return javabin.getAbsolutePath();
            }
        }

        return "java";
    }
    

    
    
    /**
     * @param path
     * @return
     */
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


    
    
    /**
     * @param path
     * @return
     */
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


    
    
    /**
     * @return
     */
    private String createToken ()
    {
        return Long.toString(random.nextLong()^System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
    }
    

    
    
    /**
     * @param mode
     * @param inputStream
     */
    private void startPump(String mode, InputStream inputStream)
    {
        ConsoleStreamer pump = new ConsoleStreamer(mode,inputStream);
        Thread thread = new Thread(pump,"ConsoleStreamer/" + mode);
        thread.setDaemon(true);
        thread.start();
    }


    
    
    /**
     * @param strings
     * @return
     */
    private String toCSV (List<String> strings)
    {
        if (strings == null)
            return "";
        StringBuffer strbuff = new StringBuffer();
        Iterator<String> itor = strings.iterator();
        while (itor.hasNext())
        {
            strbuff.append(itor.next());
            if (itor.hasNext())
                strbuff.append(",");
        }
        return strbuff.toString();
    }
}

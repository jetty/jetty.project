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

package org.eclipse.jetty.ee9.jspc.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.jasper.JspC;
import org.apache.jasper.servlet.JspCServletContext;
import org.apache.jasper.servlet.TldScanner;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.util.scan.StandardJarScanner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;

/**
 * This goal will compile jsps for a webapp so that they can be included in a
 * war.
 * <p>
 * At runtime, the plugin will use the jspc compiler to precompile jsps and tags.
 * </p>
 * <p>
 * Note that the same java compiler will be used as for on-the-fly compiled
 * jsps, which will be the Eclipse java compiler.
 * <p>
 * See <a
 * href="https://www.eclipse.org/jetty/documentation/current/jetty-jspc-maven-plugin.html">Usage
 * Guide</a> for instructions on using this plugin.
 * </p>
 * Runs jspc compiler to produce .java and .class files
 */
@Mojo(name = "jspc", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = true)
public class JspcMojo extends AbstractMojo
{
    public static final String END_OF_WEBAPP = "</web-app>";
    public static final String PRECOMPILED_FLAG = "org.eclipse.jetty.ee9.jsp.precompiled";

    /**
     * JettyJspC
     *
     * Add some extra setters to standard JspC class to help configure it
     * for running in maven.
     *
     * TODO move all setters on the plugin onto this jspc class instead.
     */
    public static class JettyJspC extends JspC
    {

        private boolean scanAll;
        private boolean scanManifest;

        public void setClassLoader(ClassLoader loader)
        {
            this.loader = loader;
        }

        public void setScanAllDirectories(boolean scanAll)
        {
            this.scanAll = scanAll;
        }

        public boolean getScanAllDirectories()
        {
            return this.scanAll;
        }

        public void setScanManifest(boolean scanManifest)
        {
            this.scanManifest = scanManifest;
        }

        public boolean getScanManifest()
        {
            return this.scanManifest;
        }

        @Override
        protected TldScanner newTldScanner(JspCServletContext context, boolean namespaceAware, boolean validate, boolean blockExternal)
        {
            if (context != null && context.getAttribute(JarScanner.class.getName()) == null)
            {
                StandardJarScanner jarScanner = new StandardJarScanner();
                jarScanner.setScanAllDirectories(getScanAllDirectories());
                jarScanner.setScanManifest(getScanManifest());
                context.setAttribute(JarScanner.class.getName(), jarScanner);
            }

            return super.newTldScanner(context, namespaceAware, validate, blockExternal);
        }
    }

    /**
     * Whether or not to include dependencies on the plugin's classpath with &lt;scope&gt;provided&lt;/scope&gt;
     * Use WITH CAUTION as you may wind up with duplicate jars/classes.
     *
     * @since jetty-7.6.3
     */
    @Parameter(defaultValue = "false")
    private boolean useProvidedScope;

    /**
     * The artifacts for the project.
     *
     * @since jetty-7.6.3
     */
    @Parameter(defaultValue = "${project.artifacts}", readonly = true)
    private Set<Artifact> projectArtifacts;

    /**
     * The maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The artifacts for the plugin itself.
     */
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true)
    private List<Artifact> pluginArtifacts;

    /**
     * File into which to generate the &lt;servlet&gt; and
     * &lt;servlet-mapping&gt; tags for the compiled jsps
     */
    @Parameter(defaultValue = "${basedir}/target/webfrag.xml")
    private String webXmlFragment;

    /**
     * Optional. A marker string in the src web.xml file which indicates where
     * to merge in the generated web.xml fragment. Note that the marker string
     * will NOT be preserved during the insertion. Can be left blank, in which
     * case the generated fragment is inserted just before the &lt;/web-app&gt;
     * line
     */
    @Parameter
    private String insertionMarker;

    /**
     * Merge the generated fragment file with the web.xml from
     * webAppSourceDirectory. The merged file will go into the same directory as
     * the webXmlFragment.
     */
    @Parameter(defaultValue = "true")
    private boolean mergeFragment;

    /**
     * The destination directory into which to put the compiled jsps.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private String generatedClasses;

    /**
     * Controls whether or not .java files generated during compilation will be
     * preserved.
     */
    @Parameter(defaultValue = "false")
    private boolean keepSources;

    /**
     * Root directory for all html/jsp etc files
     */
    @Parameter(defaultValue = "${basedir}/src/main/webapp")
    private String webAppSourceDirectory;

    /**
     * Location of web.xml. Defaults to src/main/webapp/web.xml.
     */
    @Parameter(defaultValue = "${basedir}/src/main/webapp/WEB-INF/web.xml")
    private String webXml;

    /**
     * The comma separated list of patterns for file extensions to be processed. By default
     * will include all .jsp and .jspx files.
     */
    @Parameter(defaultValue = "**\\/*.jsp, **\\/*.jspx")
    private String includes;

    /**
     * The comma separated list of file name patters to exclude from compilation.
     */
    @Parameter(defaultValue = "**\\/.svn\\/**")
    private String excludes;

    /**
     * The location of the compiled classes for the webapp
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File classesDirectory;

    /**
     * Patterns of jars on the system path that contain tlds. Use | to separate each pattern.
     */
    @Parameter(defaultValue = ".*taglibs[^/]*\\.jar|.*jstl[^/]*\\.jar$")
    private String tldJarNamePatterns;

    /**
     * Source version - if not set defaults to jsp default (currently 1.7)
     */
    @Parameter
    private String sourceVersion;

    /**
     * Target version - if not set defaults to jsp default (currently 1.7)
     */
    @Parameter
    private String targetVersion;

    /**
     * The JspC instance being used to compile the jsps.
     */
    @Parameter
    private JettyJspC jspc;

    /**
     * Whether dirs on the classpath should be scanned as well as jars.
     * True by default. This allows for scanning for tlds of dependent projects that
     * are in the reactor as unassembled jars.
     */
    @Parameter(defaultValue = "true")
    private boolean scanAllDirectories;

    /**
     * Determines if the manifest of JAR files found on the classpath should be scanned.
     * True by default.
     */
    @Parameter(defaultValue = "true")
    private boolean scanManifest;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (getLog().isDebugEnabled())
        {

            getLog().info("webAppSourceDirectory=" + webAppSourceDirectory);
            getLog().info("generatedClasses=" + generatedClasses);
            getLog().info("webXmlFragment=" + webXmlFragment);
            getLog().info("webXml=" + webXml);
            getLog().info("insertionMarker=" + (insertionMarker == null || insertionMarker.equals("") ? END_OF_WEBAPP : insertionMarker));
            getLog().info("keepSources=" + keepSources);
            getLog().info("mergeFragment=" + mergeFragment);
            if (sourceVersion != null)
                getLog().info("sourceVersion=" + sourceVersion);
            if (targetVersion != null)
                getLog().info("targetVersion=" + targetVersion);
        }
        try
        {
            prepare();
            compile();
            cleanupSrcs();
            mergeWebXml();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Failure processing jsps", e);
        }
    }

    public void compile() throws Exception
    {
        ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();

        //set up the classpath of the webapp
        List<URL> webAppUrls = setUpWebAppClassPath();

        //set up the classpath of the container (ie jetty and jsp jars)
        Set<URL> pluginJars = getPluginJars();
        Set<URL> providedJars = getProvidedScopeJars(pluginJars);

        //Make a classloader so provided jars will be on the classpath
        List<URL> sysUrls = new ArrayList<>();
        sysUrls.addAll(providedJars);
        URLClassLoader sysClassLoader = new URLClassLoader(sysUrls.toArray(new URL[0]), currentClassLoader);

        //make a classloader with the webapp classpath
        URLClassLoader webAppClassLoader = new URLClassLoader(webAppUrls.toArray(new URL[0]), sysClassLoader);
        StringBuilder webAppClassPath = new StringBuilder();

        for (int i = 0; i < webAppUrls.size(); i++)
        {
            if (getLog().isDebugEnabled())
                getLog().debug("webappclassloader contains: " + webAppUrls.get(i));
            webAppClassPath.append(new File(webAppUrls.get(i).toURI()).getCanonicalPath());
            if (getLog().isDebugEnabled())
                getLog().debug("added to classpath: " + (webAppUrls.get(i)).getFile());
            if (i + 1 < webAppUrls.size())
                webAppClassPath.append(System.getProperty("path.separator"));
        }

        //Interpose a fake classloader as the webapp class loader. This is because the Apache JspC class
        //uses a TldScanner which ignores jars outside of the WEB-INF/lib path on the webapp classloader.
        //It will, however, look at all jars on the parents of the webapp classloader.
        URLClassLoader fakeWebAppClassLoader = new URLClassLoader(new URL[0], webAppClassLoader);
        Thread.currentThread().setContextClassLoader(fakeWebAppClassLoader);

        if (jspc == null)
            jspc = new JettyJspC();

        jspc.setWebXmlInclude(webXmlFragment);
        jspc.setUriroot(webAppSourceDirectory);
        jspc.setOutputDir(generatedClasses);
        jspc.setClassLoader(fakeWebAppClassLoader);
        jspc.setScanAllDirectories(scanAllDirectories);
        jspc.setScanManifest(scanManifest);
        jspc.setCompile(true);
        if (sourceVersion != null)
            jspc.setCompilerSourceVM(sourceVersion);
        if (targetVersion != null)
            jspc.setCompilerTargetVM(targetVersion);

        // JspC#setExtensions() does not exist, so 
        // always set concrete list of files that will be processed.
        String jspFiles = getJspFiles(webAppSourceDirectory);

        try
        {
            if (jspFiles == null | jspFiles.equals(""))
            {
                getLog().info("No files selected to precompile");
            }
            else
            {
                getLog().info("Compiling " + jspFiles + " from includes=" + includes + " excludes=" + excludes);
                jspc.setJspFiles(jspFiles);
                jspc.execute();
            }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
        }
    }

    private String getJspFiles(String webAppSourceDirectory)
        throws Exception
    {
        List<String> fileNames = FileUtils.getFileNames(new File(webAppSourceDirectory), includes, excludes, false);
        return StringUtils.join(fileNames.toArray(new String[0]), ",");
    }

    /**
     * Until Jasper supports the option to generate the srcs in a different dir
     * than the classes, this is the best we can do.
     *
     * @throws Exception if unable to clean srcs
     */
    public void cleanupSrcs() throws Exception
    {
        // delete the .java files - depending on keepGenerated setting
        if (!keepSources)
        {
            File generatedClassesDir = new File(generatedClasses);

            if (generatedClassesDir.exists() && generatedClassesDir.isDirectory())
            {
                delete(generatedClassesDir, pathname ->
                {
                    return pathname.isDirectory() || pathname.getName().endsWith(".java");
                });
            }
        }
    }

    static void delete(File dir, FileFilter filter)
    {
        File[] files = dir.listFiles(filter);
        if (files != null)
        {
            for (File f : files)
            {
                if (f.isDirectory())
                    delete(f, filter);
                else
                    f.delete();
            }
        }
    }

    /**
     * Take the web fragment and put it inside a copy of the web.xml.
     *
     * You can specify the insertion point by specifying the string in the
     * insertionMarker configuration entry.
     *
     * If you dont specify the insertionMarker, then the fragment will be
     * inserted at the end of the file just before the &lt;/webapp&gt;
     *
     * @throws Exception if unable to merge the web xml
     */
    public void mergeWebXml() throws Exception
    {
        if (mergeFragment)
        {
            // open the src web.xml
            File webXml = getWebXmlFile();

            if (!webXml.exists())
            {
                getLog().info(webXml.toString() + " does not exist, cannot merge with generated fragment");
                return;
            }

            File fragmentWebXml = new File(webXmlFragment);
            File mergedWebXml = new File(fragmentWebXml.getParentFile(), "web.xml");

            try (BufferedReader webXmlReader = new BufferedReader(new FileReader(webXml));
                 PrintWriter mergedWebXmlWriter = new PrintWriter(new FileWriter(mergedWebXml)))
            {

                if (!fragmentWebXml.exists())
                {
                    getLog().info("No fragment web.xml file generated");
                    //just copy existing web.xml to expected position
                    IO.copy(webXmlReader, mergedWebXmlWriter);
                }
                else
                {
                    // read up to the insertion marker or the </webapp> if there is no
                    // marker
                    boolean atInsertPoint = false;
                    boolean atEOF = false;
                    String marker = (insertionMarker == null || insertionMarker.equals("") ? END_OF_WEBAPP : insertionMarker);
                    while (!atInsertPoint && !atEOF)
                    {
                        String line = webXmlReader.readLine();
                        if (line == null)
                            atEOF = true;
                        else if (line.indexOf(marker) >= 0)
                        {
                            atInsertPoint = true;
                        }
                        else
                        {
                            mergedWebXmlWriter.println(line);
                        }
                    }

                    if (atEOF && !atInsertPoint)
                        throw new IllegalStateException("web.xml does not contain insertionMarker " + insertionMarker);

                    //put in a context init-param to flag that the contents have been precompiled
                    mergedWebXmlWriter.println("<context-param><param-name>" + PRECOMPILED_FLAG + "</param-name><param-value>true</param-value></context-param>");

                    // put in the generated fragment
                    try (BufferedReader fragmentWebXmlReader =
                             new BufferedReader(new FileReader(fragmentWebXml)))
                    {
                        IO.copy(fragmentWebXmlReader, mergedWebXmlWriter);

                        // if we inserted just before the </web-app>, put it back in
                        if (marker.equals(END_OF_WEBAPP))
                            mergedWebXmlWriter.println(END_OF_WEBAPP);

                        // copy in the rest of the original web.xml file
                        IO.copy(webXmlReader, mergedWebXmlWriter);
                    }
                }
            }
        }
    }

    private void prepare() throws Exception
    {
        // For some reason JspC doesn't like it if the dir doesn't
        // already exist and refuses to create the web.xml fragment
        File generatedSourceDirectoryFile = new File(generatedClasses);
        if (!generatedSourceDirectoryFile.exists())
            generatedSourceDirectoryFile.mkdirs();
    }

    /**
     * Set up the execution classpath for Jasper.
     *
     * Put everything in the classesDirectory and all of the dependencies on the
     * classpath.
     *
     * @returns a list of the urls of the dependencies
     */
    private List<URL> setUpWebAppClassPath() throws Exception
    {
        //add any classes from the webapp
        List<URL> urls = new ArrayList<URL>();
        String classesDir = classesDirectory.getCanonicalPath();
        classesDir = classesDir + (classesDir.endsWith(File.pathSeparator) ? "" : File.separator);
        urls.add(Resource.toURL(new File(classesDir)));

        if (getLog().isDebugEnabled())
            getLog().debug("Adding to classpath classes dir: " + classesDir);

        //add the dependencies of the webapp (which will form WEB-INF/lib)
        for (Iterator<Artifact> iter = project.getArtifacts().iterator(); iter.hasNext(); )
        {
            Artifact artifact = iter.next();

            // Include runtime and compile time libraries
            if (!Artifact.SCOPE_TEST.equals(artifact.getScope()) && !Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
            {
                String filePath = artifact.getFile().getCanonicalPath();
                if (getLog().isDebugEnabled())
                    getLog().debug("Adding to classpath dependency file: " + filePath);

                urls.add(Resource.toURL(artifact.getFile()));
            }
        }
        return urls;
    }

    /**
     *
     */
    private Set<URL> getPluginJars() throws MalformedURLException
    {
        HashSet<URL> pluginJars = new HashSet<>();
        for (Iterator<Artifact> iter = pluginArtifacts.iterator(); iter.hasNext(); )
        {
            Artifact pluginArtifact = iter.next();
            if ("jar".equalsIgnoreCase(pluginArtifact.getType()))
            {
                if (getLog().isDebugEnabled())
                {
                    getLog().debug("Adding plugin artifact " + pluginArtifact);
                }
                pluginJars.add(pluginArtifact.getFile().toURI().toURL());
            }
        }

        return pluginJars;
    }

    /**
     *
     */
    private Set<URL> getProvidedScopeJars(Set<URL> pluginJars) throws MalformedURLException
    {
        if (!useProvidedScope)
            return Collections.emptySet();

        HashSet<URL> providedJars = new HashSet<>();

        for (Iterator<Artifact> iter = projectArtifacts.iterator(); iter.hasNext(); )
        {
            Artifact artifact = iter.next();
            if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
            {
                //test to see if the provided artifact was amongst the plugin artifacts
                URL jar = artifact.getFile().toURI().toURL();
                if (!pluginJars.contains(jar))
                {
                    providedJars.add(jar);
                    if (getLog().isDebugEnabled())
                    {
                        getLog().debug("Adding provided artifact: " + artifact);
                    }
                }
                else
                {
                    if (getLog().isDebugEnabled())
                    {
                        getLog().debug("Skipping provided artifact: " + artifact);
                    }
                }
            }
        }
        return providedJars;
    }

    private File getWebXmlFile()
        throws IOException
    {
        File file = null;
        File baseDir = project.getBasedir().getCanonicalFile();
        File defaultWebAppSrcDir = new File(baseDir, "src/main/webapp").getCanonicalFile();
        File webAppSrcDir = new File(webAppSourceDirectory).getCanonicalFile();
        File defaultWebXml = new File(defaultWebAppSrcDir, "web.xml").getCanonicalFile();

        //If the web.xml has been changed from the default, try that
        File webXmlFile = new File(webXml).getCanonicalFile();
        if (webXmlFile.compareTo(defaultWebXml) != 0)
        {
            file = new File(webXml);
            return file;
        }

        //If the web app src directory has not been changed from the default, use whatever
        //is set for the web.xml location
        file = new File(webAppSrcDir, "web.xml");
        return file;
    }
}

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

package org.eclipse.jetty.test.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.junit.jupiter.api.Assertions;

/**
 * Basic process based executor for using the Jetty Distribution along with custom configurations to perform basic
 * <p>
 * Allows for a test specific directory, that is a copied jetty-distribution, and then modified for the test specific testing required.
 * <p>
 * Requires that you setup the maven-dependency-plugin appropriately for the base distribution you want to use, along with any other dependencies (wars, libs,
 * etc..) that you may need from other maven projects.
 * <p>
 * Maven Dependency Plugin Setup:
 *
 * <pre>
 *  &lt;project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
 *    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd"&gt;
 *
 *   &lt;!-- Common Destination Directories --&gt;
 *
 *   &lt;properties&gt;
 *     &lt;test-wars-dir&gt;${project.build.directory}/test-wars&lt;/test-wars-dir&gt;
 *     &lt;test-libs-dir&gt;${project.build.directory}/test-libs&lt;/test-libs-dir&gt;
 *     &lt;test-distro-dir&gt;${project.build.directory}/test-dist&lt;/test-distro-dir&gt;
 *   &lt;/properties&gt;
 *
 *   &lt;build&gt;
 *     &lt;plugins&gt;
 *       &lt;plugin&gt;
 *         &lt;groupId&gt;org.apache.maven.plugins&lt;/groupId&gt;
 *         &lt;artifactId&gt;maven-dependency-plugin&lt;/artifactId&gt;
 *         &lt;version&gt;2.1&lt;/version&gt;
 *         &lt;executions&gt;
 *
 *           &lt;!-- Copy LIB and WAR dependencies into place that JettyDistro can use them --&gt;
 *
 *           &lt;execution&gt;
 *             &lt;id&gt;test-lib-war-copy&lt;/id&gt;
 *             &lt;phase&gt;process-test-resources&lt;/phase&gt;
 *             &lt;goals&gt;
 *               &lt;goal&gt;copy&lt;/goal&gt;
 *             &lt;/goals&gt;
 *             &lt;configuration&gt;
 *               &lt;artifactItems&gt;
 *                 &lt;artifactItem&gt;
 *                   &lt;groupId&gt;org.mortbay.jetty.testwars&lt;/groupId&gt;
 *                   &lt;artifactId&gt;test-war-java_util_logging&lt;/artifactId&gt;
 *                   &lt;version&gt;7.3.0&lt;/version&gt;
 *                   &lt;type&gt;war&lt;/type&gt;
 *                   &lt;outputDirectory&gt;${test-wars-dir}&lt;/outputDirectory&gt;
 *                 &lt;/artifactItem&gt;
 *                 &lt;artifactItem&gt;
 *                   &lt;groupId&gt;org.mortbay.jetty&lt;/groupId&gt;
 *                   &lt;artifactId&gt;jetty-aspect-servlet-api-2.5&lt;/artifactId&gt;
 *                   &lt;version&gt;7.3.0&lt;/version&gt;
 *                   &lt;type&gt;jar&lt;/type&gt;
 *                   &lt;outputDirectory&gt;${test-libs-dir}&lt;/outputDirectory&gt;
 *                 &lt;/artifactItem&gt;
 *               &lt;/artifactItems&gt;
 *               &lt;overWriteIfNewer&gt;true&lt;/overWriteIfNewer&gt;
 *               &lt;overWrite&gt;true&lt;/overWrite&gt;
 *               &lt;stripVersion&gt;true&lt;/stripVersion&gt;
 *             &lt;/configuration&gt;
 *           &lt;/execution&gt;
 *
 *           &lt;!-- Extract Jetty DISTRIBUTION into place that JettyDistro can use it --&gt;
 *
 *           &lt;execution&gt;
 *             &lt;id&gt;unpack-test-dist&lt;/id&gt;
 *             &lt;phase&gt;process-test-resources&lt;/phase&gt;
 *             &lt;goals&gt;
 *               &lt;goal&gt;unpack&lt;/goal&gt;
 *             &lt;/goals&gt;
 *             &lt;configuration&gt;
 *               &lt;artifactItems&gt;
 *                 &lt;artifactItem&gt;
 *                   &lt;groupId&gt;org.eclipse.jetty&lt;/groupId&gt;
 *                   &lt;artifactId&gt;jetty-distribution&lt;/artifactId&gt;
 *                   &lt;version&gt;7.3.0&lt;/version&gt;
 *                   &lt;type&gt;zip&lt;/type&gt;
 *                   &lt;overWrite&gt;true&lt;/overWrite&gt;
 *                 &lt;/artifactItem&gt;
 *               &lt;/artifactItems&gt;
 *               &lt;outputAbsoluteArtifactFilename&gt;true&lt;/outputAbsoluteArtifactFilename&gt;
 *               &lt;outputDirectory&gt;${test-distro-dir}&lt;/outputDirectory&gt;
 *               &lt;overWriteSnapshots&gt;true&lt;/overWriteSnapshots&gt;
 *               &lt;overWriteIfNewer&gt;true&lt;/overWriteIfNewer&gt;
 *             &lt;/configuration&gt;
 *           &lt;/execution&gt;
 *         &lt;/executions&gt;
 *       &lt;/plugin&gt;
 *     &lt;/plugins&gt;
 *   &lt;/build&gt;
 *
 * &lt;/project&gt;
 * </pre>
 * <p>
 * If you have a specific configuration you want to setup, you'll want to prepare this configuration in an overlay directory underneath the
 * <code>src/test/resources/</code> directory. <br>
 * Notes:
 * <ol>
 * <li>The {@link JettyDistro} sets up a unique test directory (based on the constructor {@link #JettyDistro(Class)} or {@link #JettyDistro(org.eclipse.jetty.toolchain.test.jupiter.WorkDir)}), by
 * ensuring the directory is empty, then copying the <code>target/test-dist</code> directory into this new testing directory prior to the test specific changes
 * to the configuration.<br>
 * Note: this testing directory is a complete jetty distribution, suitable for executing via the command line for additional testing needs.</li>
 * <li>The directory name you choose in <code>src/test/resources</code> will be the name you use in the {@link #overlayConfig(String)} method to provide
 * replacement configurations for the Jetty Distribution.</li>
 * <li>You'll want to {@link #delete(String)} any files and/or directories from the standard distribution prior to using the {@link #overlayConfig(String)}
 * method.</li>
 * <li>Use the {@link #copyLib(String, String)} method to copy JAR files from the <code>target/test-libs</code> directory (created and managed above using the
 * <code>maven-dependency-plugin</code>) to copy the lib into the test specific.</li>
 * <li>Use the {@link #copyTestWar(String)} method to copy WAR files from the <code>target/test-wars</code> directory (created and managed above using the
 * <code>maven-dependency-plugin</code>) to copy the WAR into the test specific directory.</li>
 * </ol>
 * <p>
 * Next you'll want to use Junit 4.8+ and the <code>&#064;BeforeClass</code> and <code>&#064;AfterClass</code> annotations to setup the <code>JettyDistro</code>
 * class for setting up your testing configuration.
 * <p>
 * Example Test Case using {@link JettyDistro} class
 *
 * <pre>
 * public class MySampleTest
 * {
 *     private static JettyDistro jetty;
 *
 *     &#064;BeforeClass
 *     public static void initJetty() throws Exception
 *     {
 *         jetty = new JettyDistro(MySampleTest.class);
 *
 *         jetty.copyTestWar(&quot;test-war-java_util_logging.war&quot;);
 *         jetty.copyTestWar(&quot;test-war-policy.war&quot;);
 *
 *         jetty.delete(&quot;webapps/test.war&quot;);
 *         jetty.delete(&quot;contexts/test.d&quot;);
 *         jetty.delete(&quot;contexts/javadoc.xml&quot;);
 *         jetty.delete(&quot;contexts/test.xml&quot;);
 *
 *         jetty.overlayConfig(&quot;no_security&quot;);
 *
 *         jetty.setDebug(true);
 *
 *         jetty.start();
 *     }
 *
 *     &#064;AfterClass
 *     public static void shutdownJetty() throws Exception
 *     {
 *         if (jetty != null)
 *         {
 *             jetty.stop();
 *         }
 *     }
 *
 *     &#064;Test
 *     public void testRequest() throws Exception
 *     {
 *         SimpleRequest request = new SimpleRequest(jetty.getBaseUri());
 *         String path = &quot;/test-war-policy/security/PRACTICAL/testFilsystem&quot;);
 *         String response = request.getString(path);
 *         assertEquals(&quot;Success&quot;, response);
 *     }
 * }
 * </pre>
 */
public class JettyDistro
{
    private String artifactName = "jetty-distribution";
    private long startTime = 60;
    private TimeUnit timeUnit = TimeUnit.SECONDS;

    private File jettyHomeDir;
    private Process pid;
    private URI baseUri;

    private String jmxUrl;

    private boolean _debug = false;

    /**
     * Setup the JettyHome as belonging in a testing directory associated with a testing clazz.
     *
     * @param clazz the testing class using this JettyDistro
     * @throws IOException if unable to copy unpacked distribution into place for the provided testing directory
     */
    public JettyDistro(Class<?> clazz) throws IOException
    {
        this(clazz, null);
    }

    /**
     * Setup the JettyHome as belonging in a testing directory associated with a testing clazz.
     *
     * @param clazz the testing class using this JettyDistro
     * @param artifact name of jetty distribution artifact
     * @throws IOException if unable to copy unpacked distribution into place for the provided testing directory
     */
    public JettyDistro(Class<?> clazz, String artifact) throws IOException
    {
        this.jettyHomeDir = MavenTestingUtils.getTargetTestingPath(clazz, "jettyHome").toFile();
        if (artifact != null)
        {
            this.artifactName = artifact;
        }

        copyBaseDistro();
    }

    /**
     * Setup the JettyHome as belonging to a specific testing method directory
     *
     * @param testdir the testing directory to use as the JettyHome for this JettyDistro
     * @throws IOException if unable to copy unpacked distribution into place for the provided testing directory
     */
    public JettyDistro(WorkDir testdir) throws IOException
    {
        this.jettyHomeDir = testdir.getPath().toFile();
        copyBaseDistro();
    }

    /**
     * Setup the JettyHome as belonging to a specific testing method directory
     *
     * @param testdir the testing directory to use as the JettyHome for this JettyDistro
     * @param artifact name of jetty distribution artifact
     * @throws IOException if unable to copy unpacked distribution into place for the provided testing directory
     */
    public JettyDistro(WorkDir testdir, String artifact) throws IOException
    {
        this.jettyHomeDir = testdir.getPath().toFile();
        if (artifact != null)
        {
            this.artifactName = artifact;
        }

        copyBaseDistro();
    }

    /**
     * @throws IOException if unable to copy unpacked distribution into place for the provided testing directory
     */
    private void copyBaseDistro() throws IOException
    {
        // The outputDirectory for the maven side dependency:unpack goal.
        File distroUnpackDir = MavenTestingUtils.getTargetFile("test-dist");
        PathAssert.assertDirExists(artifactName + " dependency:unpack", distroUnpackDir);

        // The actual jetty-distribution-${version} directory is under this directory.
        // Lets find it.
        File[] subdirs = distroUnpackDir.listFiles(path ->
            {
                if (!path.isDirectory())
                {
                    return false;
                }
                return path.getName().startsWith(artifactName + "-");
            }
        );

        if (subdirs.length == 0)
        {
            // No jetty-distribution found.
            StringBuilder err = new StringBuilder();
            err.append("No target/test-dist/");
            err.append(artifactName);
            err.append("-${version} directory found.");
            err.append("\n  To fix this, run 'mvn process-test-resources' to create the directory.");
            throw new IOException(err.toString());
        }

        if (subdirs.length != 1)
        {
            // Too many jetty-distributions found.
            StringBuilder err = new StringBuilder();
            err.append("Too many target/test-dist/");
            err.append(artifactName);
            err.append("-${version} directories found.");
            for (File dir : subdirs)
            {
                err.append("\n  ").append(dir.getAbsolutePath());
            }
            err.append("\n  To fix this, run 'mvn clean process-test-resources' to recreate the target/test-dist directory.");
            throw new IOException(err.toString());
        }

        File distroSrcDir = subdirs[0];
        FS.ensureEmpty(jettyHomeDir);
        System.out.printf("Copying Jetty Distribution: %s%n", distroSrcDir.getAbsolutePath());
        System.out.printf("            To Testing Dir: %s%n", jettyHomeDir.getAbsolutePath());
        IO.copyDir(distroSrcDir, jettyHomeDir);
    }

    /**
     * Return the $(jetty.home) directory being used for this JettyDistro
     *
     * @return the jetty.home directory being used
     */
    public File getJettyHomeDir()
    {
        return this.jettyHomeDir;
    }

    /**
     * Copy a war file from ${project.basedir}/target/test-wars/${testWarFilename} into the ${jetty.home}/webapps/ directory
     *
     * @param testWarFilename the war file to copy (must exist)
     * @throws IOException if unable to copy the war file.
     */
    public void copyTestWar(String testWarFilename) throws IOException
    {
        File srcWar = MavenTestingUtils.getTargetFile("test-wars/" + testWarFilename);
        File destWar = new File(jettyHomeDir, FS.separators("webapps/" + testWarFilename));
        FS.ensureDirExists(destWar.getParentFile());
        IO.copyFile(srcWar, destWar);
    }

    /**
     * Copy an arbitrary file from <code>src/test/resources/${resourcePath}</code> to the testing directory.
     *
     * @param resourcePath the relative path for file content within the <code>src/test/resources</code> directory.
     * @param outputPath the testing directory relative output path for the file output (will result in a file with the outputPath name being created)
     * @throws IOException if unable to copy resource file
     */
    public void copyResource(String resourcePath, String outputPath) throws IOException
    {
        File srcFile = MavenTestingUtils.getTestResourceFile(resourcePath);
        File destFile = new File(jettyHomeDir, FS.separators(outputPath));
        FS.ensureDirExists(destFile.getParentFile());
        IO.copyFile(srcFile, destFile);
    }

    /**
     * Copy an arbitrary file from <code>target/test-libs/${libFilename}</code> to the testing directory.
     *
     * @param libFilename the <code>target/test-libs/${libFilename}</code> to copy
     * @param outputPath the destination testing directory relative output path for the lib. (will result in a file with the outputPath name being created)
     * @throws IOException if unable to copy lib
     */
    public void copyLib(String libFilename, String outputPath) throws IOException
    {
        File srcLib = MavenTestingUtils.getTargetFile("test-libs/" + libFilename);
        File destLib = new File(jettyHomeDir, FS.separators(outputPath));
        FS.ensureDirExists(destLib.getParentFile());
        IO.copyFile(srcLib, destLib);
    }

    /**
     * Copy the <code>${project.basedir}/src/main/config/</code> tree into the testing directory.
     *
     * @throws IOException if unable to copy the directory tree
     */
    public void copyProjectMainConfig() throws IOException
    {
        File srcDir = MavenTestingUtils.getProjectDir("src/main/config");
        IO.copyDir(srcDir, jettyHomeDir);
    }

    /**
     * Create a <code>${jetty.home}/lib/self/${jarFilename}</code> jar file from the content in the <code>${project.basedir}/target/classes/</code> directory.
     *
     * @throws IOException if unable to copy the directory tree
     */
    public void createProjectLib(String jarFilename) throws IOException
    {
        File srcDir = MavenTestingUtils.getTargetFile("classes");
        File libSelfDir = new File(jettyHomeDir, FS.separators("lib/self"));
        FS.ensureDirExists(libSelfDir);
        File jarFile = new File(libSelfDir, jarFilename);
        JAR.create(srcDir, jarFile);
    }

    /**
     * Unpack an arbitrary config from <code>target/test-configs/${configFilename}</code> to the testing directory.
     *
     * @param configFilename the <code>target/test-configs/${configFilename}</code> to copy
     * @throws IOException if unable to unpack config file
     */
    public void unpackConfig(String configFilename) throws IOException
    {
        File srcConfig = MavenTestingUtils.getTargetFile("test-configs/" + configFilename);
        JAR.unpack(srcConfig, jettyHomeDir);
    }

    /**
     * Delete a File or Directory found in the ${jetty.home} directory.
     *
     * @param path the path to delete. (can be a file or directory)
     */
    public void delete(String path)
    {
        File jettyPath = new File(jettyHomeDir, FS.separators(path));
        FS.delete(jettyPath);
    }

    /**
     * Return the baseUri being used for this Jetty Process Instance.
     *
     * @return the base URI for this Jetty Process Instance.
     */
    public URI getBaseUri()
    {
        return this.baseUri;
    }

    /**
     * Return the JMX URL being used for this Jetty Process Instance.
     *
     * @return the JMX URL for this Jetty Process Instance.
     */
    public String getJmxUrl()
    {
        return this.jmxUrl;
    }

    /**
     * Take the directory contents from ${project.basedir}/src/test/resources/${testConfigName}/ and copy it over whatever happens to be at ${jetty.home}
     *
     * @param testConfigName the src/test/resources/ directory name to use as the source diretory for the configuration we are interested in.
     * @throws IOException if unable to copy directory.
     */
    public void overlayConfig(String testConfigName) throws IOException
    {
        File srcDir = MavenTestingUtils.getTestResourceDir(testConfigName);
        IO.copyDir(srcDir, jettyHomeDir);
    }

    /**
     * Start the jetty server
     *
     * @throws IOException if unable to start the server.
     */
    public void start() throws IOException
    {
        List<String> commands = new ArrayList<String>();
        commands.add(getJavaBin());

        commands.add("-Djetty.home=" + jettyHomeDir.getAbsolutePath());

        // Do a dry run first to get the exact command line for Jetty process
        commands.add("-jar");
        commands.add("start.jar");
        commands.add("jetty.http.port=0");
        if (_debug)
        {
            commands.add("-D.DEBUG=true");
        }
        commands.add("--dry-run");

        ProcessBuilder pbCmd = new ProcessBuilder(commands);
        pbCmd.directory(jettyHomeDir);

        String cmdLine = null;
        Process pidCmd = pbCmd.start();
        try
        {
            cmdLine = readOutputLine(pidCmd);
        }
        finally
        {
            pidCmd.destroy();
        }

        if (cmdLine == null || !cmdLine.contains("XmlConfiguration"))
        {
            Assertions.fail("Unable to get Jetty command line");
        }

        // Need to breakdown commandline into parts, as spaces in command line will cause failures.
        List<String> execCommands = splitAndUnescapeCommandLine(cmdLine);

        System.out.printf("Executing: %s%n", cmdLine);
        System.out.printf("Working Dir: %s%n", jettyHomeDir.getAbsolutePath());

        pbCmd = new ProcessBuilder(execCommands);
        pid = pbCmd.start();

        ConsoleParser parser = new ConsoleParser();
        List<String[]> jmxList = parser.newPattern("JMX Remote URL: (.*)", 0);
        List<String[]> connList = parser.newPattern("Started [A-Za-z]*Connector@([0-9]*\\.[0-9]*\\.[0-9]*\\.[0-9]*):([0-9]*)", 1);
        // DISABLED: This is what exists in Jetty 9+
        // List<String[]> connList = parser.newPattern("Started [A-Za-z]*Connector@.*[\\({]([0-9]*\\.[0-9]*\\.[0-9]*\\.[0-9]*):([0-9]*)[\\)}].*",1);

        startPump("STDOUT", parser, this.pid.getInputStream());
        startPump("STDERR", parser, this.pid.getErrorStream());

        try
        {
            parser.waitForDone(this.startTime, this.timeUnit);

            if (!jmxList.isEmpty())
            {
                this.jmxUrl = jmxList.get(0)[0];
                System.out.printf("## Found JMX connector at %s%n", this.jmxUrl);
            }

            if (!connList.isEmpty())
            {
                String[] params = connList.get(0);
                if (params.length == 2)
                {
                    this.baseUri = URI.create("http://localhost:" + params[1] + "/");
                }
                System.out.printf("## Found Jetty connector at host: %s port: %s%n", (Object[])params);
            }
        }
        catch (InterruptedException e)
        {
            pid.destroy();
            Assertions.fail("Unable to get required information within time limit");
        }
    }

    public static List<String> splitAndUnescapeCommandLine(CharSequence rawCmdLine)
    {
        List<String> cmds = new ArrayList<String>();

        int len = rawCmdLine.length();
        StringBuilder arg = new StringBuilder();
        boolean escaped = false;
        boolean inQuote = false;
        char c;
        for (int i = 0; i < len; i++)
        {
            c = rawCmdLine.charAt(i);
            if (escaped)
            {
                switch (c)
                {
                    case 'r':
                        arg.append('\r');
                        break;
                    case 'f':
                        arg.append('\f');
                        break;
                    case 't':
                        arg.append('\t');
                        break;
                    case 'n':
                        arg.append('\n');
                        break;
                    case 'b':
                        arg.append('\b');
                        break;
                    default:
                        arg.append(c);
                        break;
                }
                escaped = false;
                continue;
            }

            if (c == '\\')
            {
                escaped = true;
            }
            else
            {
                if ((c == ' ') && (!inQuote))
                {
                    // the delim!
                    cmds.add(String.valueOf(arg.toString()));
                    arg.setLength(0);
                }
                else if (c == '"')
                {
                    inQuote = !inQuote;
                }
                else
                {
                    arg.append(c);
                }
            }
        }
        cmds.add(String.valueOf(arg.toString()));

        return cmds;
    }

    private String readOutputLine(Process pidCmd) throws IOException
    {
        InputStream in = null;
        InputStreamReader reader = null;
        BufferedReader buf = null;
        try
        {
            in = pidCmd.getInputStream();
            reader = new InputStreamReader(in);
            buf = new BufferedReader(reader);
            return buf.readLine();
        }
        finally
        {
            IO.close(buf);
            IO.close(reader);
            IO.close(in);
        }
    }

    private static class ConsoleParser
    {
        private List<ConsolePattern> patterns = new ArrayList<ConsolePattern>();
        private CountDownLatch latch;
        private int count;

        public List<String[]> newPattern(String exp, int cnt)
        {
            ConsolePattern pat = new ConsolePattern(exp, cnt);
            patterns.add(pat);
            count += cnt;

            return pat.getMatches();
        }

        public void parse(String line)
        {
            for (ConsolePattern pat : patterns)
            {
                Matcher mat = pat.getMatcher(line);
                if (mat.find())
                {
                    int num = 0;
                    int count = mat.groupCount();
                    String[] match = new String[count];
                    while (num++ < count)
                    {
                        match[num - 1] = mat.group(num);
                    }
                    pat.getMatches().add(match);

                    if (pat.getCount() > 0)
                    {
                        getLatch().countDown();
                    }
                }
            }
        }

        public void waitForDone(long timeout, TimeUnit unit) throws InterruptedException
        {
            getLatch().await(timeout, unit);
        }

        private CountDownLatch getLatch()
        {
            synchronized (this)
            {
                if (latch == null)
                {
                    latch = new CountDownLatch(count);
                }
            }

            return latch;
        }
    }

    private static class ConsolePattern
    {
        private Pattern pattern;
        private List<String[]> matches;
        private int count;

        ConsolePattern(String exp, int cnt)
        {
            pattern = Pattern.compile(exp);
            matches = new ArrayList<String[]>();
            count = cnt;
        }

        public Matcher getMatcher(String line)
        {
            return pattern.matcher(line);
        }

        public List<String[]> getMatches()
        {
            return matches;
        }

        public int getCount()
        {
            return count;
        }
    }

    private void startPump(String mode, ConsoleParser parser, InputStream inputStream)
    {
        ConsoleStreamer pump = new ConsoleStreamer(mode, inputStream);
        pump.setParser(parser);
        Thread thread = new Thread(pump, "ConsoleStreamer/" + mode);
        thread.start();
    }

    /**
     * enable debug on the jetty process
     */
    public void setDebug(boolean debug)
    {
        _debug = debug;
    }

    private String getJavaBin()
    {
        String[] javaexes = new String[]
            {"java", "java.exe"};

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir, FS.separators("bin/" + javaexe));
            if (javabin.exists() && javabin.isFile())
            {
                return javabin.getAbsolutePath();
            }
        }

        Assertions.fail("Unable to find java bin");
        return "java";
    }

    /**
     * Stop the jetty server
     */
    public void stop()
    {
        System.out.println("Stopping JettyDistro ...");
        if (pid != null)
        {
            // TODO: maybe issue a STOP instead?
            pid.destroy();
        }
    }

    /**
     * Simple streamer for the console output from a Process
     */
    private static class ConsoleStreamer implements Runnable
    {
        private String mode;
        private BufferedReader reader;
        private ConsoleParser parser;

        public ConsoleStreamer(String mode, InputStream is)
        {
            this.mode = mode;
            this.reader = new BufferedReader(new InputStreamReader(is));
        }

        public void setParser(ConsoleParser connector)
        {
            this.parser = connector;
        }

        @Override
        public void run()
        {
            String line;
            // System.out.printf("ConsoleStreamer/%s initiated%n",mode);
            try
            {
                while ((line = reader.readLine()) != (null))
                {
                    if (parser != null)
                    {
                        parser.parse(line);
                    }
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
            // System.out.printf("ConsoleStreamer/%s finished%n",mode);
        }
    }

    public void setStartTime(long startTime, TimeUnit timeUnit)
    {
        this.startTime = startTime;
        this.timeUnit = timeUnit;
    }
}

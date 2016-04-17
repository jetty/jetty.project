//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.config.ConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.DirConfigSource;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class IncludeJettyDirTest
{
    private static class MainResult
    {
        private Main main;
        private StartArgs args;

        public void assertSearchOrder(List<String> expectedSearchOrder)
        {
            ConfigSources sources = main.getBaseHome().getConfigSources();
            List<String> actualOrder = new ArrayList<>();
            for (ConfigSource source : sources)
            {
                if (source instanceof DirConfigSource)
                {
                    actualOrder.add(source.getId());
                }
            }
            ConfigurationAssert.assertOrdered("Search Order",expectedSearchOrder,actualOrder);
        }

        public void assertProperty(String key, String expectedValue)
        {
            Prop prop = args.getProperties().getProp(key);
            String prefix = "Prop[" + key + "]";
            Assert.assertThat(prefix + " should have a value",prop,notNullValue());
            Assert.assertThat(prefix + " value",prop.value,is(expectedValue));
        }
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    private MainResult runMain(File baseDir, File homeDir, String... cmdLineArgs) throws Exception
    {
        MainResult ret = new MainResult();
        ret.main = new Main();
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("jetty.home=" + homeDir.getAbsolutePath());
        cmdLine.add("jetty.base=" + baseDir.getAbsolutePath());
        // cmdLine.add("--debug");
        for (String arg : cmdLineArgs)
        {
            cmdLine.add(arg);
        }
        ret.args = ret.main.processCommandLine(cmdLine);
        return ret;
    }

    @Test
    public void testNoExtras() throws Exception
    {
        // Create home
       testdir.getPathFile("home").toFile();
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        // Simple command line - no reference to include-jetty-dirs
        MainResult result = runMain(base,home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
    }

    @Test
    public void testCommandLine_1Extra() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        // Simple command line reference to include-jetty-dir
        MainResult result = runMain(base,home,
        // direct reference via path
                "--include-jetty-dir=" + common.getAbsolutePath());

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.getAbsolutePath());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testCommandLine_1Extra_FromSimpleProp() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        // Simple command line reference to include-jetty-dir via property (also on command line)
        MainResult result = runMain(base,home,
        // property
                "my.common=" + common.getAbsolutePath(),
                // reference via property
                "--include-jetty-dir=${my.common}");

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add("${my.common}"); // should see property use
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testCommandLine_1Extra_FromPropPrefix() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create opt
        File opt = testdir.getPathFile("opt").toFile();
        FS.ensureEmpty(opt);

        // Create common
        File common = new File(opt,"common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        String dirRef = "${my.opt}" + File.separator + "common";

        // Simple command line reference to include-jetty-dir via property (also on command line)
        MainResult result = runMain(base,home,
        // property to 'opt' dir
                "my.opt=" + opt.getAbsolutePath(),
                // reference via property prefix
                "--include-jetty-dir=" + dirRef);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(dirRef); // should use property
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testCommandLine_1Extra_FromCompoundProp() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create opt
        File opt = testdir.getPathFile("opt").toFile();
        FS.ensureEmpty(opt);

        // Create common
        File common = new File(opt,"common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        String dirRef = "${my.opt}" + File.separator + "${my.dir}";

        // Simple command line reference to include-jetty-dir via property (also on command line)
        MainResult result = runMain(base,home,
        // property to 'opt' dir
                "my.opt=" + opt.getAbsolutePath(),
                // property to commmon dir name
                "my.dir=common",
                // reference via property prefix
                "--include-jetty-dir=" + dirRef);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(dirRef); // should use property
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testRefCommon() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        MainResult result = runMain(base,home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.getAbsolutePath());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testRefCommonAndCorp() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create corp
        File corp = testdir.getPathFile("corp").toFile();
        FS.ensureEmpty(corp);

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath(), //
                "--include-jetty-dir=" + corp.getAbsolutePath());

        MainResult result = runMain(base,home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.getAbsolutePath());
        expectedSearchOrder.add(corp.getAbsolutePath());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testRefCommonRefCorp() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create corp
        File corp = testdir.getPathFile("corp").toFile();
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini","jetty.http.port=9090");

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "--include-jetty-dir=" + corp.getAbsolutePath(), //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        MainResult result = runMain(base,home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.getAbsolutePath());
        expectedSearchOrder.add(corp.getAbsolutePath());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testRefCommonRefCorp_FromSimpleProps() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create corp
        File corp = testdir.getPathFile("corp").toFile();
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini", //
                "jetty.http.port=9090");

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "my.corp=" + corp.getAbsolutePath(), //
                "--include-jetty-dir=${my.corp}", //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "my.common=" + common.getAbsolutePath(), //
                "--include-jetty-dir=${my.common}");

        MainResult result = runMain(base,home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add("${my.common}");
        expectedSearchOrder.add("${my.corp}");
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testRefCommonRefCorp_CmdLineRef() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create devops
        File devops = testdir.getPathFile("devops").toFile();
        FS.ensureEmpty(devops);
        TestEnv.makeFile(devops,"start.ini", //
                "--module=optional", //
                "jetty.http.port=2222");

        // Create corp
        File corp = testdir.getPathFile("corp").toFile();
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini", //
                "jetty.http.port=9090");

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "--include-jetty-dir=" + corp.getAbsolutePath(), //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        MainResult result = runMain(base,home,
        // command line provided include-jetty-dir ref
                "--include-jetty-dir=" + devops.getAbsolutePath());

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(devops.getAbsolutePath());
        expectedSearchOrder.add(common.getAbsolutePath());
        expectedSearchOrder.add(corp.getAbsolutePath());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","2222"); // from 'devops'
    }

    @Test
    public void testRefCommonRefCorp_CmdLineProp() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create corp
        File corp = testdir.getPathFile("corp").toFile();
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini", //
                "jetty.http.port=9090");

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "--include-jetty-dir=" + corp.getAbsolutePath(), //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        MainResult result = runMain(base,home,
        // command line property should override all others
                "jetty.http.port=7070");

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.getAbsolutePath());
        expectedSearchOrder.add(corp.getAbsolutePath());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host","127.0.0.1");
        result.assertProperty("jetty.http.port","7070"); // from command line
    }

    @Test
    public void testBadDoubleRef() throws Exception
    {
        // Create home
        File home = testdir.getPathFile("home").toFile();
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getPathFile("common").toFile();
        FS.ensureEmpty(common);

        // Create corp
        File corp = testdir.getPathFile("corp").toFile();
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini",
        // standard property
                "jetty.http.port=9090",
                // INTENTIONAL BAD Reference (duplicate)
                "--include-jetty-dir=" + common.getAbsolutePath());

        // Populate common
        TestEnv.makeFile(common,"start.ini",
        // standard property
                "jetty.http.port=8080",
                // reference to corp
                "--include-jetty-dir=" + corp.getAbsolutePath());

        // Create base
        File base = testdir.getPathFile("base").toFile();
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        try
        {
            runMain(base,home);
            Assert.fail("Should have thrown a UsageException");
        }
        catch (UsageException e)
        {
            Assert.assertThat("UsageException",e.getMessage(),containsString("Duplicate"));
        }
    }
}

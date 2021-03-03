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

package org.eclipse.jetty.start;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.config.ConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.DirConfigSource;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
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
            ConfigurationAssert.assertOrdered("Search Order", expectedSearchOrder, actualOrder);
        }

        public void assertProperty(String key, String expectedValue)
        {
            Prop prop = args.getProperties().getProp(key);
            String prefix = "Prop[" + key + "]";
            assertThat(prefix + " should have a value", prop, notNullValue());
            assertThat(prefix + " value", prop.value, is(expectedValue));
        }
    }

    public WorkDir testdir;

    private MainResult runMain(Path baseDir, Path homeDir, String... cmdLineArgs) throws Exception
    {
        MainResult ret = new MainResult();
        ret.main = new Main();
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("jetty.home=" + homeDir.toString());
        cmdLine.add("jetty.base=" + baseDir.toString());
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
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1");

        // Simple command line - no reference to include-jetty-dirs
        MainResult result = runMain(base, home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
    }

    @Test
    public void testCommandLine1Extra() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini", "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1");

        // Simple command line reference to include-jetty-dir
        MainResult result = runMain(base, home,
            // direct reference via path
            "--include-jetty-dir=" + common.toString());

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.toString());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testCommandLine1ExtraFromSimpleProp() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini", "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1");

        // Simple command line reference to include-jetty-dir via property (also on command line)
        MainResult result = runMain(base, home,
            // property
            "my.common=" + common.toString(),
            // reference via property
            "--include-jetty-dir=${my.common}");

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add("${my.common}"); // should see property use
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testCommandLine1ExtraFromPropPrefix() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create opt
        Path opt = testdir.getPathFile("opt");
        FS.ensureEmpty(opt);

        // Create common
        Path common = opt.resolve("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini", "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1");

        String dirRef = "${my.opt}" + File.separator + "common";

        // Simple command line reference to include-jetty-dir via property (also on command line)
        MainResult result = runMain(base, home,
            // property to 'opt' dir
            "my.opt=" + opt.toString(),
            // reference via property prefix
            "--include-jetty-dir=" + dirRef);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(dirRef); // should use property
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testCommandLine1ExtraFromCompoundProp() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create opt
        Path opt = testdir.getPathFile("opt");
        FS.ensureEmpty(opt);

        // Create common
        Path common = opt.resolve("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini", "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1");

        String dirRef = "${my.opt}" + File.separator + "${my.dir}";

        // Simple command line reference to include-jetty-dir via property (also on command line)
        MainResult result = runMain(base, home,
            // property to 'opt' dir
            "my.opt=" + opt.toString(),
            // property to commmon dir name
            "my.dir=common",
            // reference via property prefix
            "--include-jetty-dir=" + dirRef);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(dirRef); // should use property
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testRefCommon() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini", "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common.toString());

        MainResult result = runMain(base, home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.toString());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testRefCommonAndCorp() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini", "jetty.http.port=8080");

        // Create corp
        Path corp = testdir.getPathFile("corp");
        FS.ensureEmpty(corp);

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common.toString(),
            "--include-jetty-dir=" + corp.toString());

        MainResult result = runMain(base, home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.toString());
        expectedSearchOrder.add(corp.toString());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testRefCommonRefCorp() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create corp
        Path corp = testdir.getPathFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp, "start.ini", "jetty.http.port=9090");

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini",
            "--include-jetty-dir=" + corp.toString(),
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common.toString());

        MainResult result = runMain(base, home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.toString());
        expectedSearchOrder.add(corp.toString());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testRefCommonRefCorpFromSimpleProps() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create corp
        Path corp = testdir.getPathFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp, "start.ini",
            "jetty.http.port=9090");

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini",
            "my.corp=" + corp.toString(),
            "--include-jetty-dir=${my.corp}",
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "my.common=" + common.toString(),
            "--include-jetty-dir=${my.common}");

        MainResult result = runMain(base, home);

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add("${my.common}");
        expectedSearchOrder.add("${my.corp}");
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "8080"); // from 'common'
    }

    @Test
    public void testRefCommonRefCorpCmdLineRef() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create devops
        Path devops = testdir.getPathFile("devops");
        FS.ensureEmpty(devops);
        TestEnv.makeFile(devops, "start.ini",
            "--module=optional",
            "jetty.http.port=2222");

        // Create corp
        Path corp = testdir.getPathFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp, "start.ini",
            "jetty.http.port=9090");

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini",
            "--include-jetty-dir=" + corp.toString(),
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common.toString());

        MainResult result = runMain(base, home,
            // command line provided include-jetty-dir ref
            "--include-jetty-dir=" + devops.toString());

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(devops.toString());
        expectedSearchOrder.add(common.toString());
        expectedSearchOrder.add(corp.toString());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "2222"); // from 'devops'
    }

    @Test
    public void testRefCommonRefCorpCmdLineProp() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create corp
        Path corp = testdir.getPathFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp, "start.ini",
            "jetty.http.port=9090");

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini",
            "--include-jetty-dir=" + corp.toString(),
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common.toString());

        MainResult result = runMain(base, home,
            // command line property should override all others
            "jetty.http.port=7070");

        List<String> expectedSearchOrder = new ArrayList<>();
        expectedSearchOrder.add("${jetty.base}");
        expectedSearchOrder.add(common.toString());
        expectedSearchOrder.add(corp.toString());
        expectedSearchOrder.add("${jetty.home}");
        result.assertSearchOrder(expectedSearchOrder);

        result.assertProperty("jetty.http.host", "127.0.0.1");
        result.assertProperty("jetty.http.port", "7070"); // from command line
    }

    @Test
    public void testBadDoubleRef() throws Exception
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);

        // Create corp
        Path corp = testdir.getPathFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp, "start.ini",
            // standard property
            "jetty.http.port=9090",
            // INTENTIONAL BAD Reference (duplicate)
            "--include-jetty-dir=" + common.toString());

        // Populate common
        TestEnv.makeFile(common, "start.ini",
            // standard property
            "jetty.http.port=8080",
            // reference to corp
            "--include-jetty-dir=" + corp.toString());

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common.toString());

        UsageException e = assertThrows(UsageException.class, () -> runMain(base, home));
        assertThat("UsageException", e.getMessage(), containsString("Duplicate"));
    }
}

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

package org.eclipse.jetty.start.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.TestEnv;
import org.eclipse.jetty.start.UsageException;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class ConfigSourcesTest
{
    public WorkDir testdir;

    private void assertIdOrder(ConfigSources sources, String... expectedOrder)
    {
        List<String> actualList = new ArrayList<>();
        for (ConfigSource source : sources)
        {
            actualList.add(source.getId());
        }
        List<String> expectedList = Arrays.asList(expectedOrder);
        assertThat("ConfigSources.id order", actualList, contains(expectedList.toArray()));
    }

    private void assertDirOrder(ConfigSources sources, Path... expectedDirOrder) throws IOException
    {
        List<String> actualList = new ArrayList<>();
        for (ConfigSource source : sources)
        {
            if (source instanceof DirConfigSource)
            {
                actualList.add(((DirConfigSource)source).getDir().toString());
            }
        }
        List<String> expectedList = new ArrayList<>();
        for (Path path : expectedDirOrder)
        {
            expectedList.add(path.toRealPath().toString());
        }
        assertThat("ConfigSources.dir order", actualList, contains(expectedList.toArray()));
    }

    private void assertProperty(ConfigSources sources, String key, String expectedValue)
    {
        Prop prop = sources.getProp(key);
        assertThat("getProp('" + key + "') should not be null", prop, notNullValue());
        assertThat("getProp('" + key + "')", prop.value, is(expectedValue));
    }

    @Test
    public void testOrderBasicConfig() throws IOException
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

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyBaseConfigSource(base));
        sources.add(new JettyHomeConfigSource(home));

        assertIdOrder(sources, "<command-line>", "${jetty.base}", "${jetty.home}");
    }

    @Test
    public void testOrderWith1ExtraConfig() throws IOException
    {
        // Create home
        Path home = testdir.getPathFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home", home);

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common.toFile());
        common = common.toRealPath();

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common);

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toRealPath()));
        sources.add(new JettyBaseConfigSource(base.toRealPath()));

        assertIdOrder(sources, "<command-line>", "${jetty.base}", common.toString(), "${jetty.home}");
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

        ConfigSources sources = new ConfigSources();

        // Simple command line reference to include-jetty-dir via property (also on command line)

        String[] cmdLine = new String[]{
            // property
            "my.common=" + common,
            // reference via property
            "--include-jetty-dir=${my.common}"
        };

        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>", "${jetty.base}", "${my.common}", "${jetty.home}");

        assertDirOrder(sources, base, common, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "8080"); // from 'common'
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

        ConfigSources sources = new ConfigSources();

        // Simple command line reference to include-jetty-dir via property (also on command line)
        String[] cmdLine = new String[]{
            // property to 'opt' dir
            "my.opt=" + opt,
            // reference via property prefix
            "--include-jetty-dir=" + dirRef
        };

        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>", "${jetty.base}", dirRef, "${jetty.home}");

        assertDirOrder(sources, base, common, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "8080"); // from 'common'
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

        ConfigSources sources = new ConfigSources();

        // Simple command line reference to include-jetty-dir via property (also on command line)

        String[] cmdLine = new String[]{
            // property to 'opt' dir
            "my.opt=" + opt,
            // property to common dir name
            "my.dir=common",
            // reference via property prefix
            "--include-jetty-dir=" + dirRef
        };

        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>", "${jetty.base}", dirRef, "${jetty.home}");

        assertDirOrder(sources, base, common, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "8080"); // from 'common'
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
            "--include-jetty-dir=" + common);

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>", "${jetty.base}", common.toString(), "${jetty.home}");

        assertDirOrder(sources, base, common, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "8080"); // from 'common'
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
            "--include-jetty-dir=" + common,
            "--include-jetty-dir=" + corp.toString());

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>", "${jetty.base}",
            common.toString(),
            corp.toString(),
            "${jetty.home}");

        assertDirOrder(sources, base, common, corp, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "8080"); // from 'common'
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
        TestEnv.makeFile(corp, "start.ini",
            "jetty.http.port=9090");

        // Create common
        Path common = testdir.getPathFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common, "start.ini",
            "--include-jetty-dir=" + corp,
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common);

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>", "${jetty.base}",
            common.toString(),
            corp.toString(),
            "${jetty.home}");

        assertDirOrder(sources, base, common, corp, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "8080"); // from 'common'
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
            "my.corp=" + corp,
            "--include-jetty-dir=${my.corp}",
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "my.common=" + common,
            "--include-jetty-dir=${my.common}");

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>",
            "${jetty.base}",
            "${my.common}",
            "${my.corp}",
            "${jetty.home}");

        assertDirOrder(sources, base, common, corp, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "8080"); // from 'common'
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
            "--module=logging",
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
            "--include-jetty-dir=" + corp,
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common);

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[]{
            // command line provided include-jetty-dir ref
            "--include-jetty-dir=" + devops
        };
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>",
            "${jetty.base}",
            devops.toString(),
            common.toString(),
            corp.toString(),
            "${jetty.home}");

        assertDirOrder(sources, base, devops, common, corp, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "2222"); // from 'common'
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
            "--include-jetty-dir=" + corp,
            "jetty.http.port=8080");

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common);

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[]{
            // command line property should override all others
            "jetty.http.port=7070"
        };
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home));
        sources.add(new JettyBaseConfigSource(base));

        assertIdOrder(sources, "<command-line>", "${jetty.base}",
            common.toString(),
            corp.toString(),
            "${jetty.home}");

        assertDirOrder(sources, base, common, corp, home);

        assertProperty(sources, "jetty.http.host", "127.0.0.1");
        assertProperty(sources, "jetty.http.port", "7070"); // from <command-line>
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
            "--include-jetty-dir=" + corp);

        // Create base
        Path base = testdir.getPathFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base, "start.ini",
            "jetty.http.host=127.0.0.1",
            "--include-jetty-dir=" + common);

        ConfigSources sources = new ConfigSources();

        UsageException e = assertThrows(UsageException.class, () ->
        {
            String[] cmdLine = new String[0];
            sources.add(new CommandLineConfigSource(cmdLine));
            sources.add(new JettyHomeConfigSource(home));
            sources.add(new JettyBaseConfigSource(base));
        });
        assertThat("UsageException", e.getMessage(), containsString("Duplicate"));
    }
}

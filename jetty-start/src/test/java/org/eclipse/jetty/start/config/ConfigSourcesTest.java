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

package org.eclipse.jetty.start.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.start.ConfigurationAssert;
import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.TestEnv;
import org.eclipse.jetty.start.UsageException;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ConfigSourcesTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    private void assertIdOrder(ConfigSources sources, String... expectedOrder)
    {
        List<String> actualList = new ArrayList<>();
        for (ConfigSource source : sources)
        {
            actualList.add(source.getId());
        }
        List<String> expectedList = Arrays.asList(expectedOrder);
        ConfigurationAssert.assertOrdered("ConfigSources.id order",expectedList,actualList);
    }

    private void assertDirOrder(ConfigSources sources, File... expectedDirOrder)
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
        for (File path : expectedDirOrder)
        {
            expectedList.add(path.getAbsolutePath());
        }
        ConfigurationAssert.assertOrdered("ConfigSources.dir order",expectedList,actualList);
    }

    private void assertProperty(ConfigSources sources, String key, String expectedValue)
    {
        Prop prop = sources.getProp(key);
        Assert.assertThat("getProp('" + key + "') should not be null",prop,notNullValue());
        Assert.assertThat("getProp('" + key + "')",prop.value,is(expectedValue));
    }

    @Test
    public void testOrder_BasicConfig() throws IOException
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyBaseConfigSource(base.toPath()));
        sources.add(new JettyHomeConfigSource(home.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}","${jetty.home}");
    }

    @Test
    public void testOrder_With1ExtraConfig() throws IOException
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        Path common = testdir.getFile("common").toPath();
        FS.ensureEmpty(common.toFile());
        common = common.toRealPath();

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.toString());

        ConfigSources sources = new ConfigSources();

        String[] cmdLine = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath().toRealPath()));
        sources.add(new JettyBaseConfigSource(base.toPath().toRealPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}",common.toString(),"${jetty.home}");
    }

    @Test
    public void testCommandLine_1Extra_FromSimpleProp() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        ConfigSources sources = new ConfigSources();

        // Simple command line reference to include-jetty-dir via property (also on command line)

        String[] cmdLine = new String[] {
                // property
                "my.common=" + common.getAbsolutePath(),
                // reference via property
                "--include-jetty-dir=${my.common}" };
        
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}","${my.common}","${jetty.home}");

        assertDirOrder(sources,base,common,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testCommandLine_1Extra_FromPropPrefix() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create opt
        File opt = testdir.getFile("opt");
        FS.ensureEmpty(opt);

        // Create common
        File common = new File(opt,"common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        String dirRef = "${my.opt}" + File.separator + "common";

        ConfigSources sources = new ConfigSources();

        // Simple command line reference to include-jetty-dir via property (also on command line)
        String[] cmdLine = new String[] {
                // property to 'opt' dir
                "my.opt=" + opt.getAbsolutePath(),
                // reference via property prefix
                "--include-jetty-dir=" + dirRef };
        
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}",dirRef,"${jetty.home}");

        assertDirOrder(sources,base,common,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testCommandLine_1Extra_FromCompoundProp() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create opt
        File opt = testdir.getFile("opt");
        FS.ensureEmpty(opt);

        // Create common
        File common = new File(opt,"common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1");

        String dirRef = "${my.opt}" + File.separator + "${my.dir}";

        ConfigSources sources = new ConfigSources();

        // Simple command line reference to include-jetty-dir via property (also on command line)

        String[] cmdLine = new String[] {
                // property to 'opt' dir
                "my.opt=" + opt.getAbsolutePath(),
                // property to commmon dir name
                "my.dir=common",
                // reference via property prefix
                "--include-jetty-dir=" + dirRef };
        
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}",dirRef,"${jetty.home}");

        assertDirOrder(sources,base,common,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","8080"); // from 'common'
    }
    
    @Test
    public void testRefCommon() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        ConfigSources sources = new ConfigSources();
        
        String cmdLine[] = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}",common.getAbsolutePath(),"${jetty.home}");

        assertDirOrder(sources,base,common,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","8080"); // from 'common'
    }

    @Test
    public void testRefCommonAndCorp() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini","jetty.http.port=8080");

        // Create corp
        File corp = testdir.getFile("corp");
        FS.ensureEmpty(corp);

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath(), //
                "--include-jetty-dir=" + corp.getAbsolutePath());

        ConfigSources sources = new ConfigSources();

        String cmdLine[] = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}",
                common.getAbsolutePath(),
                corp.getAbsolutePath(),
                "${jetty.home}");

        assertDirOrder(sources,base,common,corp,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","8080"); // from 'common'
    }
    
    @Test
    public void testRefCommonRefCorp() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create corp
        File corp = testdir.getFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini", //
                "jetty.http.port=9090");

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "--include-jetty-dir=" + corp.getAbsolutePath(), //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        ConfigSources sources = new ConfigSources();

        String cmdLine[] = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}",
                common.getAbsolutePath(),
                corp.getAbsolutePath(),
                "${jetty.home}");

        assertDirOrder(sources,base,common,corp,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","8080"); // from 'common'
    }
    
    @Test
    public void testRefCommonRefCorp_FromSimpleProps() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create corp
        File corp = testdir.getFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini", //
                "jetty.http.port=9090");

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "my.corp=" + corp.getAbsolutePath(), //
                "--include-jetty-dir=${my.corp}", //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "my.common="+common.getAbsolutePath(), //
                "--include-jetty-dir=${my.common}");

        ConfigSources sources = new ConfigSources();

        String cmdLine[] = new String[0];
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>",
                "${jetty.base}",
                "${my.common}",
                "${my.corp}",
                "${jetty.home}");

        assertDirOrder(sources,base,common,corp,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","8080"); // from 'common'
    }
    
    @Test
    public void testRefCommonRefCorp_CmdLineRef() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create devops
        File devops = testdir.getFile("devops");
        FS.ensureEmpty(devops);
        TestEnv.makeFile(devops,"start.ini", //
                "--module=logging", //
                "jetty.http.port=2222");

        // Create corp
        File corp = testdir.getFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini", //
                "jetty.http.port=9090");

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "--include-jetty-dir=" + corp.getAbsolutePath(), //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        ConfigSources sources = new ConfigSources();
        
        String cmdLine[] = new String[]{
                // command line provided include-jetty-dir ref
                "--include-jetty-dir=" + devops.getAbsolutePath()};
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>",
                "${jetty.base}",
                devops.getAbsolutePath(),
                common.getAbsolutePath(),
                corp.getAbsolutePath(),
                "${jetty.home}");

        assertDirOrder(sources,base,devops,common,corp,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","2222"); // from 'common'
    }
    
    @Test
    public void testRefCommonRefCorp_CmdLineProp() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create corp
        File corp = testdir.getFile("corp");
        FS.ensureEmpty(corp);
        TestEnv.makeFile(corp,"start.ini", //
                "jetty.http.port=9090");

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);
        TestEnv.makeFile(common,"start.ini", //
                "--include-jetty-dir=" + corp.getAbsolutePath(), //
                "jetty.http.port=8080");

        // Create base
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        ConfigSources sources = new ConfigSources();
        
        String cmdLine[] = new String[]{
             // command line property should override all others
                "jetty.http.port=7070"
        };
        sources.add(new CommandLineConfigSource(cmdLine));
        sources.add(new JettyHomeConfigSource(home.toPath()));
        sources.add(new JettyBaseConfigSource(base.toPath()));

        assertIdOrder(sources,"<command-line>","${jetty.base}",
                common.getAbsolutePath(),
                corp.getAbsolutePath(),
                "${jetty.home}");

        assertDirOrder(sources,base,common,corp,home);

        assertProperty(sources,"jetty.http.host","127.0.0.1");
        assertProperty(sources,"jetty.http.port","7070"); // from <command-line>
    }
    
    @Test
    public void testBadDoubleRef() throws Exception
    {
        // Create home
        File home = testdir.getFile("home");
        FS.ensureEmpty(home);
        TestEnv.copyTestDir("dist-home",home);

        // Create common
        File common = testdir.getFile("common");
        FS.ensureEmpty(common);

        // Create corp
        File corp = testdir.getFile("corp");
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
        File base = testdir.getFile("base");
        FS.ensureEmpty(base);
        TestEnv.makeFile(base,"start.ini", //
                "jetty.http.host=127.0.0.1",//
                "--include-jetty-dir=" + common.getAbsolutePath());

        ConfigSources sources = new ConfigSources();

        try
        {
            String cmdLine[] = new String[0];
            sources.add(new CommandLineConfigSource(cmdLine));
            sources.add(new JettyHomeConfigSource(home.toPath()));
            sources.add(new JettyBaseConfigSource(base.toPath()));
            
            Assert.fail("Should have thrown a UsageException");
        }
        catch (UsageException e)
        {
            Assert.assertThat("UsageException",e.getMessage(),containsString("Duplicate"));
        }
    }
}

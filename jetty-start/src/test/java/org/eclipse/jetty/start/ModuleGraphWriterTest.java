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

package org.eclipse.jetty.start;

import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ModuleGraphWriterTest
{
    @SuppressWarnings("unused")
    private final static List<String> TEST_SOURCE = Collections.singletonList("<test>");
    
    private StartArgs DEFAULT_ARGS = new StartArgs(new String[]{"jetty.version=TEST"}).parseCommandLine();

    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testGenerate_NothingEnabled() throws IOException
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("usecases/home");
        File baseDir = testdir.getEmptyDir();
        BaseHome basehome = new BaseHome(homeDir,baseDir);

        Modules modules = new Modules();
        modules.registerAll(basehome, DEFAULT_ARGS);
        modules.buildGraph();

        File outputFile = new File(baseDir,"graph.dot");

        ModuleGraphWriter writer = new ModuleGraphWriter();
        writer.write(modules,outputFile);

        Assert.assertThat("Output File Exists",outputFile.exists(),is(true));
    }
}

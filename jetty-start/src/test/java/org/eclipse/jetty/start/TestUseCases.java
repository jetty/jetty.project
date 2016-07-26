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

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Various Home + Base use cases
 */
@RunWith(Parameterized.class)
public class TestUseCases
{
    @Parameters(name = "{0}")
    public static List<Object[]> getCases() throws Exception
    {        
        File usecases = MavenTestingUtils.getTestResourceDir("usecases/");
        File[] cases=usecases.listFiles(new FilenameFilter()
        {
            @Override
            public boolean accept(File dir, String name)
            {
                return name.endsWith(".assert.txt");
            }
        });
        
        Arrays.sort(cases);
        
        List<Object[]> ret = new ArrayList<>();
        for(File assertTxt:cases)
        {
            String caseName=assertTxt.getName().replace(".assert.txt","");
            String baseName=caseName.split("\\.")[0];
            ret.add(new Object[] {caseName,baseName, assertTxt, lines(new File(usecases,caseName+".prepare.txt")),lines(new File(usecases,caseName+".cmdline.txt"))});
        }
        
        return ret;
    }

    static String[] lines(File file) throws IOException
    {
        if (!file.exists() || !file.canRead())
            return new String[0];
        return IO.readToString(file).split("[\n\r]+");
    }
    
    @Parameter(0)
    public String caseName;
    
    @Parameter(1)
    public String baseName;
    
    @Parameter(2)
    public File assertFile;

    @Parameter(3)
    public String[] prepare;
    
    @Parameter(4)
    public String[] commandLineArgs;

    @Test
    public void testUseCase() throws Exception
    {
        Path homeDir = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();
        
        Path baseSrcDir = MavenTestingUtils.getTestResourceDir("usecases/" + baseName).toPath().toRealPath();
        Path baseDir = MavenTestingUtils.getTargetTestingPath(caseName);
        if (baseDir.toFile().exists())
            org.eclipse.jetty.toolchain.test.FS.cleanDirectory(baseDir);
        else
            baseDir.toFile().mkdirs();
        org.eclipse.jetty.toolchain.test.IO.copyDir(baseSrcDir.toFile(),baseDir.toFile());
        
        
        System.setProperty("jetty.home",homeDir.toString());
        System.setProperty("jetty.base",baseDir.toString());

        try
        {
            if (prepare != null && prepare.length>0)
            {
                for (String arg : prepare)
                {
                    Main main = new Main();
                    List<String> cmdLine = new ArrayList<>();
                    cmdLine.add("--testing-mode");
                    cmdLine.addAll(Arrays.asList(arg.split(" ")));
                    main.start(main.processCommandLine(cmdLine));
                }
            }

            Main main = new Main();
            List<String> cmdLine = new ArrayList<>();
            cmdLine.add("--debug");
            if (commandLineArgs != null)
            {
                for (String arg : commandLineArgs)
                    cmdLine.add(arg);
            }

            StartArgs args = main.processCommandLine(cmdLine);
            args.getAllModules().checkEnabledModules();
            BaseHome baseHome = main.getBaseHome();
            ConfigurationAssert.assertConfiguration(baseHome,args,assertFile);
        }
        catch (Exception e)
        {
            List<String> exceptions = Arrays.asList(lines(assertFile)).stream().filter(s->s.startsWith("EX|")).collect(toList());
            if (exceptions.isEmpty())
                throw e;
            for (String ex:exceptions)
            {
                ex=ex.substring(3);
                Assert.assertThat(e.toString(),Matchers.containsString(ex));
            }
        }
    }
}

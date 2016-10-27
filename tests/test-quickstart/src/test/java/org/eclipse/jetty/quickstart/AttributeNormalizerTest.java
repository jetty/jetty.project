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

package org.eclipse.jetty.quickstart;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class AttributeNormalizerTest
{
    @Parameterized.Parameters(name = "[{index}] {0} - {1}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
    
        String arch = String.format("%s/%s", System.getProperty("os.name"), System.getProperty("os.arch"));
        
        String title;
        Map<String, String> env;
        
        // ------
        title = "Typical Setup";
        
        env = new HashMap<>();
        env.put("jetty.home", asTargetPath(title,"jetty-distro"));
        env.put("jetty.base", asTargetPath(title,"jetty-distro/demo.base"));
        env.put("WAR", asTargetPath(title,"jetty-distro/demo.base/webapps/FOO"));
        
        data.add(new Object[]{arch, title, env});
        
        // ------
        // This puts the jetty.home inside of the jetty.base
        title = "Overlap Setup";
        env = new HashMap<>();
        env.put("jetty.home", asTargetPath(title,"app/dist"));
        env.put("jetty.base", asTargetPath(title,"app"));
        env.put("WAR", asTargetPath(title,"app/webapps/FOO"));
        
        data.add(new Object[]{arch, title, env});
        
        // ------
        // This tests a path scenario often seen on various automatic deployments tooling
        // such as Kubernetes, CircleCI, TravisCI, and Jenkins.
        title = "Nasty Path Setup";
        env = new HashMap<>();
        env.put("jetty.home", asTargetPath(title,"app%2Fnasty/dist"));
        env.put("jetty.base", asTargetPath(title,"app%2Fnasty/base"));
        env.put("WAR", asTargetPath(title,"app%2Fnasty/base/webapps/FOO"));
        
        data.add(new Object[]{arch, title, env});
        return data;
    }
    
    private static final String asTargetPath(String title, String subpath)
    {
        Path rootPath = MavenTestingUtils.getTargetTestingPath(title);
        FS.ensureDirExists(rootPath);
        Path path = rootPath.resolve(OS.separators(subpath));
        FS.ensureDirExists(path);
        
        return path.toString();
    }
    
    private Map<String, String> oldValues = new HashMap<>();
    private final String jettyHome;
    private final String jettyBase;
    private final String war;
    private final String arch;
    private final String title;
    private final Map<String, String> env;
    private final AttributeNormalizer normalizer;
    
    public AttributeNormalizerTest(String arch, String title, Map<String, String> env) throws IOException
    {
        this.arch = arch;
        this.title = title;
        this.env = env;
        
        // Remember old values
        env.keySet().stream().forEach((key) ->
        {
            String old = System.getProperty(key);
            oldValues.put(key, old);
        });
        
        // Grab specific values of interest in general
        jettyHome = env.get("jetty.home");
        jettyBase = env.get("jetty.base");
        war = env.get("WAR");
        
        // Set environment (skipping null and WAR)
        env.entrySet().stream()
                .filter((e) -> e.getValue() != null && !e.getKey().equalsIgnoreCase("WAR"))
                .forEach((entry) -> System.setProperty(entry.getKey(), entry.getValue()));
        
        // Setup normalizer
        Resource webresource = Resource.newResource(war);
        this.normalizer = new AttributeNormalizer(webresource);
    }
    
    @After
    public void restoreEnv()
    {
        // Restore old values
        oldValues.entrySet().stream().forEach((entry) ->
                EnvUtils.restoreSystemProperty(entry.getKey(), entry.getValue())
        );
    }
    
    private void assertNormalize(Object o, String expected)
    {
        String result = normalizer.normalize(o);
        assertThat("normalize((" + o.getClass().getSimpleName() + ") " + o.toString() + ")",
                result, is(expected));
    }
    
    private void assertExpandPath(String line, String expected)
    {
        String result = normalizer.expand(line);
        
        // Treat output as strings
        assertThat("expand('" + line + "')", result, is(expected));
    }
    
    private void assertExpandURI(String line, URI expected)
    {
        String result = normalizer.expand(line);
        
        URI resultURI = URI.create(result);
        assertThat("expand('" + line + "')", resultURI.getScheme(), is(expected.getScheme()));
        assertThat("expand('" + line + "')", resultURI.getPath(), is(expected.getPath()));
    }
    
    @Test
    public void testNormalizeWarAsString()
    {
        // Normalize WAR as String path
        assertNormalize(war, war); // only URL, File, URI are supported
    }
    
    @Test
    public void testNormalizeJettyBaseAsFile()
    {
        // Normalize jetty.base as File path
        assertNormalize(new File(jettyBase), "${jetty.base}");
    }
    
    @Test
    public void testNormalizeJettyHomeAsFile()
    {
        // Normalize jetty.home as File path
        assertNormalize(new File(jettyHome), "${jetty.home}");
    }
    
    @Test
    public void testNormalizeJettyBaseAsURI()
    {
        // Normalize jetty.base as URI path
        assertNormalize(new File(jettyBase).toURI(), "${jetty.base.uri}");
    }
    
    @Test
    public void testNormalizeJettyHomeAsURI()
    {
        // Normalize jetty.home as URI path
        assertNormalize(new File(jettyHome).toURI(), "${jetty.home.uri}");
    }
    
    @Test
    public void testExpandJettyBase()
    {
        // Expand jetty.base
        assertExpandPath("${jetty.base}", jettyBase);
    }
    
    @Test
    public void testExpandJettyHome()
    {
        // Expand jetty.home
        assertExpandPath("${jetty.home}", jettyHome);
    }
    
    @Test
    public void testNormalizeWarAsURI()
    {
        // Normalize WAR as URI
        URI testWarURI = new File(war).toURI();
        assertNormalize(testWarURI, "${WAR.uri}");
    }
    
    @Test
    public void testNormalizeWarDeepAsFile()
    {
        // Normalize WAR deep path as File
        File testWarDeep = new File(new File(war), OS.separators("deep/ref")).getAbsoluteFile();
        assertNormalize(testWarDeep, "${WAR.path}/deep/ref");
    }
    
    @Test
    public void testNormalizeWarDeepAsString()
    {
        // Normalize WAR deep path as String
        File testWarDeep = new File(new File(war), OS.separators("deep/ref")).getAbsoluteFile();
        assertNormalize(testWarDeep.toString(), testWarDeep.toString());
    }
    
    @Test
    public void testNormalizeWarDeepAsURI()
    {
        // Normalize WAR deep path as URI
        File testWarDeep = new File(new File(war), OS.separators("deep/ref")).getAbsoluteFile();
        assertNormalize(testWarDeep.toURI(), "${WAR.uri}/deep/ref");
    }
    
    @Test
    public void testExpandWarDeep()
    {
        // Expand WAR deep path
        File testWarDeep = new File(new File(war), OS.separators("deep/ref"));
        URI uri = URI.create("jar:" + testWarDeep.toURI().toASCIIString() + "!/other/file");
        assertExpandURI("jar:${WAR.uri}/deep/ref!/other/file", uri);
    }
}


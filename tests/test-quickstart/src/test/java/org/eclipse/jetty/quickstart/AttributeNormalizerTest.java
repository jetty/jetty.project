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

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Test;

public class AttributeNormalizerTest
{
    @Test
    public void testNormalizeOrder() throws IOException
    {
        String oldJettyHome = System.getProperty("jetty.home");
        String oldJettyBase = System.getProperty("jetty.base");
        
        try
        {
            String testJettyHome = EnvUtils.toSystemPath("/opt/jetty-distro");
            String testJettyBase = EnvUtils.toSystemPath("/opt/jetty-distro/demo.base");
            String testWar = EnvUtils.toSystemPath("/opt/jetty-distro/demo.base/webapps/FOO");
            
            System.setProperty("jetty.home", testJettyHome);
            System.setProperty("jetty.base", testJettyBase);
            
            Resource webresource = Resource.newResource(testWar);
            AttributeNormalizer normalizer = new AttributeNormalizer(webresource);
            String result = null;
            
            // Normalize as String path
            result = normalizer.normalize(testWar);
            assertThat(result, is(testWar)); // only URL, File, URI are supported
            
            URI testWarURI = new File(testWar).toURI();
            
            // Normalize as URI
            result = normalizer.normalize(testWarURI);
            assertThat(result, is("${WAR}"));
            
            // Normalize deep path as File
            File testWarDeep = new File(new File(testWar), "deep/ref").getAbsoluteFile();
            result = normalizer.normalize(testWarDeep);
            assertThat(result, is("${WAR}/deep/ref"));
            
            // Normalize deep path as String
            result = normalizer.normalize(testWarDeep.toString());
            assertThat(result, is(testWarDeep.toString()));
            
            // Normalize deep path as URI
            result = normalizer.normalize(testWarDeep.toURI());
            assertThat(result, is("${WAR}/deep/ref"));
        }
        finally
        {
            EnvUtils.restoreSystemProperty("jetty.home", oldJettyHome);
            EnvUtils.restoreSystemProperty("jetty.base", oldJettyBase);
        }
    }
    
    @Test
    public void testNormalizeExpandWAR() throws IOException
    {
        String webref = MavenTestingUtils.getTargetDir().getAbsolutePath() + "/bogus.war";
        Resource webresource = Resource.newResource(webref);
        AttributeNormalizer normalizer = new AttributeNormalizer(webresource);
        String result = null;

        File webrefFile = new File(webref);
        URI uri = webrefFile.toURI();
        // As normal URI ref
        result = normalizer.normalize(uri);
        assertThat("normalize(" + uri + ")", result, is("${WAR}"));

        // as jar internal resource reference
        uri = URI.create("jar:" + webrefFile.toURI().toASCIIString() + "!/deep/ref");
        result = normalizer.normalize(uri);
        assertThat("normalize(" + uri + ")", result, is("jar:${WAR}!/deep/ref"));
        
        // as jar internal resource reference
        String line = "jar:${WAR}!/other/file";
        result = normalizer.expand(line);
        uri = URI.create("jar:" + webrefFile.toPath().toUri().toASCIIString() + "!/other/file");
        assertThat("expand(\"" + line + "\")", URI.create(result), is(uri));
    }
    
    @Test
    public void testWindowsTLD() throws MalformedURLException, IOException
    {
        // Setup AttributeNormalizer
        String webref = "http://localhost/resource/webapps/root";
        Resource webresource = Resource.newResource(webref);
        AttributeNormalizer normalizer = new AttributeNormalizer(webresource);
        
        // Setup example from windows
        String javaUserHome = System.getProperty("user.home");
        String realUserHome = EnvUtils.toSystemPath(javaUserHome);
        String userHome = AttributeNormalizer.uriSeparators(realUserHome);
        String path = "jar:file:" + userHome + "/.m2/repository/something/somejar.jar!/META-INF/some.tld";
        
        String result = normalizer.normalize(path);
        assertThat(result, is("jar:file:${user.home}/.m2/repository/something/somejar.jar!/META-INF/some.tld"));
        
        String expanded = normalizer.expand(result);
        assertThat(expanded, not(anyOf(containsString("\\"), containsString("${"))));
    }
}

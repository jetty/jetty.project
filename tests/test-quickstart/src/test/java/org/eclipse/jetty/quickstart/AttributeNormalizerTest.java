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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;

import org.eclipse.jetty.util.resource.Resource;
import org.junit.Test;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

public class AttributeNormalizerTest
{
    @Test
    public void testNormalizeWAR() throws MalformedURLException, IOException
    {
        String webref = "http://localhost/resource/webapps/root";
        Resource webresource = Resource.newResource(webref);
        AttributeNormalizer normalizer = new AttributeNormalizer(webresource);
        String result = null;

        result = normalizer.normalize(URI.create(webref));
        assertThat(result, is("${WAR}"));

        result = normalizer.normalize(URI.create(webref + "/deep/ref"));
        assertThat(result, is("${WAR}/deep/ref"));
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
        String realUserHome = AttributeNormalizerPathTest.toSystemPath(javaUserHome);
        String userHome = AttributeNormalizer.uriSeparators(realUserHome);
        String path = "jar:file:" + userHome + "/.m2/repository/something/somejar.jar!/META-INF/some.tld";

        String result = normalizer.normalize(path);
        assertThat(result, is("jar:file:${user.home}/.m2/repository/something/somejar.jar!/META-INF/some.tld"));

        String expanded = normalizer.expand(result);
        assertThat(expanded, not(anyOf(containsString("\\"), containsString("${"))));
    }
}

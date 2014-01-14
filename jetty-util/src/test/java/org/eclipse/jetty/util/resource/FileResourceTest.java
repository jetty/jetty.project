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

package org.eclipse.jetty.util.resource;

import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class FileResourceTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    private URI createDummyFile(String name) throws IOException
    {
        File file = testdir.getFile(name);
        file.createNewFile();
        return file.toURI();
    }
    
    private URL decode(URL url) throws MalformedURLException
    {
        String raw = url.toExternalForm();
        String decoded = UrlEncoded.decodeString(raw,0,raw.length(), StandardCharsets.UTF_8);
        return new URL(decoded);
    }
    
    @Test
    public void testSemicolon() throws Exception
    {
        assumeTrue(!OS.IS_WINDOWS);
        createDummyFile("foo;");

        try(Resource base = new FileResource(testdir.getDir());)
        {
            Resource res = base.addPath("foo;");
            Assert.assertNull(res.getAlias());
        }
    }

    @Test
    public void testExist_Normal() throws Exception
    {
        createDummyFile("a.jsp");

        URI ref = testdir.getDir().toURI().resolve("a.jsp");
        try(FileResource fileres = new FileResource(decode(ref.toURL()));)
        {
            Assert.assertThat("FileResource: " + fileres,fileres.exists(),is(true));
        }
    }

    @Ignore("Cannot get null to be seen by FileResource")
    @Test
    public void testExist_BadNull() throws Exception
    {
        createDummyFile("a.jsp");

        try 
        {
            // request with null at end
            URI ref = testdir.getDir().toURI().resolve("a.jsp%00");
            try(FileResource fileres = new FileResource(decode(ref.toURL()));)
            {
                Assert.assertThat("FileResource: " + fileres,fileres.exists(),is(false));
            }
        } 
        catch(URISyntaxException e) 
        {
            // Valid path
        }
    }

    @Ignore("Validation shouldn't be done in FileResource")
    @Test
    public void testExist_BadNullX() throws Exception
    {
        createDummyFile("a.jsp");

        try 
        {
            // request with null and x at end
            URI ref = testdir.getDir().toURI().resolve("a.jsp%00x");
            try(FileResource fileres = new FileResource(decode(ref.toURL()));)
            {
                Assert.assertThat("FileResource: " + fileres,fileres.exists(),is(false));
            }
        } 
        catch(URISyntaxException e) 
        {
            // Valid path
        }
    }
}

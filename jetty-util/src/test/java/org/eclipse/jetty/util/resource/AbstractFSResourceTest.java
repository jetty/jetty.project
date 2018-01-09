//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CollectionAssert;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNoException;

public abstract class AbstractFSResourceTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    public abstract Resource newResource(URI uri) throws IOException;

    public abstract Resource newResource(File file) throws IOException;

    private URI createEmptyFile(String name) throws IOException
    {
        File file = testdir.getFile(name);
        file.createNewFile();
        return file.toURI();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNonAbsoluteURI() throws Exception
    {
        newResource(new URI("path/to/resource"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNotFileURI() throws Exception
    {
        newResource(new URI("http://www.eclipse.org/jetty/"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBogusFilename() throws Exception
    {
        if (OS.IS_UNIX)
        {
            // A windows path is invalid under unix
            newResource(new URI("file://Z:/:"));
        }
        else if (OS.IS_WINDOWS)
        {
            // "CON" is a reserved name under windows
            newResource(new URI("file://CON"));
        }
        else
        {
            assumeFalse("Unknown OS type",false);
        }   
    }

    @Test
    public void testIsContainedIn() throws Exception
    {
        createEmptyFile("foo");

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo");
            assertThat("is contained in",res.isContainedIn(base),is(false));
        }
    }

    @Test
    public void testAddPath() throws Exception
    {
        File dir = testdir.getDir();
        File subdir = new File(dir,"sub");
        FS.ensureDirExists(subdir);

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource sub = base.addPath("sub");
            assertThat("sub/.isDirectory",sub.isDirectory(),is(true));
            
            Resource tmp = sub.addPath("/tmp");
            assertThat("No root",tmp.exists(),is(false));
        }
    }
    
    @Test
    public void testIsDirectory() throws Exception
    {
        File dir = testdir.getDir();
        createEmptyFile("foo");

        File subdir = new File(dir,"sub");
        FS.ensureDirExists(subdir);

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo");
            assertThat("foo.isDirectory",res.isDirectory(),is(false));

            Resource sub = base.addPath("sub");
            assertThat("sub/.isDirectory",sub.isDirectory(),is(true));
        }
    }

    @Test
    public void testLastModified() throws Exception
    {
        File file = testdir.getFile("foo");
        file.createNewFile();

        long expected = file.lastModified();

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo");
            assertThat("foo.lastModified",res.lastModified(),is(expected));
        }
    }

    @Test
    public void testLastModified_NotExists() throws Exception
    {
        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo");
            assertThat("foo.lastModified",res.lastModified(),is(0L));
        }
    }

    @Test
    public void testLength() throws Exception
    {
        File file = testdir.getFile("foo");
        file.createNewFile();

        try (StringReader reader = new StringReader("foo"); FileWriter writer = new FileWriter(file))
        {
            IO.copy(reader,writer);
        }

        long expected = file.length();

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo");
            assertThat("foo.length",res.length(),is(expected));
        }
    }

    @Test
    public void testLength_NotExists() throws Exception
    {
        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo");
            assertThat("foo.length",res.length(),is(0L));
        }
    }

    @Test
    public void testDelete() throws Exception
    {
        File file = testdir.getFile("foo");
        file.createNewFile();

        try (Resource base = newResource(testdir.getDir()))
        {
            // Is it there?
            Resource res = base.addPath("foo");
            assertThat("foo.exists",res.exists(),is(true));
            // delete it
            assertThat("foo.delete",res.delete(),is(true));
            // is it there?
            assertThat("foo.exists",res.exists(),is(false));
        }
    }

    @Test
    public void testDelete_NotExists() throws Exception
    {
        try (Resource base = newResource(testdir.getDir()))
        {
            // Is it there?
            Resource res = base.addPath("foo");
            assertThat("foo.exists",res.exists(),is(false));
            // delete it
            assertThat("foo.delete",res.delete(),is(false));
            // is it there?
            assertThat("foo.exists",res.exists(),is(false));
        }
    }

    @Test
    public void testName() throws Exception
    {
        String expected = testdir.getDir().getAbsolutePath();

        try (Resource base = newResource(testdir.getDir()))
        {
            assertThat("base.name",base.getName(),is(expected));
        }
    }

    @Test
    public void testInputStream() throws Exception
    {
        File file = testdir.getFile("foo");
        file.createNewFile();

        String content = "Foo is here";

        try (StringReader reader = new StringReader(content); FileWriter writer = new FileWriter(file))
        {
            IO.copy(reader,writer);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource foo = base.addPath("foo");
            try (InputStream stream = foo.getInputStream(); InputStreamReader reader = new InputStreamReader(stream); StringWriter writer = new StringWriter())
            {
                IO.copy(reader,writer);
                assertThat("Stream",writer.toString(),is(content));
            }
        }
    }

    @Test
    public void testReadableByteChannel() throws Exception
    {
        File file = testdir.getFile("foo");
        file.createNewFile();

        String content = "Foo is here";

        try (StringReader reader = new StringReader(content); FileWriter writer = new FileWriter(file))
        {
            IO.copy(reader,writer);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource foo = base.addPath("foo");
            try (ReadableByteChannel channel = foo.getReadableByteChannel())
            {
                ByteBuffer buf = ByteBuffer.allocate(256);
                channel.read(buf);
                buf.flip();
                String actual = BufferUtil.toUTF8String(buf);
                assertThat("ReadableByteChannel content",actual,is(content));
            }
        }
    }
    
    @Test
    public void testGetURI() throws Exception
    {
        File file = testdir.getFile("foo");
        file.createNewFile();

        URI expected = file.toURI();

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource foo = base.addPath("foo");
            assertThat("getURI",foo.getURI(),is(expected));
        }
    }

    @Test
    public void testGetURL() throws Exception
    {
        File file = testdir.getFile("foo");
        file.createNewFile();

        URL expected = file.toURI().toURL();

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource foo = base.addPath("foo");
            assertThat("getURL",foo.getURL(),is(expected));
        }
    }
    
    @Test
    public void testList() throws Exception
    {
        File dir = testdir.getDir();
        FS.touch(new File(dir, "foo"));
        FS.touch(new File(dir, "bar"));
        FS.ensureDirExists(new File(dir, "tick"));
        FS.ensureDirExists(new File(dir, "tock"));
        
        List<String> expected = new ArrayList<>();
        expected.add("foo");
        expected.add("bar");
        expected.add("tick/");
        expected.add("tock/");

        try (Resource base = newResource(testdir.getDir()))
        {
            String list[] = base.list();
            List<String> actual = Arrays.asList(list);
            
            CollectionAssert.assertContainsUnordered("Resource Directory Listing",
                    expected,actual);
        }
    }
    
    @Test
    public void testSymlink() throws Exception
    {
        File dir = testdir.getDir();
        
        Path foo = new File(dir, "foo").toPath();
        Path bar = new File(dir, "bar").toPath();
        
        try
        {
            Files.createFile(foo);
            Files.createSymbolicLink(bar,foo);
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // if unable to create symlink, no point testing the rest
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }
        
        try (Resource base = newResource(testdir.getDir()))
        {
            Resource resFoo = base.addPath("foo");
            Resource resBar = base.addPath("bar");
            
            // Access to the same resource, but via a symlink means that they are not equivalent
            assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));
            
            assertThat("foo.alias", resFoo.getAlias(), nullValue());
            assertThat("bar.alias", resBar.getAlias(), is(foo.toUri()));
        }
    }
    
    @Test
    public void testSemicolon() throws Exception
    {
        File dir = testdir.getDir();
        
        try
        {
            // attempt to create file
            Path foo = new File(dir, "foo;").toPath();
            Files.createFile(foo);
        }
        catch (Exception e)
        {
            // if unable to create file, no point testing the rest
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo;");
            assertThat("Alias: " + res,res.getAlias(),nullValue());
        }
    }

    @Test
    public void testExist_Normal() throws Exception
    {
        createEmptyFile("a.jsp");

        URI ref = testdir.getDir().toURI().resolve("a.jsp");
        try (Resource fileres = newResource(ref))
        {
            assertThat("Resource: " + fileres,fileres.exists(),is(true));
        }
    }

    @Test
    public void testSingleQuoteInFileName() throws Exception
    {
        createEmptyFile("foo's.txt");
        createEmptyFile("f o's.txt");

        URI refQuoted = testdir.getDir().toURI().resolve("foo's.txt");

        try (Resource fileres = newResource(refQuoted))
        {
            assertThat("Exists: " + refQuoted,fileres.exists(),is(true));
            assertThat("Alias: " + refQuoted,fileres.getAlias(),nullValue());
        }

        URI refEncoded = testdir.getDir().toURI().resolve("foo%27s.txt");

        try (Resource fileres = newResource(refEncoded))
        {
            assertThat("Exists: " + refEncoded,fileres.exists(),is(true));
            assertThat("Alias: " + refEncoded,fileres.getAlias(),nullValue());
        }

        URI refQuoteSpace = testdir.getDir().toURI().resolve("f%20o's.txt");

        try (Resource fileres = newResource(refQuoteSpace))
        {
            assertThat("Exists: " + refQuoteSpace,fileres.exists(),is(true));
            assertThat("Alias: " + refQuoteSpace,fileres.getAlias(),nullValue());
        }

        URI refEncodedSpace = testdir.getDir().toURI().resolve("f%20o%27s.txt");

        try (Resource fileres = newResource(refEncodedSpace))
        {
            assertThat("Exists: " + refEncodedSpace,fileres.exists(),is(true));
            assertThat("Alias: " + refEncodedSpace,fileres.getAlias(),nullValue());
        }

        URI refA = testdir.getDir().toURI().resolve("foo's.txt");
        URI refB = testdir.getDir().toURI().resolve("foo%27s.txt");

        StringBuilder msg = new StringBuilder();
        msg.append("URI[a].equals(URI[b])").append(System.lineSeparator());
        msg.append("URI[a] = ").append(refA).append(System.lineSeparator());
        msg.append("URI[b] = ").append(refB);

        // show that simple URI.equals() doesn't work
        assertThat(msg.toString(),refA.equals(refB),is(false));

        // now show that Resource.equals() does work
        try (Resource a = newResource(refA); Resource b = newResource(refB);)
        {
            assertThat("A.equals(B)",a.equals(b),is(true));
        }
    }

    @Test
    public void testExist_BadNull() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // request with null at end
            URI ref = testdir.getDir().toURI().resolve("a.jsp%00");
            assertThat("Null URI",ref,notNullValue());

            newResource(ref);
            fail("Should have thrown " + InvalidPathException.class);
        }
        catch (InvalidPathException e)
        {
            // Expected path
        }
    }

    @Test
    public void testExist_BadNullX() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // request with null and x at end
            URI ref = testdir.getDir().toURI().resolve("a.jsp%00x");
            assertThat("NullX URI",ref,notNullValue());

            newResource(ref);
            fail("Should have thrown " + InvalidPathException.class);
        }
        catch (InvalidPathException e)
        {
            // Expected path
        }
    }
    
    @Test
    public void testExist_BadControlChars_Encoded() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // request with control characters
            URI ref = testdir.getDir().toURI().resolve("a.jsp%1F%10");
            assertThat("ControlCharacters URI",ref,notNullValue());

            Resource fileref = newResource(ref);
            assertThat("File Resource should not exists", fileref.exists(), is(false));
        }
        catch (InvalidPathException e)
        {
            // Expected path
        }
    }

    @Test
    public void testExist_BadControlChars_Decoded() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // request with control characters
            File badFile = new File(testdir.getDir(), "a.jsp\014\010");
            newResource(badFile);
            fail("Should have thrown " + InvalidPathException.class);
        }
        catch (InvalidPathException e)
        {
            // Expected path
        }
    }

    @Test
    public void testExist_AddPath_BadControlChars_Decoded() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // base resource
            URI ref = testdir.getDir().toURI();
            Resource base = newResource(ref);
            assertThat("Base Resource URI",ref,notNullValue());

            // add path with control characters (raw/decoded control characters)
            // This MUST fail
            base.addPath("/a.jsp\014\010");
            fail("Should have thrown " + InvalidPathException.class);
        }
        catch (InvalidPathException e)
        {
            // Expected path
        }
    }

    @Test
    public void testExist_AddPath_BadControlChars_Encoded() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // base resource
            URI ref = testdir.getDir().toURI();
            Resource base = newResource(ref);
            assertThat("Base Resource URI",ref,notNullValue());

            // add path with control characters
            Resource fileref = base.addPath("/a.jsp%14%10");
            assertThat("File Resource should not exists", fileref.exists(), is(false));
        }
        catch (InvalidPathException e)
        {
            // Expected path
        }
    }

    @Test
    public void testUtf8Dir() throws Exception
    {
        File dir=new File(testdir.getDir(),"bãm");
        dir.mkdir();
        File file = new File(dir,"file.txt");
        file.createNewFile();
        
        Resource base = newResource(dir);
        Assert.assertNull(base.getAlias());
        
        Resource r = base.addPath("file.txt");
        Assert.assertNull(r.getAlias());
        
    }
}

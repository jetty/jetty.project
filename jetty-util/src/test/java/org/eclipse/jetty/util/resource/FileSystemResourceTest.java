//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeThat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
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
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CollectionAssert;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class FileSystemResourceTest
{
    @Rule
    public TestingDir testdir = new TestingDir();
    
    private final Class<? extends Resource> _class;

    @SuppressWarnings("deprecation")
    @Parameters(name="{0}")
    public static Collection<Object[]> data() 
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Class<?>[]{FileResource.class});
        data.add(new Class<?>[]{PathResource.class});
        return data;
    }
    
    public FileSystemResourceTest(Class<? extends Resource> test)
    {
        _class=test;
    }
    
    public Resource newResource(URI uri) throws Exception
    {
        try
        {
            return _class.getConstructor(URI.class).newInstance(uri);
        }
        catch(InvocationTargetException e)
        {
            try
            {
                throw e.getTargetException();
            }
            catch(Exception|Error ex)
            {
                throw ex;
            }
            catch(Throwable th)
            {
                throw new Error(th);
            }
        }
    }

    public Resource newResource(File file) throws Exception
    {
        try
        {
            return _class.getConstructor(File.class).newInstance(file); 
        }
        catch(InvocationTargetException e)
        {
            try
            {
                throw e.getTargetException();
            }
            catch(Exception|Error ex)
            {
                throw ex;
            }
            catch(Throwable th)
            {
                throw new Error(th);
            }
        }
    }
    
    private Matcher<Resource> hasNoAlias()
    {
        return new BaseMatcher<Resource>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Resource res = (Resource)item;
                return !res.isAlias();
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("getAlias should return null");
            }
            
            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("was ").appendValue(((Resource)item).getAlias());
            }
        };
    }
    
    private Matcher<Resource> isAliasFor(final Resource resource)
    {
        return new BaseMatcher<Resource>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Resource ritem = (Resource)item;
                final URI alias = ritem.getAlias();
                if (alias == null)
                {
                    return ritem == null;
                }
                else
                {
                    return alias.equals(resource.getURI());
                }
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("getAlias should return ").appendValue(resource.getURI());
            }
            
            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("was ").appendValue(((Resource)item).getAlias());
            }
        };
    }

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
            assertThat("foo.lastModified",res.lastModified()/1000*1000,is(expected));
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
            
            assertThat("resFoo.uri", resFoo.getURI(), is(foo.toUri()));
            
            // Access to the same resource, but via a symlink means that they are not equivalent
            assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));
            
            assertThat("resource.alias", resFoo, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resFoo.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resFoo.getFile()), hasNoAlias());
            
            assertThat("alias", resBar, isAliasFor(resFoo));
            assertThat("uri.alias", newResource(resBar.getURI()), isAliasFor(resFoo));
            assertThat("file.alias", newResource(resBar.getFile()), isAliasFor(resFoo));
        }
    }

    @Test
    public void testNonExistantSymlink() throws Exception
    {
        File dir = testdir.getDir();
        
        Path foo = new File(dir, "foo").toPath();
        Path bar = new File(dir, "bar").toPath();
        
        try
        {
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
            // FileResource does not pass this test!
            assumeFalse(base instanceof FileResource);
            
            Resource resFoo = base.addPath("foo");
            Resource resBar = base.addPath("bar");
            
            assertThat("resFoo.uri", resFoo.getURI(), is(foo.toUri()));
            
            // Access to the same resource, but via a symlink means that they are not equivalent
            assertThat("foo.equals(bar)", resFoo.equals(resBar), is(false));
            
            assertThat("resource.alias", resFoo, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resFoo.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resFoo.getFile()), hasNoAlias());
            
            assertThat("alias", resBar, isAliasFor(resFoo));
            assertThat("uri.alias", newResource(resBar.getURI()), isAliasFor(resFoo));
            assertThat("file.alias", newResource(resBar.getFile()), isAliasFor(resFoo));
        }
    }
    

    @Test
    public void testCaseInsensitiveAlias() throws Exception
    {
        File dir = testdir.getDir();
        Path path = new File(dir, "file").toPath();
        Files.createFile(path);
        
        try (Resource base = newResource(testdir.getDir()))
        {
            // Reference to actual resource that exists
            Resource resource = base.addPath("file");
                        
            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resource.getFile()), hasNoAlias());

            // On some case insensitive file systems, lets see if an alternate
            // case for the filename results in an alias reference
            Resource alias = base.addPath("FILE");
            if (alias.exists())
            {
                // If it exists, it must be an alias
                assertThat("alias", alias, isAliasFor(resource));
                assertThat("alias.uri", newResource(alias.getURI()), isAliasFor(resource));
                assertThat("alias.file", newResource(alias.getFile()), isAliasFor(resource));
            }
        }
    }

    /**
     * Test for Windows feature that exposes 8.3 filename references
     * for long filenames.
     * <p>
     * See: http://support.microsoft.com/kb/142982
     * @throws Exception failed test
     */
    @Test
    public void testCase8dot3Alias() throws Exception
    {
        File dir = testdir.getDir();
        Path path = new File(dir, "TextFile.Long.txt").toPath();
        Files.createFile(path);
        
        try (Resource base = newResource(testdir.getDir()))
        {
            // Long filename
            Resource resource = base.addPath("TextFile.Long.txt");
                        
            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resource.getFile()), hasNoAlias());

            // On some versions of Windows, the long filename can be referenced
            // via a short 8.3 equivalent filename.
            Resource alias = base.addPath("TEXTFI~1.TXT");
            if (alias.exists())
            {
                // If it exists, it must be an alias
                assertThat("alias", alias, isAliasFor(resource));
                assertThat("alias.uri", newResource(alias.getURI()), isAliasFor(resource));
                assertThat("alias.file", newResource(alias.getFile()), isAliasFor(resource));
            }
        }
    }

    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     * @throws Exception failed test
     */
    @Test
    public void testNTFSFileStreamAlias() throws Exception
    {
        File dir = testdir.getDir();
        Path path = new File(dir, "testfile").toPath();
        Files.createFile(path);
        
        try (Resource base = newResource(testdir.getDir()))
        {
            Resource resource = base.addPath("testfile");
                        
            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resource.getFile()), hasNoAlias());

            try
            {
                // Attempt to reference same file, but via NTFS simple stream
                Resource alias = base.addPath("testfile:stream");
                if (alias.exists())
                {
                    // If it exists, it must be an alias
                    assertThat("resource.alias",alias,isAliasFor(resource));
                    assertThat("resource.uri.alias",newResource(alias.getURI()),isAliasFor(resource));
                    assertThat("resource.file.alias",newResource(alias.getFile()),isAliasFor(resource));
                }
            }
            catch (InvalidPathException e)
            {
                // NTFS filesystem streams are unsupported on some platforms.
                assumeNoException(e);
            }
        }
    }
    
    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     * @throws Exception failed test
     */
    @Test
    public void testNTFSFileDataStreamAlias() throws Exception
    {
        File dir = testdir.getDir();
        Path path = new File(dir, "testfile").toPath();
        Files.createFile(path);
        
        try (Resource base = newResource(testdir.getDir()))
        {
            Resource resource = base.addPath("testfile");
                        
            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resource.getFile()), hasNoAlias());

            try
            {
                // Attempt to reference same file, but via NTFS DATA stream
                Resource alias = base.addPath("testfile::$DATA");
                if (alias.exists())
                {
                    assumeThat(alias.getURI().getScheme(), is("http"));
                    
                    // If it exists, it must be an alias
                    assertThat("resource.alias",alias,isAliasFor(resource));
                    assertThat("resource.uri.alias",newResource(alias.getURI()),isAliasFor(resource));
                    assertThat("resource.file.alias",newResource(alias.getFile()),isAliasFor(resource));
                }
            }
            catch (InvalidPathException e)
            {
                // NTFS filesystem streams are unsupported on some platforms.
                assumeNoException(e);
            }
        }
    }
    
    /**
     * NTFS Alternative Data / File Streams.
     * <p>
     * See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa364404(v=vs.85).aspx
     * @throws Exception failed test
     */
    @Test
    public void testNTFSFileEncodedDataStreamAlias() throws Exception
    {
        File dir = testdir.getDir();
        Path path = new File(dir, "testfile").toPath();
        Files.createFile(path);
        
        try (Resource base = newResource(testdir.getDir()))
        {
            Resource resource = base.addPath("testfile");
                        
            assertThat("resource.alias", resource, hasNoAlias());
            assertThat("resource.uri.alias", newResource(resource.getURI()), hasNoAlias());
            assertThat("resource.file.alias", newResource(resource.getFile()), hasNoAlias());

            try
            {
                // Attempt to reference same file, but via NTFS DATA stream (encoded addPath version) 
                Resource alias = base.addPath("testfile::%24DATA");
                if (alias.exists())
                {
                    // If it exists, it must be an alias
                    assertThat("resource.alias",alias,isAliasFor(resource));
                    assertThat("resource.uri.alias",newResource(alias.getURI()),isAliasFor(resource));
                    assertThat("resource.file.alias",newResource(alias.getFile()),isAliasFor(resource));
                }
            }
            catch (InvalidPathException e)
            {
                // NTFS filesystem streams are unsupported on some platforms.
                assumeNoException(e);
            }
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
            // if unable to create file, no point testing the rest.
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
    public void testSingleQuote() throws Exception
    {
        File dir = testdir.getDir();
        
        try
        {
            // attempt to create file
            Path foo = new File(dir, "foo' bar").toPath();
            Files.createFile(foo);
        }
        catch (Exception e)
        {
            // if unable to create file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo' bar");
            assertThat("Alias: " + res,res.getAlias(),nullValue());
        }
    }
    
    @Test
    public void testSingleBackTick() throws Exception
    {
        File dir = testdir.getDir();
        
        try
        {
            // attempt to create file
            Path foo = new File(dir, "foo` bar").toPath();
            Files.createFile(foo);
        }
        catch (Exception e)
        {
            // if unable to create file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            // FileResource does not pass this test!
            assumeFalse(base instanceof FileResource);

            Resource res = base.addPath("foo` bar");
            assertThat("Alias: " + res,res.getAlias(),nullValue());
        }
    }
    
    @Test
    public void testBrackets() throws Exception
    {
        File dir = testdir.getDir();
        
        try
        {
            // attempt to create file
            Path foo = new File(dir, "foo[1]").toPath();
            Files.createFile(foo);
        }
        catch (Exception e)
        {
            // if unable to create file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            Resource res = base.addPath("foo[1]");
            assertThat("Alias: " + res,res.getAlias(),nullValue());
        }
    }
    
    @Test
    public void testBraces() throws Exception
    {
        File dir = testdir.getDir();
        
        try
        {
            // attempt to create file
            Path foo = new File(dir, "foo.{bar}.txt").toPath();
            Files.createFile(foo);
        }
        catch (Exception e)
        {
            // if unable to create file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            // FileResource does not pass this test!
            assumeFalse(base instanceof FileResource);

            Resource res = base.addPath("foo.{bar}.txt");
            assertThat("Alias: " + res,res.getAlias(),nullValue());
        }
    }
    
    @Test
    public void testCaret() throws Exception
    {
        File dir = testdir.getDir();
        
        try
        {
            // attempt to create file
            Path foo = new File(dir, "foo^3.txt").toPath();
            Files.createFile(foo);
        }
        catch (Exception e)
        {
            // if unable to create file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            // FileResource does not pass this test!
            assumeFalse(base instanceof FileResource);

            Resource res = base.addPath("foo^3.txt");
            assertThat("Alias: " + res,res.getAlias(),nullValue());
        }
    }
    
    @Test
    public void testPipe() throws Exception
    {
        File dir = testdir.getDir();
        
        try
        {
            // attempt to create file
            Path foo = new File(dir, "foo|bar.txt").toPath();
            Files.createFile(foo);
        }
        catch (Exception e)
        {
            // if unable to create file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeNoException(e);
        }

        try (Resource base = newResource(testdir.getDir()))
        {
            // FileResource does not pass this test!
            assumeFalse(base instanceof FileResource);

            Resource res = base.addPath("foo|bar.txt");
            assertThat("Alias: " + res,res.getAlias(),nullValue());
        }
    }

    /**
     * The most basic access example
     * @throws Exception failed test
     */
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
            assertThat("Alias: " + refQuoted,fileres,hasNoAlias());
        }

        URI refEncoded = testdir.getDir().toURI().resolve("foo%27s.txt");

        try (Resource fileres = newResource(refEncoded))
        {
            assertThat("Exists: " + refEncoded,fileres.exists(),is(true));
            assertThat("Alias: " + refEncoded,fileres,hasNoAlias());
        }

        URI refQuoteSpace = testdir.getDir().toURI().resolve("f%20o's.txt");

        try (Resource fileres = newResource(refQuoteSpace))
        {
            assertThat("Exists: " + refQuoteSpace,fileres.exists(),is(true));
            assertThat("Alias: " + refQuoteSpace,fileres,hasNoAlias());
        }

        URI refEncodedSpace = testdir.getDir().toURI().resolve("f%20o%27s.txt");

        try (Resource fileres = newResource(refEncodedSpace))
        {
            assertThat("Exists: " + refEncodedSpace,fileres.exists(),is(true));
            assertThat("Alias: " + refEncodedSpace,fileres,hasNoAlias());
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
    public void testExist_BadURINull() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // request with null at end
            URI uri = testdir.getDir().toURI().resolve("a.jsp%00");
            assertThat("Null URI",uri,notNullValue());

            Resource r = newResource(uri);
            
            // if we have r, then it better not exist
            assertFalse(r.exists());
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }

    @Test
    public void testExist_BadURINullX() throws Exception
    {
        createEmptyFile("a.jsp");

        try
        {
            // request with null and x at end
            URI uri = testdir.getDir().toURI().resolve("a.jsp%00x");
            assertThat("NullX URI",uri,notNullValue());

            Resource r = newResource(uri);
            
            // if we have r, then it better not exist
            assertFalse(r.exists());
        }
        catch (InvalidPathException e)
        {
            // Exception is acceptable
        }
    }
    
    @Test
    public void testEncoding() throws Exception
    {
        File specials = testdir.getFile("a file with,spe#ials");
        try(Resource res= newResource(specials))
        {
            assertThat("Specials URL", res.getURI().toASCIIString(), containsString("a%20file%20with,spe%23ials"));
            assertThat("Specials Filename", res.getFile().toString(), containsString("a file with,spe#ials"));
            
            res.delete();
            assertThat("File should have been deleted.",res.exists(),is(false));
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
        assertNull(base.getAlias());
        
        Resource r = base.addPath("file.txt");
        assertNull(r.getAlias());
    }
}

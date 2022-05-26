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

package org.eclipse.jetty.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.server.MultiPartFormInputStream.MultiPart;
import org.eclipse.jetty.server.MultiParts.NonCompliance;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class MultiPartFormInputStreamTest
{
    private static final AtomicInteger testCounter = new AtomicInteger();
    private static final String FILENAME = "stuff.txt";
    protected String _contentType = "multipart/form-data, boundary=AaB03x";
    protected String _multi = createMultipartRequestString(FILENAME);
    protected File _tmpDir = MavenTestingUtils.getTargetTestingDir(String.valueOf(testCounter.incrementAndGet()));
    protected String _dirname = _tmpDir.getAbsolutePath();

    public MultiPartFormInputStreamTest()
    {
        _tmpDir.deleteOnExit();
    }

    @Test
    public void testBadMultiPartRequest()
    {
        String boundary = "X0Y0";
        String str = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n" +
            "How now brown cow." +
            "\r\n--" + boundary + "-\r\n" +
            "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n" +
            "\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            "multipart/form-data, boundary=" + boundary,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        IOException x = assertThrows(IOException.class,
            mpis::getParts,
            "Incomplete Multipart");
        assertThat(x.getMessage(), startsWith("Incomplete"));
    }

    @Test
    public void testFinalBoundaryOnly() throws Exception
    {
        String delimiter = "\r\n";
        final String boundary = "MockMultiPartTestBoundary";

        // Malformed multipart request body containing only an arbitrary string of text, followed by the final boundary marker, delimited by empty lines.
        String str =
            delimiter +
                "Hello world" +
                delimiter +        // Two delimiter markers, which make an empty line.
                delimiter +
                "--" + boundary + "--" + delimiter;

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            "multipart/form-data, boundary=" + boundary,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        assertTrue(mpis.getParts().isEmpty());
    }

    @Test
    public void testEmpty() throws Exception
    {
        String delimiter = "\r\n";
        final String boundary = "MockMultiPartTestBoundary";

        String str =
            delimiter +
                "--" + boundary + "--" + delimiter;

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            "multipart/form-data, boundary=" + boundary,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        assertTrue(mpis.getParts().isEmpty());
    }

    @Test
    public void testNoBoundaryRequest() throws Exception
    {
        String str = "--\r\n" +
            "Content-Disposition: form-data; name=\"fileName\"\r\n" +
            "Content-Type: text/plain; charset=US-ASCII\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n" +
            "\r\n" +
            "abc\r\n" +
            "--\r\n" +
            "Content-Disposition: form-data; name=\"desc\"\r\n" +
            "Content-Type: text/plain; charset=US-ASCII\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n" +
            "\r\n" +
            "123\r\n" +
            "--\r\n" +
            "Content-Disposition: form-data; name=\"title\"\r\n" +
            "Content-Type: text/plain; charset=US-ASCII\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n" +
            "\r\n" +
            "ttt\r\n" +
            "--\r\n" +
            "Content-Disposition: form-data; name=\"datafile5239138112980980385.txt\"; filename=\"datafile5239138112980980385.txt\"\r\n" +
            "Content-Type: application/octet-stream; charset=ISO-8859-1\r\n" +
            "Content-Transfer-Encoding: binary\r\n" +
            "\r\n" +
            "000\r\n" +
            "----\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            "multipart/form-data",
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(4));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Part fileName = mpis.getPart("fileName");
        assertThat(fileName, notNullValue());
        assertThat(fileName.getSize(), is(3L));
        IO.copy(fileName.getInputStream(), baos);
        assertThat(baos.toString(StandardCharsets.US_ASCII), is("abc"));

        baos = new ByteArrayOutputStream();
        Part desc = mpis.getPart("desc");
        assertThat(desc, notNullValue());
        assertThat(desc.getSize(), is(3L));
        IO.copy(desc.getInputStream(), baos);
        assertThat(baos.toString(StandardCharsets.US_ASCII), is("123"));

        baos = new ByteArrayOutputStream();
        Part title = mpis.getPart("title");
        assertThat(title, notNullValue());
        assertThat(title.getSize(), is(3L));
        IO.copy(title.getInputStream(), baos);
        assertThat(baos.toString(StandardCharsets.US_ASCII), is("ttt"));
    }

    @Test
    public void testNonMultiPartRequest()
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        Throwable t = assertThrows(IllegalArgumentException.class, () ->
            new MultiPartFormInputStream(new ByteArrayInputStream(_multi.getBytes()),
                "Content-type: text/plain", config, _tmpDir));
        assertThat(t.getMessage(), is("content type is not multipart/form-data"));
    }

    @Test
    public void testNoBody()
    {
        String body = "";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(body.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        IOException x = assertThrows(IOException.class, mpis::getParts);
        assertThat(x.getMessage(), containsString("Missing initial multi part boundary"));
    }

    @Test
    public void testBodyAlreadyConsumed() throws Exception
    {
        ServletInputStream is = new ServletInputStream()
        {

            @Override
            public boolean isFinished()
            {
                return true;
            }

            @Override
            public boolean isReady()
            {
                return false;
            }

            @Override
            public void setReadListener(ReadListener readListener)
            {
            }

            @Override
            public int read()
            {
                return 0;
            }
        };

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(is,
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertEquals(0, parts.size());
    }

    @Test
    public void testWhitespaceBodyWithCRLF()
    {
        String whitespace = "              \n\n\n\r\n\r\n\r\n\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(whitespace.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        IOException x = assertThrows(IOException.class, mpis::getParts);
        assertThat(x.getMessage(), containsString("Missing initial multi part boundary"));
    }

    @Test
    public void testWhitespaceBody()
    {
        String whitespace = " ";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(whitespace.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        IOException x = assertThrows(IOException.class, mpis::getParts);
        assertThat(x.getMessage(), containsString("Missing initial"));
    }

    @Test
    public void testLeadingWhitespaceBodyWithCRLF() throws Exception
    {
        String body = "              \n\n\n\r\n\r\n\r\n\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"" + "foo.txt" + "\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" + "aaaa" +
            "bbbbb" + "\r\n" +
            "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(body.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        Collection<Part> parts = mpis.getParts();
        assertThat(parts, notNullValue());
        assertThat(parts.size(), is(2));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Part field1 = mpis.getPart("field1");
            assertThat(field1, notNullValue());
            IO.copy(field1.getInputStream(), baos);
            assertThat(baos.toString(StandardCharsets.US_ASCII), is("Joe Blow"));
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Part stuff = mpis.getPart("stuff");
            assertThat(stuff, notNullValue());
            IO.copy(stuff.getInputStream(), baos);
            assertThat(baos.toString(StandardCharsets.US_ASCII), containsString("aaaa"));
        }
    }

    @Test
    public void testLeadingWhitespaceBodyWithoutCRLF() throws Exception
    {
        String body = "            " +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"" + "foo.txt" + "\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" + "aaaa" +
            "bbbbb" + "\r\n" +
            "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(body.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        Collection<Part> parts = mpis.getParts();
        assertThat(parts, notNullValue());
        assertThat(parts.size(), is(1));

        Part stuff = mpis.getPart("stuff");
        assertThat(stuff, notNullValue());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IO.copy(stuff.getInputStream(), baos);
        assertThat(baos.toString(StandardCharsets.US_ASCII), containsString("bbbbb"));
    }

    @Test
    public void testNoLimits() throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(_multi.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertFalse(parts.isEmpty());
    }

    @Test
    public void testRequestTooBig()
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 60, 100, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(_multi.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        IllegalStateException x = assertThrows(IllegalStateException.class, mpis::getParts);
        assertThat(x.getMessage(), containsString("Request exceeds maxRequestSize"));
    }

    @Test
    public void testRequestTooBigThrowsErrorOnGetParts()
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 60, 100, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(_multi.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        //cause parsing
        IllegalStateException x = assertThrows(IllegalStateException.class, mpis::getParts);
        assertThat(x.getMessage(), containsString("Request exceeds maxRequestSize"));

        //try again
        x = assertThrows(IllegalStateException.class, mpis::getParts);
        assertThat(x.getMessage(), containsString("Request exceeds maxRequestSize"));
    }

    @Test
    public void testFileTooBig()
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 40, 1024, 30);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(_multi.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        IllegalStateException x = assertThrows(IllegalStateException.class,
            mpis::getParts,
            "stuff.txt should have been larger than maxFileSize");
        assertThat(x.getMessage(), startsWith("Multipart Mime part"));
    }

    @Test
    public void testFileTooBigThrowsErrorOnGetParts()
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 40, 1024, 30);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(_multi.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        // Caused parsing
        IllegalStateException x = assertThrows(IllegalStateException.class,
            mpis::getParts,
            "stuff.txt should have been larger than maxFileSize");
        assertThat(x.getMessage(), startsWith("Multipart Mime part"));

        //test again after the parsing
        x = assertThrows(IllegalStateException.class,
            mpis::getParts,
            "stuff.txt should have been larger than maxFileSize");
        assertThat(x.getMessage(), startsWith("Multipart Mime part"));
    }

    @Test
    public void testPartFileRelative() throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(createMultipartRequestString("tptfd").getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        mpis.getParts();

        MultiPart part = (MultiPart)mpis.getPart("stuff");
        File stuff = part.getFile();
        assertThat(stuff, notNullValue()); // longer than 50 bytes, should already be a tmp file
        part.write("tptfd.txt");
        File tptfd = new File(_dirname + File.separator + "tptfd.txt");
        assertThat(tptfd.exists(), is(true));
        assertThat(stuff.exists(), is(false)); //got renamed
        part.cleanUp();
        assertThat(tptfd.exists(), is(true));  //explicitly written file did not get removed after cleanup
        tptfd.deleteOnExit(); //clean up test
    }
    
    @Test
    public void testPartFileAbsolute() throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(createMultipartRequestString("tpfa").getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        mpis.getParts();

        MultiPart part = (MultiPart)mpis.getPart("stuff");
        File stuff = part.getFile();
        assertThat(stuff, notNullValue()); // longer than 50 bytes, should already be a tmp file
        Path path = MavenTestingUtils.getTargetTestingPath().resolve("tpfa.txt");
        part.write(path.toFile().getAbsolutePath());
        File tpfa = path.toFile();
        assertThat(tpfa.exists(), is(true));
        assertThat(stuff.exists(), is(false)); //got renamed
        part.cleanUp();
        assertThat(tpfa.exists(), is(true));  //explicitly written file did not get removed after cleanup
        tpfa.deleteOnExit(); //clean up test 
    }
    
    @Test
    public void testPartFileAbsoluteFromBuffer() throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 5000);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(createMultipartRequestString("tpfafb").getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        mpis.getParts();

        MultiPart part = (MultiPart)mpis.getPart("stuff");
        //Content should still be in the buffer, because the length is < 5000, 
        assertNull(part.getFile());
        //test writing to an absolute filename
        Path path = MavenTestingUtils.getTargetTestingPath().resolve("tpfafb.txt");
        part.write(path.toFile().getAbsolutePath());
        File tpfafb = path.toFile();
        assertThat(tpfafb.exists(), is(true));
        part.cleanUp();
        assertThat(tpfafb.exists(), is(true));  //explicitly written file did not get removed after cleanup
        tpfafb.deleteOnExit(); //clean up test 
    }
    
    @Test
    public void testPartFileRelativeFromBuffer() throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 5000);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(createMultipartRequestString("tpfrfb").getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        mpis.getParts();

        MultiPart part = (MultiPart)mpis.getPart("stuff");
        //Content should still be in the buffer, because the length is < 5000, 
        assertNull(part.getFile());
        //test writing to a relative filename
        part.write("tpfrfb.txt");
        File tpfrfb = new File(_tmpDir, "tpfrfb.txt");
        assertThat(tpfrfb.exists(), is(true));
        part.cleanUp();
        assertThat(tpfrfb.exists(), is(true));  //explicitly written file did not get removed after cleanup
        tpfrfb.deleteOnExit(); //clean up test 
    }

    @Test
    public void testPartTmpFileDeletion() throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(createMultipartRequestString("tptfd").getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        mpis.getParts();

        MultiPart part = (MultiPart)mpis.getPart("stuff");
        File stuff = part.getFile();
        assertThat(stuff, notNullValue()); // longer than 50 bytes, should already be a tmp file
        assertThat(stuff.exists(), is(true));
        part.cleanUp();
        assertThat(stuff.exists(), is(false));  //tmp file was removed after cleanup
    }

    @Test
    public void testDeleteNPE()
    {
        final InputStream input = new ByteArrayInputStream(createMultipartRequestString("myFile").getBytes());
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 1024, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(input, _contentType, config, _tmpDir);

        mpis.deleteParts(); // this should not be an NPE
    }

    @Test
    public void testAsyncCleanUp() throws Exception
    {
        final CountDownLatch reading = new CountDownLatch(1);
        final InputStream wrappedStream = new ByteArrayInputStream(createMultipartRequestString("myFile").getBytes());

        // This stream won't allow the parser to exit because it will never return anything less than 0.
        InputStream slowStream = new InputStream()
        {
            @Override
            public int read(byte[] b, int off, int len) throws IOException
            {
                return Math.max(0, super.read(b, off, len));
            }

            @Override
            public int read() throws IOException
            {
                reading.countDown();
                return wrappedStream.read();
            }
        };

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 1024, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(slowStream, _contentType, config, _tmpDir);

        // In another thread delete the parts when we detect that we have started parsing.
        CompletableFuture<Throwable> cleanupError = new CompletableFuture<>();
        new Thread(() ->
        {
            try
            {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                mpis.deleteParts();
                cleanupError.complete(null);
            }
            catch (Throwable t)
            {
                cleanupError.complete(t);
            }
        }).start();

        // The call to getParts should throw an error.
        Throwable error = assertThrows(IOException.class, mpis::getParts);
        assertThat(error.getMessage(), is("DELETING"));

        // There was no error with the cleanup.
        assertNull(cleanupError.get());

        // No tmp files are remaining.
        String[] fileList = _tmpDir.list();
        assertNotNull(fileList);
        assertThat(fileList.length, is(0));
    }

    @Test
    public void testParseAfterCleanUp()
    {
        final InputStream input = new ByteArrayInputStream(createMultipartRequestString("myFile").getBytes());
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 1024, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(input, _contentType, config, _tmpDir);

        mpis.deleteParts();

        // The call to getParts should throw because we have already cleaned up the parts.
        Throwable error = assertThrows(IOException.class, mpis::getParts);
        assertThat(error.getMessage(), is("DELETED"));

        // Even though we called getParts() we never even created the tmp directory as we had already called deleteParts().
        assertFalse(_tmpDir.exists());
    }

    @Test
    public void testLFOnlyRequest() throws Exception
    {
        String str = "--AaB03x\n" +
            "content-disposition: form-data; name=\"field1\"\n" +
            "\n" +
            "Joe Blow" +
            "\r\n--AaB03x\n" +
            "content-disposition: form-data; name=\"field2\"\n" +
            "\n" +
            "Other" +
            "\r\n--AaB03x--\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(2));
        Part p1 = mpis.getPart("field1");
        assertThat(p1, notNullValue());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IO.copy(p1.getInputStream(), baos);
        assertThat(baos.toString(StandardCharsets.UTF_8), is("Joe Blow"));

        Part p2 = mpis.getPart("field2");
        assertThat(p2, notNullValue());
        baos = new ByteArrayOutputStream();
        IO.copy(p2.getInputStream(), baos);
        assertThat(baos.toString(StandardCharsets.UTF_8), is("Other"));
    }

    @Test
    public void testCROnlyRequest()
    {
        String str = "--AaB03x\r" +
            "content-disposition: form-data; name=\"field1\"\r" +
            "\r" +
            "Joe Blow\r" +
            "--AaB03x\r" +
            "content-disposition: form-data; name=\"field2\"\r" +
            "\r" +
            "Other\r" +
            "--AaB03x--\r";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        IllegalStateException x = assertThrows(IllegalStateException.class,
            mpis::getParts,
            "Improper EOL");
        assertThat(x.getMessage(), containsString("Bad EOL"));
    }

    @Test
    public void testCRandLFMixRequest()
    {
        String str = "--AaB03x\r" +
            "content-disposition: form-data; name=\"field1\"\r" +
            "\r" +
            "\nJoe Blow\n" +
            "\r" +
            "--AaB03x\r" +
            "content-disposition: form-data; name=\"field2\"\r" +
            "\r" +
            "Other\r" +
            "--AaB03x--\r";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        IllegalStateException x = assertThrows(IllegalStateException.class,
            mpis::getParts,
            "Improper EOL");
        assertThat(x.getMessage(), containsString("Bad EOL"));
    }

    @Test
    public void testBufferOverflowNoCRLF() throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("--AaB03x\r\n".getBytes());
        for (int i = 0; i < 3000; i++) //create content that will overrun default buffer size of BufferedInputStream
        {
            baos.write('a');
        }

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(baos.toByteArray()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        IllegalStateException x = assertThrows(IllegalStateException.class,
            mpis::getParts,
            "Header Line Exceeded Max Length");
        assertThat(x.getMessage(), containsString("Header Line Exceeded Max Length"));
    }

    @Test
    public void testCharsetEncoding() throws Exception
    {
        String contentType = "multipart/form-data; boundary=TheBoundary; charset=ISO-8859-1";
        String str = "--TheBoundary\r\n" +
            "content-disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "\nJoe Blow\n" +
            "\r\n" +
            "--TheBoundary--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(str.getBytes()),
            contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(1));
    }

    @Test
    public void testBadlyEncodedFilename() throws Exception
    {

        String contents = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"" + "Taken on Aug 22 \\ 2012.jpg" + "\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" + "stuff" +
            "aaa" + "\r\n" +
            "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(contents.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(1));
        assertThat(parts.iterator().next().getSubmittedFileName(), is("Taken on Aug 22 \\ 2012.jpg"));
    }

    @Test
    public void testBadlyEncodedMSFilename() throws Exception
    {

        String contents = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"" + "c:\\this\\really\\is\\some\\path\\to\\a\\file.txt" + "\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" + "stuff" +
            "aaa" + "\r\n" +
            "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(contents.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(1));
        assertThat(parts.iterator().next().getSubmittedFileName(), is("c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"));
    }

    @Test
    public void testCorrectlyEncodedMSFilename() throws Exception
    {
        String contents = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"" + "c:\\\\this\\\\really\\\\is\\\\some\\\\path\\\\to\\\\a\\\\file.txt" + "\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" + "stuff" +
            "aaa" + "\r\n" +
            "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(contents.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(1));
        assertThat(parts.iterator().next().getSubmittedFileName(), is("c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"));
    }

    @Test
    public void testMultiWithSpaceInFilename() throws Exception
    {
        testMulti("stuff with spaces.txt");
    }

    @Test
    public void testWriteFilesIfContentDispositionFilename() throws Exception
    {
        String s = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"; filename=\"frooble.txt\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" + "sss" +
            "aaa" + "\r\n" +
            "--AaB03x--\r\n";
        //all default values for multipartconfig, ie file size threshold 0
        MultipartConfigElement config = new MultipartConfigElement(_dirname);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(s.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        mpis.setWriteFilesWithFilenames(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(2));
        Part field1 = mpis.getPart("field1"); //has a filename, should be written to a file
        File f = ((MultiPartFormInputStream.MultiPart)field1).getFile();
        assertThat(f, notNullValue()); // longer than 100 bytes, should already be a tmp file

        Part stuff = mpis.getPart("stuff");
        f = ((MultiPartFormInputStream.MultiPart)stuff).getFile(); //should only be in memory, no filename
        assertThat(f, nullValue());
    }

    private void testMulti(String filename) throws IOException
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(createMultipartRequestString(filename).getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(2));
        Part field1 = mpis.getPart("field1");  //field 1 too small to go into tmp file, should be in internal buffer
        assertThat(field1, notNullValue());
        assertThat(field1.getName(), is("field1"));

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (InputStream is = field1.getInputStream())
        {
            IO.copy(is, os);
        }
        assertEquals("Joe Blow", new String(os.toByteArray()));
        assertEquals(8, field1.getSize());

        assertNotNull(((MultiPartFormInputStream.MultiPart)field1).getBytes()); //in internal buffer
        field1.write("field1.txt");
        assertNull(((MultiPartFormInputStream.MultiPart)field1).getBytes()); //no longer in internal buffer
        File f = new File(_dirname + File.separator + "field1.txt");
        assertTrue(f.exists());
        field1.write("another_field1.txt"); //write after having already written
        File f2 = new File(_dirname + File.separator + "another_field1.txt");
        assertTrue(f2.exists());
        assertFalse(f.exists()); //should have been renamed
        field1.delete();  //file should be deleted
        assertFalse(f.exists()); //original file was renamed
        assertFalse(f2.exists()); //2nd written file was explicitly deleted

        MultiPart stuff = (MultiPart)mpis.getPart("stuff");
        assertThat(stuff.getSubmittedFileName(), is(filename));
        assertThat(stuff.getContentType(), is("text/plain"));
        assertThat(stuff.getHeader("Content-Type"), is("text/plain"));
        assertThat(stuff.getHeaders("content-type").size(), is(1));
        assertNotNull(stuff.getHeaders("non existing part"));
        assertThat(stuff.getHeaders("non existing part").size(), is(0));
        assertThat(stuff.getHeader("content-disposition"), is("form-data; name=\"stuff\"; filename=\"" + filename + "\""));
        assertThat(stuff.getHeaderNames().size(), is(2));
        assertThat(stuff.getSize(), is(51L));

        File tmpfile = stuff.getFile();
        assertThat(tmpfile, notNullValue()); // longer than 50 bytes, should already be a tmp file
        assertThat(stuff.getBytes(), nullValue()); //not in an internal buffer
        assertThat(tmpfile.exists(), is(true));
        assertThat(tmpfile.getName(), is(not("stuff with space.txt")));
        stuff.write(filename);
        f = new File(_dirname + File.separator + filename);
        assertThat(f.exists(), is(true));
        assertThat(tmpfile.exists(), is(false));
        try
        {
            stuff.getInputStream();
        }
        catch (Exception e)
        {
            fail("Part.getInputStream() after file rename operation", e);
        }
        f.deleteOnExit(); //clean up after test
    }

    @Test
    public void testMultiSameNames() throws Exception
    {
        String sameNames = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"stuff1.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "00000\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"stuff2.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "110000000000000000000000000000000000000000000000000\r\n" +
            "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(sameNames.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertEquals(2, parts.size());
        for (Part p : parts)
        {
            assertEquals("stuff", p.getName());
        }

        //if they all have the name name, then only retrieve the first one
        Part p = mpis.getPart("stuff");
        assertNotNull(p);
        assertEquals(5, p.getSize());
    }

    @Test
    public void testBase64EncodedContent() throws Exception
    {
        String contentWithEncodedPart =
            "--AaB03x\r\n" +
                "Content-disposition: form-data; name=\"other\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "other" + "\r\n" +
                "--AaB03x\r\n" +
                "Content-disposition: form-data; name=\"stuff\"; filename=\"stuff.txt\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n" +
                Base64.getEncoder().encodeToString("hello jetty".getBytes(ISO_8859_1)) + "\r\n" +
                "--AaB03x\r\n" +
                "Content-disposition: form-data; name=\"final\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "the end" + "\r\n" +
                "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(contentWithEncodedPart.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertEquals(3, parts.size());

        Part p1 = mpis.getPart("other");
        assertNotNull(p1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IO.copy(p1.getInputStream(), baos);
        assertEquals("other", baos.toString(StandardCharsets.US_ASCII));

        Part p2 = mpis.getPart("stuff");
        assertNotNull(p2);
        baos = new ByteArrayOutputStream();
        IO.copy(p2.getInputStream(), baos);
        assertEquals(Base64.getEncoder().encodeToString("hello jetty".getBytes(ISO_8859_1)), baos.toString(StandardCharsets.US_ASCII));

        Part p3 = mpis.getPart("final");
        assertNotNull(p3);
        baos = new ByteArrayOutputStream();
        IO.copy(p3.getInputStream(), baos);
        assertEquals("the end", baos.toString(StandardCharsets.US_ASCII));

        assertThat(mpis.getNonComplianceWarnings(), equalTo(EnumSet.of(NonCompliance.TRANSFER_ENCODING)));
    }

    @Test
    public void testFragmentation() throws IOException
    {
        String contentType = "multipart/form-data, boundary=----WebKitFormBoundaryhXfFAMfUnUKhmqT8";
        String payload1 =
            "------WebKitFormBoundaryhXfFAMfUnUKhmqT8\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n\r\n" +
                "value1" +
                "\r\n--";
        String payload2 = "----WebKitFormBoundaryhXfFAMfUnUKhmqT8\r\n" +
            "Content-Disposition: form-data; name=\"field2\"\r\n\r\n" +
            "value2" +
            "\r\n------WebKitFormBoundaryhXfFAMfUnUKhmqT8--\r\n";

        // Split the content into separate reads, with the content broken up on the boundary string.
        AppendableInputStream stream = new AppendableInputStream();
        stream.append(payload1);
        stream.append("");
        stream.append(payload2);
        stream.endOfContent();

        MultipartConfigElement config = new MultipartConfigElement(_dirname);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(stream, contentType, config, _tmpDir);
        mpis.setDeleteOnExit(true);

        // Check size.
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(2));

        // Check part content.
        assertThat(IO.toString(mpis.getPart("field1").getInputStream()), is("value1"));
        assertThat(IO.toString(mpis.getPart("field2").getInputStream()), is("value2"));
    }

    static class AppendableInputStream extends InputStream
    {
        private static final ByteBuffer EOF = ByteBuffer.allocate(0);
        private final BlockingArrayQueue<ByteBuffer> buffers = new BlockingArrayQueue<>();
        private ByteBuffer current;

        public void append(String data)
        {
            append(data.getBytes(StandardCharsets.US_ASCII));
        }

        public void append(byte[] data)
        {
            buffers.add(BufferUtil.toBuffer(data));
        }

        public void endOfContent()
        {
            buffers.add(EOF);
        }

        @Override
        public int read() throws IOException
        {
            byte[] buf = new byte[1];
            while (true)
            {
                int len = read(buf, 0, 1);
                if (len < 0)
                    return -1;
                if (len > 0)
                    return buf[0];
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            if (current == null)
                current = buffers.poll();
            if (current == EOF)
                return -1;
            if (BufferUtil.isEmpty(current))
            {
                current = null;
                return 0;
            }

            ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
            buffer.flip();
            int read = BufferUtil.append(buffer, current);
            if (BufferUtil.isEmpty(current))
                current = buffers.poll();
            return read;
        }
    }

    @Test
    public void testQuotedPrintableEncoding() throws Exception
    {
        String contentWithEncodedPart =
            "--AaB03x\r\n" +
                "Content-disposition: form-data; name=\"other\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "other" + "\r\n" +
                "--AaB03x\r\n" +
                "Content-disposition: form-data; name=\"stuff\"; filename=\"stuff.txt\"\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "truth=3Dbeauty" + "\r\n" +
                "--AaB03x--\r\n";
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(contentWithEncodedPart.getBytes()),
            _contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertEquals(2, parts.size());

        Part p1 = mpis.getPart("other");
        assertNotNull(p1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IO.copy(p1.getInputStream(), baos);
        assertEquals("other", baos.toString(StandardCharsets.US_ASCII));

        Part p2 = mpis.getPart("stuff");
        assertNotNull(p2);
        baos = new ByteArrayOutputStream();
        IO.copy(p2.getInputStream(), baos);
        assertEquals("truth=3Dbeauty", baos.toString(StandardCharsets.US_ASCII));

        assertThat(mpis.getNonComplianceWarnings(), equalTo(EnumSet.of(NonCompliance.TRANSFER_ENCODING)));
    }

    @Test
    public void testGeneratedForm() throws Exception
    {
        String contentType = "multipart/form-data, boundary=WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW";
        String body = "Content-Type: multipart/form-data; boundary=WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r\n" +
            "\r\n" +
            "--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"part1\"\r\n" +
            "\n" +
            "wNfﾐxVam﾿t\r\n" +
            "--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\n" +
            "Content-Disposition: form-data; name=\"part2\"\r\n" +
            "\r\n" +
            "&ﾳﾺ￙￹ￖￃO\r\n" +
            "--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW--";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartFormInputStream mpis = new MultiPartFormInputStream(new ByteArrayInputStream(body.getBytes()),
            contentType,
            config,
            _tmpDir);
        mpis.setDeleteOnExit(true);

        Collection<Part> parts = mpis.getParts();
        assertThat(parts, notNullValue());
        assertThat(parts.size(), is(2));

        Part part1 = mpis.getPart("part1");
        assertThat(part1, notNullValue());
        Part part2 = mpis.getPart("part2");
        assertThat(part2, notNullValue());
    }

    private static String createMultipartRequestString(String filename)
    {
        int length = filename.length();
        String name = filename;
        if (length > 10)
            name = filename.substring(0, 10);
        StringBuilder filler = new StringBuilder();
        int i = name.length();
        while (i < 51)
        {
            filler.append("0");
            i++;
        }

        return "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"; filename=\"frooble.txt\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"" + filename + "\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" + name +
            filler.toString() + "\r\n" +
            "--AaB03x--\r\n";
    }
}

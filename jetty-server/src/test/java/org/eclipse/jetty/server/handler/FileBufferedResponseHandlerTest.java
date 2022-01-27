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

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class FileBufferedResponseHandlerTest
{
    private static final Logger LOG = LoggerFactory.getLogger(FileBufferedResponseHandlerTest.class);

    public WorkDir _workDir;

    private final CountDownLatch _disposeLatch = new CountDownLatch(1);
    private Server _server;
    private LocalConnector _localConnector;
    private ServerConnector _serverConnector;
    private Path _testDir;
    private FileBufferedResponseHandler _bufferedHandler;

    @BeforeEach
    public void before() throws Exception
    {
        _testDir = _workDir.getEmptyPathDir();

        _server = new Server();
        HttpConfiguration config = new HttpConfiguration();
        config.setOutputBufferSize(1024);
        config.setOutputAggregationSize(256);

        _localConnector = new LocalConnector(_server, new HttpConnectionFactory(config));
        _localConnector.setIdleTimeout(Duration.ofMinutes(1).toMillis());
        _server.addConnector(_localConnector);
        _serverConnector = new ServerConnector(_server, new HttpConnectionFactory(config));
        _server.addConnector(_serverConnector);

        _bufferedHandler = new FileBufferedResponseHandler()
        {
            @Override
            protected BufferedInterceptor newBufferedInterceptor(HttpChannel httpChannel, HttpOutput.Interceptor interceptor)
            {
                return new FileBufferedInterceptor(httpChannel, interceptor)
                {
                    @Override
                    protected void dispose()
                    {
                        super.dispose();
                        _disposeLatch.countDown();
                    }
                };
            }
        };
        _bufferedHandler.setTempDir(_testDir);
        _bufferedHandler.getPathIncludeExclude().include("/include/*");
        _bufferedHandler.getPathIncludeExclude().exclude("*.exclude");
        _bufferedHandler.getMimeIncludeExclude().exclude("text/excluded");
        _server.setHandler(_bufferedHandler);
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testPathNotIncluded() throws Exception
    {
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(10);
                PrintWriter writer = response.getWriter();
                writer.println("a string larger than the buffer size");
                writer.println("Committed: " + response.isCommitted());
                writer.println("NumFiles: " + getNumFiles());
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The response was committed after the first write and we never created a file to buffer the response into.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, containsString("Committed: true"));
        assertThat(responseContent, containsString("NumFiles: 0"));
        assertThat(getNumFiles(), is(0));
    }

    @Test
    public void testIncludedByPath() throws Exception
    {
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(10);
                PrintWriter writer = response.getWriter();
                writer.println("a string larger than the buffer size");
                writer.println("Committed: " + response.isCommitted());
                writer.println("NumFiles: " + getNumFiles());
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The response was not committed after the first write and a file was created to buffer the response.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, containsString("Committed: false"));
        assertThat(responseContent, containsString("NumFiles: 1"));

        // Unable to verify file deletion on windows, as immediate delete not possible.
        // only after a GC has occurred.
        if (!OS.WINDOWS.isCurrentOs())
        {
            assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
            assertThat(getNumFiles(), is(0));
        }
    }

    @Test
    public void testExcludedByPath() throws Exception
    {
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(10);
                PrintWriter writer = response.getWriter();
                writer.println("a string larger than the buffer size");
                writer.println("Committed: " + response.isCommitted());
                writer.println("NumFiles: " + getNumFiles());
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path.exclude HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The response was committed after the first write and we never created a file to buffer the response into.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, containsString("Committed: true"));
        assertThat(responseContent, containsString("NumFiles: 0"));
        assertThat(getNumFiles(), is(0));
    }

    @Test
    public void testExcludedByMime() throws Exception
    {
        String excludedMimeType = "text/excluded";
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setContentType(excludedMimeType);
                response.setBufferSize(10);
                PrintWriter writer = response.getWriter();
                writer.println("a string larger than the buffer size");
                writer.println("Committed: " + response.isCommitted());
                writer.println("NumFiles: " + getNumFiles());
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The response was committed after the first write and we never created a file to buffer the response into.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, containsString("Committed: true"));
        assertThat(responseContent, containsString("NumFiles: 0"));
        assertThat(getNumFiles(), is(0));
    }

    @Test
    public void testFlushed() throws Exception
    {
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(1024);
                PrintWriter writer = response.getWriter();
                writer.println("a string smaller than the buffer size");
                writer.println("NumFilesBeforeFlush: " + getNumFiles());
                writer.flush();
                writer.println("Committed: " + response.isCommitted());
                writer.println("NumFiles: " + getNumFiles());
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The response was not committed after the buffer was flushed and a file was created to buffer the response.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, containsString("NumFilesBeforeFlush: 0"));
        assertThat(responseContent, containsString("Committed: false"));
        assertThat(responseContent, containsString("NumFiles: 1"));

        // Unable to verify file deletion on windows, as immediate delete not possible.
        // only after a GC has occurred.
        if (!OS.WINDOWS.isCurrentOs())
        {
            assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
            assertThat(getNumFiles(), is(0));
        }
    }

    @Test
    public void testClosed() throws Exception
    {
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(10);
                PrintWriter writer = response.getWriter();
                writer.println("a string larger than the buffer size");
                writer.println("NumFiles: " + getNumFiles());
                writer.close();
                writer.println("writtenAfterClose");
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The content written after close was not sent.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, not(containsString("writtenAfterClose")));
        assertThat(responseContent, containsString("NumFiles: 1"));

        // Unable to verify file deletion on windows, as immediate delete not possible.
        // only after a GC has occurred.
        if (!OS.WINDOWS.isCurrentOs())
        {
            assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
            assertThat(getNumFiles(), is(0));
        }
    }

    @Test
    public void testBufferSizeBig() throws Exception
    {
        int bufferSize = 4096;
        String largeContent = generateContent(bufferSize - 64);
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(bufferSize);
                PrintWriter writer = response.getWriter();
                writer.println(largeContent);
                writer.println("Committed: " + response.isCommitted());
                writer.println("NumFiles: " + getNumFiles());
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The content written was not buffered as a file as it was less than the buffer size.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, not(containsString("writtenAfterClose")));
        assertThat(responseContent, containsString("Committed: false"));
        assertThat(responseContent, containsString("NumFiles: 0"));
        assertThat(getNumFiles(), is(0));
    }

    @Test
    public void testFlushEmpty() throws Exception
    {
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(1024);
                PrintWriter writer = response.getWriter();
                writer.flush();
                int numFiles = getNumFiles();
                writer.println("NumFiles: " + numFiles);
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // The flush should not create the file unless there is content to write.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, containsString("NumFiles: 0"));

        // Unable to verify file deletion on windows, as immediate delete not possible.
        // only after a GC has occurred.
        if (!OS.WINDOWS.isCurrentOs())
        {
            assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
            assertThat(getNumFiles(), is(0));
        }
    }

    @Test
    public void testReset() throws Exception
    {
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setBufferSize(8);
                PrintWriter writer = response.getWriter();
                writer.println("THIS WILL BE RESET");
                writer.flush();
                writer.println("THIS WILL BE RESET");
                int numFilesBeforeReset = getNumFiles();
                response.resetBuffer();
                int numFilesAfterReset = getNumFiles();

                writer.println("NumFilesBeforeReset: " + numFilesBeforeReset);
                writer.println("NumFilesAfterReset: " + numFilesAfterReset);
                writer.println("a string larger than the buffer size");
                writer.println("NumFilesAfterWrite: " + getNumFiles());
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String responseContent = response.getContent();

        // Resetting the response buffer will delete the file.
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(responseContent, not(containsString("THIS WILL BE RESET")));

        assertThat(responseContent, containsString("NumFilesBeforeReset: 1"));
        assertThat(responseContent, containsString("NumFilesAfterReset: 0"));
        assertThat(responseContent, containsString("NumFilesAfterWrite: 1"));

        // Unable to verify file deletion on windows, as immediate delete not possible.
        // only after a GC has occurred.
        if (!OS.WINDOWS.isCurrentOs())
        {
            assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
            assertThat(getNumFiles(), is(0));
        }
    }

    @Test
    public void testFileLargerThanMaxInteger() throws Exception
    {
        long fileSize = Integer.MAX_VALUE + 1234L;
        byte[] bytes = randomBytes(1024 * 1024);

        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                ServletOutputStream outputStream = response.getOutputStream();

                long written = 0;
                while (written < fileSize)
                {
                    int length = Math.toIntExact(Math.min(bytes.length, fileSize - written));
                    outputStream.write(bytes, 0, length);
                    written += length;
                }
                outputStream.flush();

                response.setHeader("NumFiles", Integer.toString(getNumFiles()));
                response.setHeader("FileSize", Long.toString(getFileSize()));
            }
        });

        _server.start();

        AtomicLong received = new AtomicLong();
        HttpTester.Response response = new HttpTester.Response()
        {
            @Override
            public boolean content(ByteBuffer ref)
            {
                // Verify the content is what was sent.
                while (ref.hasRemaining())
                {
                    byte byteFromBuffer = ref.get();
                    long totalReceived = received.getAndIncrement();
                    int bytesIndex = (int)(totalReceived % bytes.length);
                    byte byteFromArray = bytes[bytesIndex];

                    if (byteFromBuffer != byteFromArray)
                    {
                        LOG.warn("Mismatch at index {} received bytes {}, {}!={}", bytesIndex, totalReceived, byteFromBuffer, byteFromArray, new IllegalStateException());
                        return true;
                    }
                }

                return false;
            }
        };

        try (Socket socket = new Socket("localhost", _serverConnector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();
            String request = "GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.parseResponse(input, response);
        }

        assertTrue(response.isComplete());
        assertThat(response.get("NumFiles"), is("1"));
        assertThat(response.get("FileSize"), is(Long.toString(fileSize)));
        assertThat(received.get(), is(fileSize));

        // Unable to verify file deletion on windows, as immediate delete not possible.
        // only after a GC has occurred.
        if (!OS.WINDOWS.isCurrentOs())
        {
            assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
            assertThat(getNumFiles(), is(0));
        }
    }

    @Test
    public void testNextInterceptorFailed() throws Exception
    {
        AbstractHandler failingInterceptorHandler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                HttpOutput httpOutput = baseRequest.getResponse().getHttpOutput();
                HttpOutput.Interceptor nextInterceptor = httpOutput.getInterceptor();
                httpOutput.setInterceptor(new HttpOutput.Interceptor()
                {
                    @Override
                    public void write(ByteBuffer content, boolean last, Callback callback)
                    {
                        callback.failed(new Throwable("intentionally throwing from interceptor"));
                    }

                    @Override
                    public HttpOutput.Interceptor getNextInterceptor()
                    {
                        return nextInterceptor;
                    }
                });
            }
        };

        _server.setHandler(new HandlerCollection(failingInterceptorHandler, _server.getHandler()));
        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] chunk1 = "this content will ".getBytes();
                byte[] chunk2 = "be buffered in a file".getBytes();
                response.setContentLength(chunk1.length + chunk2.length);
                ServletOutputStream outputStream = response.getOutputStream();

                // Write chunk1 and then flush so it is written to the file.
                outputStream.write(chunk1);
                outputStream.flush();
                assertThat(getNumFiles(), is(1));

                try
                {
                    // ContentLength is set so it knows this is the last write.
                    // This will cause the file to be written to the next interceptor which will fail.
                    outputStream.write(chunk2);
                }
                catch (Throwable t)
                {
                    errorFuture.complete(t);
                    throw t;
                }
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // Response was aborted.
        assertThat(response.getStatus(), is(0));

        // We failed because of the next interceptor.
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error.getMessage(), containsString("intentionally throwing from interceptor"));

        // Unable to verify file deletion on windows, as immediate delete not possible.
        // only after a GC has occurred.
        if (!OS.WINDOWS.isCurrentOs())
        {
            // All files were deleted.
            assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
            assertThat(getNumFiles(), is(0));
        }
    }

    @Test
    public void testFileWriteFailed() throws Exception
    {
        // Set the temp directory to an empty directory so that the file cannot be created.
        File tempDir = MavenTestingUtils.getTargetTestingDir(getClass().getSimpleName());
        FS.ensureDeleted(tempDir);
        _bufferedHandler.setTempDir(tempDir.toPath());

        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        _bufferedHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                ServletOutputStream outputStream = response.getOutputStream();
                byte[] content = "this content will be buffered in a file".getBytes();

                try
                {
                    // Write the content and flush it to the file.
                    // This should throw as it cannot create the file to aggregate into.
                    outputStream.write(content);
                    outputStream.flush();
                }
                catch (Throwable t)
                {
                    errorFuture.complete(t);
                    throw t;
                }
            }
        });

        _server.start();
        String rawResponse = _localConnector.getResponse("GET /include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // Response was aborted.
        assertThat(response.getStatus(), is(0));

        // We failed because cannot create the file.
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error, instanceOf(NoSuchFileException.class));

        // No files were created.
        assertTrue(_disposeLatch.await(5, TimeUnit.SECONDS));
        assertThat(getNumFiles(), is(0));
    }

    private int getNumFiles()
    {
        File[] files = _testDir.toFile().listFiles();
        if (files == null)
            return 0;

        return files.length;
    }

    private long getFileSize()
    {
        File[] files = _testDir.toFile().listFiles();
        assertNotNull(files);
        assertThat(files.length, is(1));
        return files[0].length();
    }

    private static String generateContent(int size)
    {
        Random random = new Random();
        StringBuilder stringBuilder = new StringBuilder(size);
        for (int i = 0; i < size; i++)
        {
            stringBuilder.append((char)Math.abs(random.nextInt(0x7F)));
        }
        return stringBuilder.toString();
    }

    @SuppressWarnings("SameParameterValue")
    private byte[] randomBytes(int size)
    {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        return data;
    }
}

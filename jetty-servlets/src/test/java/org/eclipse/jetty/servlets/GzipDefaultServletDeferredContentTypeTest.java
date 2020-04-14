//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlets;

/**
 * GzipHandler setting of headers when reset and/or not compressed.
 *
 * The GzipHandler now sets deferred headers (content-length and etag) when it decides not to commit.
 * Also does not allow a reset after a decision to commit
 *
 * Originally from http://bugs.eclipse.org/408909
 */
public class GzipDefaultServletDeferredContentTypeTest extends AbstractGzipTest
{
    /*
    public static class AddDefaultServletCustomizer extends ServerHandlerCustomizer
    {
        @Override
        public Handler customize(ServletContextHandler servletContextHandler, Class<? extends Servlet> servletClass)
        {
            ServletHolder holder = new ServletHolder("default", servletClass);
            servletContextHandler.addServlet(holder, "/");
            return servletContextHandler;
        }
    }

    @Test
    public void testIsNotGzipCompressedByDeferredContentType() throws Exception
    {
        createServer(new AddDefaultServletCustomizer(), DeferredGetDefaultServlet.class);

        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;

        Path file = createFile("file.mp3.deferred", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.mp3.deferred");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));

        // Response Vary check
        assertThat("Response[Vary]", response.get("Vary"), is(emptyOrNullString()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    public static class DeferredGetDefaultServlet extends DefaultServlet
    {
        public DeferredGetDefaultServlet()
        {
            super();
        }

        @Override
        public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
        {
            String uri = req.getRequestURI();
            if (uri.endsWith(".deferred"))
            {
                // System.err.println("type for "+uri.substring(0,uri.length()-9)+" is "+getServletContext().getMimeType(uri.substring(0,uri.length()-9)));
                resp.setContentType(getServletContext().getMimeType(uri.substring(0, uri.length() - 9)));
            }

            doGet(req, resp);
        }
    }

     */
}

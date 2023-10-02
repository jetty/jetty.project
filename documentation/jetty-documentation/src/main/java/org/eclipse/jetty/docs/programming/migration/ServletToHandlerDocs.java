//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.migration;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CompletableTask;
import org.eclipse.jetty.util.Fields;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("unused")
public class ServletToHandlerDocs
{
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::request[]
    public class RequestAPIs extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Gets the request method.
            // Replaces:
            //   - servletRequest.getMethod();
            String method = request.getMethod();

            // Gets the request protocol name and version.
            // Replaces:
            //   - servletRequest.getProtocol();
            String protocol = request.getConnectionMetaData().getProtocol();

            // Gets the full request URI.
            // Replaces:
            //   - servletRequest.getRequestURL();
            String fullRequestURI = request.getHttpURI().asString();

            // Gets the request context.
            // Replaces:
            //   - servletRequest.getServletContext()
            Context context = request.getContext();

            // Gets the context path.
            // Replaces:
            //   - servletRequest.getContextPath()
            String contextPath = context.getContextPath();

            // Gets the request path.
            // Replaces:
            //   - servletRequest.getRequestURI();
            String requestPath = request.getHttpURI().getPath();

            // Gets the request path after the context path.
            // Replaces:
            //   - servletRequest.getServletPath() + servletRequest.getPathInfo()
            String pathInContext = Request.getPathInContext(request);

            // Gets the request query.
            // Replaces:
            //   - servletRequest.getQueryString()
            String queryString = request.getHttpURI().getQuery();

            // Gets request parameters.
            // Replaces:
            //   - servletRequest.getParameterNames();
            //   - servletRequest.getParameter(name);
            //   - servletRequest.getParameterValues(name);
            //   - servletRequest.getParameterMap();
            Fields queryParameters = Request.extractQueryParameters(request, UTF_8);
            Fields allParameters = Request.getParameters(request);

            // Gets cookies.
            // Replaces:
            //   - servletRequest.getCookies();
            List<HttpCookie> cookies = Request.getCookies(request);

            // Gets request HTTP headers.
            // Replaces:
            //   - servletRequest.getHeaderNames()
            //   - servletRequest.getHeader(name)
            //   - servletRequest.getHeaders(name)
            //   - servletRequest.getDateHeader(name)
            //   - servletRequest.getIntHeader(name)
            HttpFields requestHeaders = request.getHeaders();

            // Gets the request Content-Type.
            // Replaces:
            //   - servletRequest.getContentType()
            String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);

            // Gets the request Content-Length.
            // Replaces:
            //   - servletRequest.getContentLength()
            //   - servletRequest.getContentLengthLong()
            long contentLength = request.getLength();

            // Gets the request locales.
            // Replaces:
            //   - servletRequest.getLocale()
            //   - servletRequest.getLocales()
            List<Locale> locales = Request.getLocales(request);

            // Gets the request scheme.
            // Replaces:
            //   - servletRequest.getScheme()
            String scheme = request.getHttpURI().getScheme();

            // Gets the server name.
            // Replaces:
            //   - servletRequest.getServerName()
            String serverName = Request.getServerName(request);

            // Gets the server port.
            // Replaces:
            //   - servletRequest.getServerPort()
            int serverPort = Request.getServerPort(request);

            // Gets the remote host/address.
            // Replaces:
            //   - servletRequest.getRemoteAddr()
            //   - servletRequest.getRemoteHost()
            String remoteAddress = Request.getRemoteAddr(request);

            // Gets the remote port.
            // Replaces:
            //   - servletRequest.getRemotePort()
            int remotePort = Request.getRemotePort(request);

            // Gets the local host/address.
            // Replaces:
            //   - servletRequest.getLocalAddr()
            //   - servletRequest.getLocalHost()
            String localAddress = Request.getLocalAddr(request);

            // Gets the local port.
            // Replaces:
            //   - servletRequest.getLocalPort()
            int localPort = Request.getLocalPort(request);

            // Gets the request attributes.
            // Replaces:
            //   - servletRequest.getAttributeNames()
            //   - servletRequest.getAttribute(name)
            //   - servletRequest.setAttribute(name, value)
            //   - servletRequest.removeAttribute(name)
            String name = "name";
            Object value = "value";
            Set<String> names = request.getAttributeNameSet();
            Object attribute = request.getAttribute(name);
            Object oldValue = request.setAttribute(name, value);
            Object removedValue = request.removeAttribute(name);
            request.clearAttributes();
            Map<String, Object> map = request.asAttributeMap();

            // Gets the request trailers.
            // Replaces:
            //   - servletRequest.getTrailerFields()
            HttpFields trailers = request.getTrailers();

            // Gets the HTTP session.
            // Replaces:
            //   - servletRequest.getSession()
            //   - servletRequest.getSession(create)
            boolean create = true;
            Session session = request.getSession(create);

            callback.succeeded();
            return false;
        }
    }
    // end::request[]

    @SuppressWarnings("InnerClassMayBeStatic")
    public class RequestContentAPIsString extends Handler.Abstract
    {
        // tag::requestContent-string[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Non-blocking read the request content as a String.
            // Use with caution as the request content may be large.
            CompletableFuture<String> completable = Content.Source.asStringAsync(request, UTF_8);

            completable.whenComplete((requestContent, failure) ->
            {
                if (failure == null)
                {
                    // Process the request content here.

                    // Implicitly respond with status code 200 and no content.
                    callback.succeeded();
                }
                else
                {
                    // Implicitly respond with status code 500.
                    callback.failed(failure);
                }
            });

            return true;
        }
        // end::requestContent-string[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class RequestContentAPIsByteBuffer extends Handler.Abstract
    {
        // tag::requestContent-buffer[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Non-blocking read the request content as a ByteBuffer.
            // Use with caution as the request content may be large.
            CompletableFuture<ByteBuffer> completable = Content.Source.asByteBufferAsync(request);

            completable.whenComplete((requestContent, failure) ->
            {
                if (failure == null)
                {
                    // Process the request content here.

                    // Implicitly respond with status code 200 and no content.
                    callback.succeeded();
                }
                else
                {
                    // Implicitly respond with status code 500.
                    callback.failed(failure);
                }
            });

            return true;
        }
        // end::requestContent-buffer[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class RequestContentAPIsInputStream extends Handler.Abstract
    {
        // tag::requestContent-stream[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Read the request content as an InputStream.
            // Note that InputStream.read() may block.
            try (InputStream inputStream = Content.Source.asInputStream(request))
            {
                while (true)
                {
                    int read = inputStream.read();

                    // EOF was reached, stop reading.
                    if (read < 0)
                        break;

                    // Process the read byte here.
                }
            }

            // Implicitly respond with status code 200 and no content.
            callback.succeeded();
            return true;
        }
        // end::requestContent-stream[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class RequestContentAPIsSource extends Handler.Abstract
    {
        // tag::requestContent-source[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            CompletableTask<Void> reader = new CompletableTask<>()
            {
                @Override
                public void run()
                {
                    // Read in a loop.
                    while (true)
                    {
                        // Read a chunk of content.
                        Content.Chunk chunk = request.read();

                        // If there is no content, demand to be
                        // called back when more content is available.
                        if (chunk == null)
                        {
                            request.demand(this);
                            return;
                        }

                        // If a failure is read, complete with a failure.
                        if (Content.Chunk.isFailure(chunk))
                        {
                            Throwable failure = chunk.getFailure();
                            completeExceptionally(failure);
                            return;
                        }

                        if (chunk instanceof Trailers trailers)
                        {
                            // Possibly process the request trailers here.
                            // Trailers have an empty ByteBuffer and are a last chunk.
                        }

                        // Process the request content chunk here.
                        // After the processing, the chunk MUST be released.
                        chunk.release();

                        // If the last chunk is read, complete normally.
                        if (chunk.isLast())
                        {
                            complete(null);
                            return;
                        }

                        // Not the last chunk of content, loop around to read more.
                    }
                }
            };

            // Initiate the read of the request content.
            reader.start();

            // When the read is complete, complete the Handler callback.
            callback.completeWith(reader);

            return true;
        }
        // end::requestContent-source[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::response[]
    public class ResponseAPIs extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Sets/Gets the response HTTP status.
            // Replaces:
            //   - servletResponse.setStatus(code);
            //   - servletResponse.getStatus();
            response.setStatus(HttpStatus.OK_200);
            int status = response.getStatus();

            // Gets the response HTTP headers.
            // Replaces:
            //   - servletResponse.setHeader(name, value);
            //   - servletResponse.addHeader(name, value);
            //   - servletResponse.setDateHeader(name, date);
            //   - servletResponse.addDateHeader(name, date);
            //   - servletResponse.setIntHeader(name, value);
            //   - servletResponse.addIntHeader(name, value);
            //   - servletResponse.getHeaderNames()
            //   - servletResponse.getHeader(name)
            //   - servletResponse.getHeaders(name)
            //   - servletResponse.containsHeader(name)
            HttpFields.Mutable responseHeaders = response.getHeaders();

            // Sets an HTTP cookie.
            // Replaces:
            //   - Cookie cookie = new Cookie("name", "value");
            //   - cookie.setDomain("example.org");
            //   - cookie.setPath("/path");
            //   - cookie.setMaxAge(24 * 3600);
            //   - cookie.setAttribute("SameSite", "Lax");
            //   - servletResponse.addCookie(cookie);
            HttpCookie cookie = HttpCookie.build("name", "value")
                .domain("example.org")
                .path("/path")
                .maxAge(Duration.ofDays(1).toSeconds())
                .sameSite(HttpCookie.SameSite.LAX)
                .build();
            Response.addCookie(response, cookie);

            // Sets the response Content-Type.
            // Replaces:
            //   - servletResponse.setContentType(type)
            responseHeaders.put(HttpHeader.CONTENT_TYPE, "text/plain; charset=UTF-8");

            // Sets the response Content-Length.
            // Replaces:
            //   - servletResponse.setContentLength(length)
            //   - servletResponse.setContentLengthLong(length)
            responseHeaders.put(HttpHeader.CONTENT_LENGTH, 1024L);

            // Sets/Gets the response trailers.
            // Replaces:
            //   - servletResponse.setTrailerFields(() -> trailers)
            //   - servletResponse.getTrailerFields()
            HttpFields trailers = HttpFields.build().put("checksum", 0xCAFE);
            response.setTrailersSupplier(trailers);
            Supplier<HttpFields> trailersSupplier = response.getTrailersSupplier();

            // Gets whether the response is committed.
            // Replaces:
            //   - servletResponse.isCommitted()
            boolean committed = response.isCommitted();

            // Resets the response.
            // Replaces:
            //   - servletResponse.reset();
            response.reset();

            // Sends a redirect response.
            // Replaces:
            //   - servletResponse.encodeRedirectURL(location)
            //   - servletResponse.sendRedirect(location)
            String location = Request.toRedirectURI(request, "/redirect");
            Response.sendRedirect(request, response, callback, location);

            // Sends an error response.
            // Replaces:
            //   - servletResponse.sendError(code);
            //   - servletResponse.sendError(code, message);
            Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503, "Request Cannot be Processed");

            callback.succeeded();
            return true;
        }
    }
    // end::response[]

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPIsImplicit extends Handler.Abstract
    {
        // tag::responseContent-implicit[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Produces an implicit response with status code 200
            // with no content when returning from this method.

            // The Handler callback must be completed when returning true.
            callback.succeeded();
            return true;
        }
        // end::responseContent-implicit[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPIsImplicitWithStatus extends Handler.Abstract
    {
        // tag::responseContent-implicit-status[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Produces an implicit response with status 204
            // with no content when returning from this method.
            response.setStatus(HttpStatus.NO_CONTENT_204);

            // The Handler callback must be completed when returning true.
            callback.succeeded();
            return true;
        }
        // end::responseContent-implicit-status[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPIsExplicit extends Handler.Abstract
    {
        // tag::responseContent-explicit[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            // Produces an explicit response with status 204 with no content.
            response.setStatus(HttpStatus.NO_CONTENT_204);

            // This explicit first write() writes the response status code and headers.
            // It is also the last write (as specified by the first parameter)
            // and writes an empty content (the second parameter, a null ByteBuffer).
            // When this write completes, the Handler callback is completed.
            response.write(true, null, callback);

            return true;
        }
        // end::responseContent-explicit[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPISimpleContent extends Handler.Abstract
    {
        // tag::responseContent-content[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(HttpStatus.OK_200);

            ByteBuffer content = UTF_8.encode("Hello World");

            // Explicit first write that writes the response status code, headers and content.
            // When this write completes, the Handler callback is completed.
            response.write(true, content, callback);

            return true;
        }
        // end::responseContent-content[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPIFlush extends Handler.Abstract
    {
        // tag::responseContent-content[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(HttpStatus.OK_200);

            ByteBuffer content = UTF_8.encode("Hello World");
            response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.remaining());

            // Flush the response status code and the headers (no content).
            // This is the fist but non-last write.
            Callback.Completable completable = new Callback.Completable();
            response.write(false, null, completable);

            // When the first write completes, perform the second (and last) write.
            completable.whenComplete((ignored, failure) ->
            {
                if (failure == null)
                {
                    // Now explicitly write the content as the last write.
                    // When this write completes, the Handler callback is completed.
                    response.write(true, content, callback);
                }
                else
                {
                    // Implicitly respond with status code 500.
                    callback.failed(failure);
                }
            });

            return true;
        }
        // end::responseContent-content[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPIString extends Handler.Abstract
    {
        // tag::responseContent-string[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(HttpStatus.OK_200);

            // Utility method to write UTF-8 string content.
            // When this write completes, the Handler callback is completed.
            Content.Sink.write(response, true, "Hello World", callback);

            return true;
        }
        // end::responseContent-string[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPIEcho extends Handler.Abstract
    {
        // tag::responseContent-echo[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(HttpStatus.OK_200);

            // Utility method to echo the content from the request to the response.
            // When the echo completes, the Handler callback is completed.
            Content.copy(request, response, callback);

            return true;
        }
        // end::responseContent-echo[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class ResponseContentAPITrailers extends Handler.Abstract
    {
        // tag::responseContent-trailers[]
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(HttpStatus.OK_200);

            // The trailers must be set on the response before the first write.
            HttpFields.Mutable trailers = HttpFields.build();
            response.setTrailersSupplier(trailers);

            // Explicit first write that writes the response status code, headers and content.
            // The trailers have not been written yet; they will be written with the last write.
            ByteBuffer content = UTF_8.encode("Hello World");
            Callback.Completable completable = new Callback.Completable();
            response.write(false, content, completable);

            completable.whenComplete((ignored, failure) ->
            {
                if (failure == null)
                {
                    // Update the trailers
                    trailers.put("Content-Checksum", 0xCAFE);

                    // Explicit last write to write the trailers
                    // and complete the Handler callback.
                    response.write(true, null, callback);
                }
                else
                {
                    // Implicitly respond with status code 500.
                    callback.failed(failure);
                }
            });

            return true;
        }
        // end::responseContent-trailers[]
    }
}

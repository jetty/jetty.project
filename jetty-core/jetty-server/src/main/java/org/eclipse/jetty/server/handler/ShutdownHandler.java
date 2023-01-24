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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A {@link Handler} that initiates a Shutdown of the Jetty Server it belongs to.
 * </p>
 *
 * <p>
 * Used to trigger shutdown of a Jetty Server instance
 * <ul>
 *    <li>If {@code exitJvm} is set to true a hard {@link System#exit(int)} call will be performed.</li>
 * </ul>
 *
 * Server Setup Example:
 *
 * <pre>{@code
 * Server server = new Server(8080);
 * Handler.Collection handlers = new Handler.Collection();
 * handlers.addHandler(someOtherHandler);
 * String shutdownToken = "secret password";
 * boolean exitJvm = false;
 * handlers.addHandler(new ShutdownHandler(shutdownToken, exitJvm));
 * server.setHandler(handlers);
 * server.start();
 * }</pre>
 *
 * Client Triggering Example
 *
 * <pre>{@code
 * public static void attemptShutdown(int port, String shutdownToken) {
 *   try {
 *     String encodedToken = URLEncoder.encode(shutdownToken);
 *     URI uri = URI.create("http://localhost:%d/shutdown?token=%s".formatted(port, shutdownCookie));
 *     HttpClient httpClient = HttpClient.newBuilder().build();
 *     HttpRequest httpRequest = HttpRequest.newBuilder(shutdownURI)
 *         .POST(HttpRequest.BodyPublishers.noBody())
 *         .build();
 *     HttpResponse<String> httpResponse = httpClient.send(httpRequest,
 *         HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
 *     Assertions.assertEquals(200, httpResponse.statusCode());
 *     System.out.println(httpResponse.body());
 *     logger.info("Shutting down " + uri + ": " + httpResponse.body());
 *   } catch (IOException | InterruptedException e) {
 *     logger.debug("Shutdown Handler not available");
 *     // Okay - the server is not running
 *     throw new RuntimeException(e);
 *   }
 * }
 * }</pre>
 */
public class ShutdownHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ShutdownHandler.class);

    private final String _shutdownPath;
    private final String _shutdownToken;
    private boolean _exitJvm = false;

    /**
     * Creates a Handler that lets the server be shut down remotely (but only from localhost).
     *
     * @param shutdownToken a secret password to avoid unauthorized shutdown attempts
     */
    public ShutdownHandler(String shutdownToken)
    {
        this(null, shutdownToken, false);
    }

    /**
     * Creates a Handler that lets the server be shut down remotely (but only from localhost).
     *
     * @param shutdownToken a secret password to avoid unauthorized shutdown attempts
     * @param exitJVM If true, when the shutdown is executed, the handler class System.exit()
     */
    public ShutdownHandler(String shutdownToken, boolean exitJVM)
    {
        this(null, shutdownToken, exitJVM);
    }

    /**
     * Creates a Handler that lets the server be shut down remotely (but only from localhost).
     *
     * @param shutdownPath the path to respond to shutdown requests against (default is "{@code /shutdown}")
     * @param shutdownToken a secret password to avoid unauthorized shutdown attempts
     * @param exitJVM If true, when the shutdown is executed, the handler class System.exit()
     */
    public ShutdownHandler(String shutdownPath, String shutdownToken, boolean exitJVM)
    {
        this._shutdownPath = StringUtil.isBlank(shutdownPath) ? "/shutdown" : shutdownPath;
        this._shutdownToken = shutdownToken;
        this._exitJvm = exitJVM;
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        String fullPath = request.getHttpURI().getCanonicalPath();
        ContextHandler contextHandler = ContextHandler.getContextHandler(request);
        if (contextHandler != null)
        {
            // We are operating in a context, so use it
            String pathInContext = contextHandler.getContext().getPathInContext(fullPath);
            if (!pathInContext.startsWith(this._shutdownPath))
            {
                return super.process(request, response, callback);
            }
        }
        else
        {
            // We are standalone
            if (!fullPath.startsWith(this._shutdownPath))
            {
                return super.process(request, response, callback);
            }
        }

        if (!request.getMethod().equals("POST"))
        {
            Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400);
            return true;
        }

        if (!hasCorrectSecurityToken(request))
        {
            LOG.warn("Unauthorized tokenless shutdown attempt from {}", request.getConnectionMetaData().getRemoteSocketAddress());
            Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401);
            return true;
        }
        if (!requestFromLocalhost(request))
        {
            LOG.warn("Unauthorized non-loopback shutdown attempt from {}", request.getConnectionMetaData().getRemoteSocketAddress());
            Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401);
            return true;
        }

        LOG.info("Shutting down by request from {}", request.getConnectionMetaData().getRemoteSocketAddress());
        // Establish callback to trigger server shutdown when write of response is complete
        Callback triggerShutdownCallback = Callback.from(() ->
        {
            CompletableFuture.runAsync(this::shutdownServer);
        });
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain, charset=utf-8");
        String message = "Shutdown triggered";
        Content.Sink.write(response, true, message, triggerShutdownCallback);
        return true;
    }

    private boolean requestFromLocalhost(Request request)
    {
        SocketAddress socketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (socketAddress == null)
            return false;

        if (socketAddress instanceof InetSocketAddress addr)
            return addr.getAddress().isLoopbackAddress();

        return false;
    }

    private boolean hasCorrectSecurityToken(Request request)
    {
        Fields fields = Request.extractQueryParameters(request);
        String tok = fields.getValue("token");
        if (LOG.isDebugEnabled())
            LOG.debug("Token: {}", tok);
        return _shutdownToken.equals(tok);
    }

    private void shutdownServer()
    {
        try
        {
            // Let server stop normally.
            // Order of stop is controlled by server.
            // Graceful stop can even be configured at the Server level
            getServer().stop();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to stop server", e);
        }

        if (_exitJvm)
        {
            System.exit(0);
        }
    }

    public void setExitJvm(boolean exitJvm)
    {
        this._exitJvm = exitJvm;
    }

    public boolean isExitJvm()
    {
        return _exitJvm;
    }
}

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A {@link Handler} that initiates a Shutdown of the Jetty Server it belongs to.
 * </p>
 *
 * <p>
 * Used to do "soft" restarts from Java.
 * <ul>
 *    <li>If {@code exitJvm} is set to true a hard {@link System#exit(int)} call will be performed.</li>
 *    <li>If {@code sendShutdownAtStart} is set to true, starting the Jetty Server will try to shut down an
 *    existing server at the same port.</li>
 *    <li>If _sendShutdownAtStart is set to true, make an http call to
 *    {@code "http://localhost:" + port + "/shutdown?token=" + shutdownCookie} in order to shut down the server.</li>
 * </ul>
 *
 * Usage:
 *
 * <pre>{@code
 * Server server = new Server(8080);
 * Handler.Collection handlers = new Handler.Collection();
 * handlers.addHandler(someOtherHandler);
 * handlers.addHandler(new ShutdownHandler("secret password", false));
 * server.setHandler(handlers);
 * server.start();
 * }</pre>
 *
 * <pre>{@code
 * public static void attemptShutdown(int port, String shutdownCookie) {
 *   try {
 *     URI uri = URI.create("http://localhost:%d/shutdown?token=%s".formatted(port, shutdownCookie));
 *     HttpURLConnection connection = (HttpURLConnection)url.openConnection();
 * connection.setRequestMethod("POST");
 * connection.getResponseCode();
 * logger.info("Shutting down " + url + ": " + connection.getResponseMessage());
 * } catch (SocketException e) {
 * logger.debug("Not running");
 * // Okay - the server is not running
 * } catch (IOException e) {
 * throw new RuntimeException(e);
 * }
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
     * Creates a listener that lets the server be shut down remotely (but only from localhost).
     *
     * @param shutdownToken a secret password to avoid unauthorized shutdown attempts
     */
    public ShutdownHandler(String shutdownToken)
    {
        this("/shutdown", shutdownToken, false);
    }

    /**
     * @param shutdownToken a secret password to avoid unauthorized shutdown attempts
     * @param exitJVM If true, when the shutdown is executed, the handler class System.exit()
     */
    public ShutdownHandler(String shutdownToken, boolean exitJVM)
    {
        this("/shutdown", shutdownToken, exitJVM);
    }

    public ShutdownHandler(String shutdownPath, String shutdownToken, boolean exitJvm)
    {
        this._shutdownPath = shutdownPath;
        this._shutdownToken = shutdownToken;
        this._exitJvm = exitJvm;
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

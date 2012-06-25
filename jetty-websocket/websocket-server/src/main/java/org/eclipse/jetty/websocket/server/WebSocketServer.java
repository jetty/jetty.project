package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.extensions.Extension;

/**
 * Main API class for WebSocket servers
 */
public interface WebSocketServer
{
    public static interface Acceptor
    {
        /**
         * <p>
         * Checks the origin of an incoming WebSocket handshake request.
         * </p>
         * 
         * @param request
         *            the incoming HTTP upgrade request
         * @param origin
         *            the origin URI
         * @return boolean to indicate that the origin is acceptable.
         */
        boolean checkOrigin(HttpServletRequest request, String origin);

        /* ------------------------------------------------------------ */
        /**
         * <p>
         * Factory method that applications needs to implement to return a {@link WebSocket} object.
         * </p>
         * 
         * @param request
         *            the incoming HTTP upgrade request
         * @param protocol
         *            the websocket sub protocol
         * @return a new {@link WebSocket} object that will handle websocket events.
         */
        WebSocket doWebSocketConnect(HttpServletRequest request, String protocol);
    }

    public static interface Handshake
    {
        /**
         * Formulate a WebSocket upgrade handshake response.
         * 
         * @param request
         * @param response
         * @param extensions
         * @param acceptedSubProtocol
         */
        public void doHandshakeResponse(HttpServletRequest request, HttpServletResponse response, List<Extension> extensions, String acceptedSubProtocol)
                throws IOException;
    }
}

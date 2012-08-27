//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.npn;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

/**
 * <p>{@link NextProtoNego} provides an API to applications that want to make use of the
 * <a href="http://technotes.googlecode.com/git/nextprotoneg.html">Next Protocol Negotiation</a>.</p>
 * <p>The NPN extension is only available when using the TLS protocol, therefore applications must
 * ensure that the TLS protocol is used:</p>
 * <pre>
 * SSLContext context = SSLContext.getInstance("TLSv1");
 * </pre>
 * <p>Refer to the
 * <a href="http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#SSLContext">list
 * of standard SSLContext protocol names</a> for further information on TLS protocol versions supported.</p>
 * <p>Applications must register instances of either {@link SSLSocket} or {@link SSLEngine} with a
 * {@link ClientProvider} or with a {@link ServerProvider}, depending whether they are on client or
 * server side.</p>
 * <p>The NPN implementation will invoke the provider callbacks to allow applications to interact
 * with the negotiation of the next protocol.</p>
 * <p>Client side typical usage:</p>
 * <pre>
 * SSLSocket sslSocket = ...;
 * NextProtoNego.put(sslSocket, new NextProtoNego.ClientProvider()
 * {
 *     &#64;Override
 *     public boolean supports()
 *     {
 *         return true;
 *     }
 *
 *     &#64;Override
 *     public void unsupported()
 *     {
 *     }
 *
 *     &#64;Override
 *     public String selectProtocol(List&lt;String&gt; protocols)
 *     {
 *         return protocols.get(0);
 *     }
 *  });
 * </pre>
 * <p>Server side typical usage:</p>
 * <pre>
 * SSLSocket sslSocket = ...;
 * NextProtoNego.put(sslSocket, new NextProtoNego.ServerProvider()
 * {
 *     &#64;Override
 *     public void unsupported()
 *     {
 *     }
 *
 *     &#64;Override
 *     public List<String> protocols()
 *     {
 *         return Arrays.asList("http/1.1");
 *     }
 *
 *     &#64;Override
 *     public void protocolSelected(String protocol)
 *     {
 *         System.out.println("Protocol Selected is: " + protocol);
 *     }
 *  });
 * </pre>
 * <p>There is no need to unregister {@link SSLSocket} or {@link SSLEngine} instances, as they
 * are kept in a {@link WeakHashMap} and will be garbage collected when the application does not
 * hard reference them anymore. However, methods to explicitly unregister {@link SSLSocket} or
 * {@link SSLEngine} instances are provided.</p>
 * <p>In order to help application development, you can set the {@link NextProtoNego#debug} field
 * to {@code true} to have debug code printed to {@link System#err}.</p>
 */
public class NextProtoNego
{
    /**
     * <p>Enables debug logging on {@link System#err}.</p>
     */
    public static boolean debug = false;

    private static Map<Object, Provider> objects = Collections.synchronizedMap(new WeakHashMap<Object, Provider>());

    private NextProtoNego()
    {
    }

    /**
     * <p>Registers a SSLSocket with a provider.</p>
     *
     * @param socket the socket to register with the provider
     * @param provider the provider to register with the socket
     * @see #remove(SSLSocket)
     */
    public static void put(SSLSocket socket, Provider provider)
    {
        objects.put(socket, provider);
    }

    /**
     * @param socket a socket registered with {@link #put(SSLSocket, Provider)}
     * @return the provider registered with the given socket
     */
    public static Provider get(SSLSocket socket)
    {
        return objects.get(socket);
    }

    /**
     * <p>Unregisters the given SSLSocket.</p>
     *
     * @param socket the socket to unregister
     * @return the provider registered with the socket
     * @see #put(SSLSocket, Provider)
     */
    public static Provider remove(SSLSocket socket)
    {
        return objects.remove(socket);
    }

    /**
     * <p>Registers a SSLEngine with a provider.</p>
     *
     * @param engine the engine to register with the provider
     * @param provider the provider to register with the engine
     * @see #remove(SSLEngine)
     */
    public static void put(SSLEngine engine, Provider provider)
    {
        objects.put(engine, provider);
    }

    /**
     *
     * @param engine an engine registered with {@link #put(SSLEngine, Provider)}
     * @return the provider registered with the given engine
     */
    public static Provider get(SSLEngine engine)
    {
        return objects.get(engine);
    }

    /**
     * <p>Unregisters the given SSLEngine.</p>
     *
     * @param engine the engine to unregister
     * @return the provider registered with the engine
     * @see #put(SSLEngine, Provider)
     */
    public static Provider remove(SSLEngine engine)
    {
        return objects.remove(engine);
    }

    /**
     * <p>Base, empty, interface for providers.</p>
     */
    public interface Provider
    {
    }

    /**
     * <p>The client-side provider interface that applications must implement to interact
     * with the negotiation of the next protocol.</p>
     */
    public interface ClientProvider extends Provider
    {
        /**
         * <p>Callback invoked to let the implementation know whether an
         * empty NPN extension should be added to a ClientHello SSL message.</p>
         *
         * @return true to add the NPN extension, false otherwise
         */
        public boolean supports();

        /**
         * <p>Callback invoked to let the application know that the server does
         * not support NPN.</p>
         */
        public void unsupported();

        /**
         * <p>Callback invoked to let the application select a protocol
         * among the ones sent by the server.</p>
         *
         * @param protocols the protocols sent by the server
         * @return the protocol selected by the application, or null if the
         * NextProtocol SSL message should not be sent to the server
         */
        public String selectProtocol(List<String> protocols);
    }

    /**
     * <p>The server-side provider interface that applications must implement to interact
     * with the negotiation of the next protocol.</p>
     */
    public interface ServerProvider extends Provider
    {
        /**
         * <p>Callback invoked to let the application know that the client does not
         * support NPN.</p>
         */
        public void unsupported();

        /**
         * <p>Callback invoked to let the implementation know the list
         * of protocols that should be added to an NPN extension in a
         * ServerHello SSL message.</p>
         * <p>This callback is invoked only if the client sent a NPN extension.</p>
         *
         * @return the list of protocols, or null if no NPN extension
         * should be sent to the client
         */
        public List<String> protocols();

        /**
         * <p>Callback invoked to let the application know the protocol selected
         * by the client.</p>
         * <p>This callback is invoked only if the client sent a NextProtocol SSL message.</p>
         *
         * @param protocol the selected protocol
         */
        public void protocolSelected(String protocol);
    }
}

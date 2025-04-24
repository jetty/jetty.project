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

/**
 * <p>This module contains the main abstractions for the QUIC protocol using the Quiche library.</p>
 * <p>A {@link org.eclipse.jetty.quic.quiche.QuicheConnection} is a {@link org.eclipse.jetty.io.Connection}
 * that receives and sends bytes from its underlying {@link org.eclipse.jetty.io.DatagramChannelEndPoint}.</p>
 * <p>A {@link org.eclipse.jetty.quic.quiche.QuicheConnection} manages one (on the client) or many (on the
 * server) {@link org.eclipse.jetty.quic.quiche.QuicheSession}s, one for each QUIC connection id.</p>
 *
 * <p>A {@link org.eclipse.jetty.quic.quiche.QuicheSession} manages many QUIC streams, identified by a
 * stream id and represented by {@link org.eclipse.jetty.quic.quiche.QuicheStream}.</p>
 * <p>A {@link org.eclipse.jetty.quic.quiche.QuicheSession} delegates I/O processing to a protocol-specific
 * {@link org.eclipse.jetty.quic.common.ProtocolSession}, whose responsibility is to use QUIC streams
 * to implement the protocol-specific I/O processing.</p>
 *
 * <p>A {@link org.eclipse.jetty.quic.common.ProtocolSession} manages many {@link org.eclipse.jetty.quic.common.StreamEndPoint}s,
 * one for each QUIC stream.</p>
 * <p>The {@link org.eclipse.jetty.io.Connection} associated with each {@link org.eclipse.jetty.quic.common.StreamEndPoint}
 * parses the bytes received on the QUIC stream, and generates the bytes to send on that QUIC stream.</p>
 *
 * <p>On the client side, the layout of the components in case of HTTP/1.1 is the following:</p>
 * <pre>{@code
 * DatagramChannelEndPoint -- ClientQuicheConnection -- ClientQuicheSession -- ClientProtocolSession -- StreamEndPoint -- HttpConnectionOverHTTP
 * }</pre>
 * <p>The client specific {@link org.eclipse.jetty.quic.common.ProtocolSession} creates one bidirectional
 * QUIC stream that represent the same transport as a TCP stream, over which HTTP/1.1 bytes are exchanged
 * by the two peers.</p>
 * <p>On the client side, the layout of the components in case of HTTP/3 is the following:</p>
 * <pre>{@code
 * DatagramChannelEndPoint -- ClientQuicheConnection -- ClientQuicheSession -- ClientHTTP3Session -* StreamEndPoint -- ClientHTTP3StreamConnection
 * }</pre>
 * <p>In this case, the client specific, HTTP/3 specific, {@link org.eclipse.jetty.quic.common.ProtocolSession}
 * creates and manages zero or more bidirectional QUIC streams, over which HTTP/3 bytes are exchanged
 * by the two peers, as well as the unidirectional QUIC streams required by the HTTP/3 protocol.</p>
 *
 * <p>On the server side, the layout of the components in case of HTTP/1.1 is the following:</p>
 * <pre>{@code
 * CLIENT  |  SERVER
 * clientA                                                   ServerQuicheSessionA -- ServerProtocolSessionA -- StreamEndPointA -- HttpConnection
 *         \                                                /
 *           DatagramChannelEndPoint -- ServerQuicheConnection
 *         /                                                \
 * clientB                                                   ServerQuicheSessionB -- ServerProtocolSessionB -- StreamEndPointB -- HttpConnection
 * }</pre>
 * <p>The {@link org.eclipse.jetty.io.DatagramChannelEndPoint} listens on the server port and receives
 * UDP datagrams from all clients.</p>
 * <p>The server side {@link org.eclipse.jetty.quic.quiche.QuicheConnection} processes the incoming datagram
 * bytes creating a {@link org.eclipse.jetty.quic.quiche.QuicheSession} for every QUIC connection id sent by
 * the clients.</p>
 * <p>The clients have created a single QUIC stream to send HTTP/1.1 requests, which results in the
 * {@link org.eclipse.jetty.quic.quiche.QuicheSession}s to create a correspondent
 * {@link org.eclipse.jetty.quic.common.StreamEndPoint} with its associated {@code HttpConnection}.</p>
 * <p>The path {@code DatagramChannelEndPoint -- ServerQuicheConnection -- ServerQuicheSession -- ServerProtocolSession -- StreamEndPoint}
 * behaves exactly like a TCP {@link org.eclipse.jetty.io.SocketChannelEndPoint} for the associated
 * {@code HttpConnection}.</p>
 * <p>On the server side, the layout of the components in case of HTTP/3 is the following:</p>
 * <pre>{@code
 * CLIENT  |  SERVER
 * clientA                                                   ServerQuicheSessionA -# ServerHTTP3SessionA -- StreamEndPointA1 -- ServerHTTP3StreamConnection
 *         \                                                /                                            `- StreamEndPointA2 -- ServerHTTP3StreamConnection
 *           DatagramChannelEndPoint -- ServerQuicheConnection
 *         /                                                \                                            ,- StreamEndPointB1 -- ServerHTTP3StreamConnection
 * clientB                                                   ServerQuicheSessionB -# ServerHTTP3SessionB -- StreamEndPointB2 -- ServerHTTP3StreamConnection
 * }</pre>
 * <p>In this case, the server specific, HTTP/3 specific, {@link org.eclipse.jetty.quic.common.ProtocolSession}
 * creates and manages zero or more bidirectional QUIC streams, created by the clients, over which HTTP/3 bytes
 * are exchanged by the two peers, as well as the unidirectional QUIC streams required by the HTTP/3 protocol.</p>
 *
 * <p>In a more compact representation, the server side layout is the following:</p>
 * <pre>{@code
 * DatagramChannelEndPoint -- ServerQuicheConnection -*# ServerQuicheSession -- ServerHTTP3Session -* StreamEndPoint -- ServerHTTP3StreamConnection
 * }</pre>
 * where {@code --} represents a 1-1 relationship, {@code -*} represents a 1-N relationship, and {@code -#} represents the
 * place where a new thread is dispatched to process different QUIC connection ids so that they can be processed in parallel,
 * as it would naturally happen with TCP (which has a "thread per active connection" model).
 */
package org.eclipse.jetty.quic.quiche;

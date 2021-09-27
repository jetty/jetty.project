//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
 * <p>This module contains the main abstractions for the QUIC protocol.</p>
 * <p>A {@link org.eclipse.jetty.quic.common.QuicConnection} is a {@link org.eclipse.jetty.io.Connection}
 * that receives and sends bytes from its underlying {@link org.eclipse.jetty.io.DatagramChannelEndPoint}.</p>
 * <p>A {@link org.eclipse.jetty.quic.common.QuicConnection} manages many {@link org.eclipse.jetty.quic.common.QuicSession}s,
 * one for each QUIC connection ID.</p>
 * <p>A {@link org.eclipse.jetty.quic.common.QuicSession} manages many QUIC streams, identified by a
 * stream ID and represented by {@link org.eclipse.jetty.quic.common.QuicStreamEndPoint}.</p>
 * <p>A {@link org.eclipse.jetty.quic.common.QuicSession} delegates I/O processing to a protocol-specific
 * {@link org.eclipse.jetty.quic.common.ProtocolSession}, whose responsibility is to use QUIC streams
 * to implement the protocol-specific I/O processing.</p>
 * <p>The {@link org.eclipse.jetty.io.Connection} associated with each {@link org.eclipse.jetty.quic.common.QuicStreamEndPoint}
 * parses the bytes received on the QUIC stream represented by the {@link org.eclipse.jetty.quic.common.QuicStreamEndPoint},
 * and generates the bytes to send on that QUIC stream.</p>
 * <p>On the client side, the layout of the components in case of HTTP/1.1 could be the following:</p>
 * <pre>
 * DatagramChannelEndPoint -- QuicConnection -- QuicSession -- ProtocolSession -- QuicStreamEndPoint -- HttpConnectionOverHTTP
 * </pre>
 * <p>The client-specific {@link org.eclipse.jetty.quic.common.ProtocolSession} creates a bidirectional QUIC stream
 * that represent the same transport as a TCP stream, over which HTTP/1.1 bytes are exchanged by the two peers.</p>
 * <p>On the server side, the layout of the components in case of HTTP/1.1 could be the following:</p>
 * <pre>
 * CLIENT  |  SERVER
 *
 * clientA                                              QuicSessionA -- ProtocolSessionA -- QuicStreamEndPointA -- HttpConnection
 *         \                                           /
 *           DatagramChannelEndPoint -- QuicConnection
 *         /                                           \
 * clientB                                              QuicSessionB -- ProtocolSessionB -- QuicStreamEndPointB -- HttpConnection
 * </pre>
 * <p>The {@code DatagramChannelEndPoint} receives UDP datagrams from clients.</p>
 * <p>{@code QuicConnection} processes the incoming datagram bytes creating a {@code QuicSession} for every
 * QUIC connection ID sent by the clients.</p>
 * <p>The clients have created a single QUIC stream to send HTTP/1.1 requests, which results in the
 * {@code QuicSession}s to create a correspondent {@code QuicStreamEndPoint} with its associated {@code HttpConnection}.</p>
 * <p>The path {@code DatagramChannelEndPoint - QuicConnection - QuicSession - QuicStreamEndPoint}
 * behaves exactly like a TCP {@link org.eclipse.jetty.io.SocketChannelEndPoint} for the associated
 * {@code HttpConnection}.</p>
 */
package org.eclipse.jetty.quic.common;

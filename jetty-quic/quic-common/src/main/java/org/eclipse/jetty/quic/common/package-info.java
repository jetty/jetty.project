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
 * that receives and sends bytes from its underlying datagram {@link org.eclipse.jetty.io.EndPoint}.</p>
 * <p>A {@link org.eclipse.jetty.quic.common.QuicConnection} manages many {@link org.eclipse.jetty.quic.common.QuicSession}s,
 * one for each QUIC connection ID.</p>
 * <p>A {@link org.eclipse.jetty.quic.common.QuicSession} manages many QUIC streams, identified by a
 * stream ID and represented by an {@link org.eclipse.jetty.io.EndPoint} subclass, namely
 * {@link org.eclipse.jetty.quic.common.QuicStreamEndPoint}.</p>
 * <p>The {@link org.eclipse.jetty.io.Connection} associated with each {@link org.eclipse.jetty.quic.common.QuicStreamEndPoint}
 * parses the bytes received on that QUIC stream, and generates the bytes to send on that QUIC stream.</p>
 * <p>For example, on the server side, the layout of the components in case of HTTP/1.1 could be the following:</p>
 * <pre>
 * CLIENT  |  SERVER
 *
 * clientA                                                     ServerQuicSessionA
 *         \                                                 /
 *           DatagramChannelEndPoint -- ServerQuicConnection
 *         /                                                 \
 * clientB                                                     ServerQuicSessionB -- QuicStreamEndPointB1 -- HttpConnection
 * </pre>
 * <p>The {@code DatagramChannelEndPoint} receives UDP datagrams from clients.</p>
 * <p>{@code ServerQuicConnection} processes the incoming datagram bytes creating a {@code ServerQuicSession} for every
 * QUIC connection ID sent by the clients.</p>
 * <p>{@code clientB} has created a single QUIC stream to send a single HTTP/1.1 request, which results in
 * {@code ServerQuicSessionB} to create a single {@code QuicStreamEndPointB1} with its associated {@code HttpConnection}.</p>
 * <p>Note that the path {@code DatagramChannelEndPoint - ServerQuicConnection - ServerQuicSessionB - QuicStreamEndPointB1}
 * behaves exactly like a TCP {@link org.eclipse.jetty.io.SocketChannelEndPoint} for the associated
 * {@code HttpConnection}.</p>
 */
package org.eclipse.jetty.quic.common;

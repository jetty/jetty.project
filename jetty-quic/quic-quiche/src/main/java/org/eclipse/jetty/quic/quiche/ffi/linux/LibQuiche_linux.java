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

package org.eclipse.jetty.quic.quiche.ffi.linux;

import java.nio.ByteBuffer;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import org.eclipse.jetty.quic.quiche.ffi.LibQuiche;
import org.eclipse.jetty.quic.quiche.ffi.size_t;
import org.eclipse.jetty.quic.quiche.ffi.ssize_t;
import org.eclipse.jetty.quic.quiche.ffi.timespec;

public interface LibQuiche_linux extends LibQuiche
{
    // load the native lib
    LibQuiche INSTANCE = Native.load("quiche", LibQuiche_linux.class);

    // Creates a new client-side connection.
    quiche_conn quiche_connect(String server_name, byte[] scid, size_t scid_len, netinet_linux.sockaddr to, size_t to_len, quiche_config config);

    // Creates a new server-side connection.
    quiche_conn quiche_accept(byte[] scid, size_t scid_len, byte[] odcid, size_t odcid_len, netinet_linux.sockaddr from, size_t from_len, quiche_config config);

    @Structure.FieldOrder({"to", "to_len", "at"})
    class quiche_send_info extends Structure
    {
        public netinet_linux.sockaddr_storage to;
        public size_t to_len;
        public timespec at;
    }

    // Writes a single QUIC packet to be sent to the peer.
    ssize_t quiche_conn_send(quiche_conn conn, ByteBuffer out, size_t out_len, quiche_send_info out_info);

    @Structure.FieldOrder({"from", "from_len"})
    class quiche_recv_info extends Structure
    {
        public netinet_linux.sockaddr.ByReference from;
        public size_t from_len;
    }

    // Processes QUIC packets received from the peer.
    ssize_t quiche_conn_recv(quiche_conn conn, ByteBuffer buf, size_t buf_len, quiche_recv_info info);
}

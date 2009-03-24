// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.ajp;

import org.eclipse.jetty.io.BufferCache;

/**
 * 
 */
public class Ajp13Packet
{

    public final static int MAX_PACKET_SIZE=(8*1024);
    public final static int HDR_SIZE=4;

    // Used in writing response...
    public final static int DATA_HDR_SIZE=7;
    public final static int MAX_DATA_SIZE=MAX_PACKET_SIZE-DATA_HDR_SIZE;

    public final static String
    // Server -> Container
            FORWARD_REQUEST="FORWARD REQUEST",
            SHUTDOWN="SHUTDOWN",
            PING_REQUEST="PING REQUEST", // Obsolete
            CPING_REQUEST="CPING REQUEST",

            // Server <- Container
            SEND_BODY_CHUNK="SEND BODY CHUNK", SEND_HEADERS="SEND HEADERS", END_RESPONSE="END RESPONSE",
            GET_BODY_CHUNK="GET BODY CHUNK",
            CPONG_REPLY="CPONG REPLY";

    public final static int FORWARD_REQUEST_ORDINAL=2, SHUTDOWN_ORDINAL=7,
            PING_REQUEST_ORDINAL=8, // Obsolete
            CPING_REQUEST_ORDINAL=10, SEND_BODY_CHUNK_ORDINAL=3, SEND_HEADERS_ORDINAL=4, END_RESPONSE_ORDINAL=5, GET_BODY_CHUNK_ORDINAL=6,
            CPONG_REPLY_ORDINAL=9;

    public final static BufferCache CACHE=new BufferCache();

    static
    {
        CACHE.add(FORWARD_REQUEST,FORWARD_REQUEST_ORDINAL);
        CACHE.add(SHUTDOWN,SHUTDOWN_ORDINAL);
        CACHE.add(PING_REQUEST,PING_REQUEST_ORDINAL); // Obsolete
        CACHE.add(CPING_REQUEST,CPING_REQUEST_ORDINAL);
        CACHE.add(SEND_BODY_CHUNK,SEND_BODY_CHUNK_ORDINAL);
        CACHE.add(SEND_HEADERS,SEND_HEADERS_ORDINAL);
        CACHE.add(END_RESPONSE,END_RESPONSE_ORDINAL);
        CACHE.add(GET_BODY_CHUNK,GET_BODY_CHUNK_ORDINAL);
        CACHE.add(CPONG_REPLY,CPONG_REPLY_ORDINAL);
    }

}

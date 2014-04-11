//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache;

/* ------------------------------------------------------------------------------- */
/** 
 * 
 * 
 */
public class HttpMethods
{
    public final static String GET= "GET",
        POST= "POST",
        HEAD= "HEAD",
        PUT= "PUT",
        OPTIONS= "OPTIONS",
        DELETE= "DELETE",
        TRACE= "TRACE",
        CONNECT= "CONNECT",
        MOVE= "MOVE";

    public final static int GET_ORDINAL= 1,
        POST_ORDINAL= 2,
        HEAD_ORDINAL= 3,
        PUT_ORDINAL= 4,
        OPTIONS_ORDINAL= 5,
        DELETE_ORDINAL= 6,
        TRACE_ORDINAL= 7,
        CONNECT_ORDINAL= 8,
        MOVE_ORDINAL= 9;

    public final static BufferCache CACHE= new BufferCache();

    public final static Buffer 
        GET_BUFFER= CACHE.add(GET, GET_ORDINAL),
        POST_BUFFER= CACHE.add(POST, POST_ORDINAL),
        HEAD_BUFFER= CACHE.add(HEAD, HEAD_ORDINAL),
        PUT_BUFFER= CACHE.add(PUT, PUT_ORDINAL),
        OPTIONS_BUFFER= CACHE.add(OPTIONS, OPTIONS_ORDINAL),
        DELETE_BUFFER= CACHE.add(DELETE, DELETE_ORDINAL),
        TRACE_BUFFER= CACHE.add(TRACE, TRACE_ORDINAL),
        CONNECT_BUFFER= CACHE.add(CONNECT, CONNECT_ORDINAL),
        MOVE_BUFFER= CACHE.add(MOVE, MOVE_ORDINAL);

}

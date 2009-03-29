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

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache;

/**
 * 
 */
public class Ajp13PacketMethods
{

    // TODO - this can probably be replaced by HttpMethods or at least an
    // extension of it.
    // It is probably most efficient if "GET" ends up as the same instance

    public final static String OPTIONS="OPTIONS", GET="GET", HEAD="HEAD", POST="POST", PUT="PUT", DELETE="DELETE", TRACE="TRACE", PROPFIND="PROPFIND",
            PROPPATCH="PROPPATCH", MKCOL="MKCOL", COPY="COPY", MOVE="MOVE", LOCK="LOCK", UNLOCK="UNLOCK", ACL="ACL", REPORT="REPORT",
            VERSION_CONTROL="VERSION-CONTROL", CHECKIN="CHECKIN", CHECKOUT="CHECKOUT", UNCHCKOUT="UNCHECKOUT", SEARCH="SEARCH", MKWORKSPACE="MKWORKSPACE",
            UPDATE="UPDATE", LABEL="LABEL", MERGE="MERGE", BASELINE_CONTROL="BASELINE-CONTROL", MKACTIVITY="MKACTIVITY";

    public final static int OPTIONS_ORDINAL=1, GET_ORDINAL=2, HEAD_ORDINAL=3, POST__ORDINAL=4, PUT_ORDINAL=5, DELETE_ORDINAL=6, TRACE_ORDINAL=7,
            PROPFIND_ORDINAL=8, PROPPATCH_ORDINAL=9, MKCOL_ORDINAL=10, COPY_ORDINAL=11, MOVE_ORDINAL=12, LOCK_ORDINAL=13, UNLOCK_ORDINAL=14, ACL_ORDINAL=15,
            REPORT_ORDINAL=16, VERSION_CONTROL_ORDINAL=17, CHECKIN_ORDINAL=18, CHECKOUT_ORDINAL=19, UNCHCKOUT_ORDINAL=20, SEARCH_ORDINAL=21,
            MKWORKSPACE_ORDINAL=22, UPDATE_ORDINAL=23, LABEL_ORDINAL=24, MERGE_ORDINAL=25, BASELINE_CONTROL_ORDINAL=26, MKACTIVITY_ORDINAL=27;

    public final static BufferCache CACHE=new BufferCache();

    public final static Buffer 
        OPTIONS_BUFFER=CACHE.add(OPTIONS,OPTIONS_ORDINAL), 
        GET_BUFFER=CACHE.add(GET,GET_ORDINAL), 
        HEAD_BUFFER=CACHE.add(HEAD, HEAD_ORDINAL), 
        POST__BUFFER=CACHE.add(POST,POST__ORDINAL), 
        PUT_BUFFER=CACHE.add(PUT,PUT_ORDINAL), 
        DELETE_BUFFER=CACHE.add(DELETE,DELETE_ORDINAL),
        TRACE_BUFFER=CACHE.add(TRACE,TRACE_ORDINAL), 
        PROPFIND_BUFFER=CACHE.add(PROPFIND,PROPFIND_ORDINAL), 
        PROPPATCH_BUFFER=CACHE.add(PROPPATCH, PROPPATCH_ORDINAL), 
        MKCOL_BUFFER=CACHE.add(MKCOL,MKCOL_ORDINAL), 
        COPY_BUFFER=CACHE.add(COPY,COPY_ORDINAL), 
        MOVE_BUFFER=CACHE.add(MOVE,MOVE_ORDINAL), 
        LOCK_BUFFER=CACHE.add(LOCK,LOCK_ORDINAL), 
        UNLOCK_BUFFER=CACHE.add(UNLOCK,UNLOCK_ORDINAL), 
        ACL_BUFFER=CACHE.add(ACL,ACL_ORDINAL), 
        REPORT_BUFFER=CACHE.add(REPORT,REPORT_ORDINAL), 
        VERSION_CONTROL_BUFFER=CACHE.add(VERSION_CONTROL,VERSION_CONTROL_ORDINAL),
        CHECKIN_BUFFER=CACHE.add(CHECKIN,CHECKIN_ORDINAL), 
        CHECKOUT_BUFFER=CACHE.add(CHECKOUT,CHECKOUT_ORDINAL), 
        UNCHCKOUT_BUFFER=CACHE.add(UNCHCKOUT,UNCHCKOUT_ORDINAL), 
        SEARCH_BUFFER=CACHE.add(SEARCH,SEARCH_ORDINAL), 
        MKWORKSPACE_BUFFER=CACHE.add(MKWORKSPACE,MKWORKSPACE_ORDINAL),
        UPDATE_BUFFER=CACHE.add(UPDATE,UPDATE_ORDINAL), 
        LABEL_BUFFER=CACHE.add(LABEL,LABEL_ORDINAL), 
        MERGE_BUFFER=CACHE.add(MERGE,MERGE_ORDINAL),
        BASELINE_CONTROL_BUFFER=CACHE.add(BASELINE_CONTROL,BASELINE_CONTROL_ORDINAL), 
        MKACTIVITY_BUFFER=CACHE.add(MKACTIVITY,MKACTIVITY_ORDINAL);
}

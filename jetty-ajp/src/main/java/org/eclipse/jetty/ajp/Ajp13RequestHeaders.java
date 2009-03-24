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
 * XXX Should this implement the Buffer interface?
 * 
 * 
 */
public class Ajp13RequestHeaders extends BufferCache
{

    public final static int MAGIC=0x1234;

    public final static String ACCEPT="accept", ACCEPT_CHARSET="accept-charset", ACCEPT_ENCODING="accept-encoding", ACCEPT_LANGUAGE="accept-language",
            AUTHORIZATION="authorization", CONNECTION="connection", CONTENT_TYPE="content-type", CONTENT_LENGTH="content-length", COOKIE="cookie",
            COOKIE2="cookie2", HOST="host", PRAGMA="pragma", REFERER="referer", USER_AGENT="user-agent";

    public final static int ACCEPT_ORDINAL=1, ACCEPT_CHARSET_ORDINAL=2, ACCEPT_ENCODING_ORDINAL=3, ACCEPT_LANGUAGE_ORDINAL=4, AUTHORIZATION_ORDINAL=5,
            CONNECTION_ORDINAL=6, CONTENT_TYPE_ORDINAL=7, CONTENT_LENGTH_ORDINAL=8, COOKIE_ORDINAL=9, COOKIE2_ORDINAL=10, HOST_ORDINAL=11, PRAGMA_ORDINAL=12,
            REFERER_ORDINAL=13, USER_AGENT_ORDINAL=14;

    public final static BufferCache CACHE=new BufferCache();

    public final static Buffer ACCEPT_BUFFER=CACHE.add(ACCEPT,ACCEPT_ORDINAL), ACCEPT_CHARSET_BUFFER=CACHE.add(ACCEPT_CHARSET,ACCEPT_CHARSET_ORDINAL),
            ACCEPT_ENCODING_BUFFER=CACHE.add(ACCEPT_ENCODING,ACCEPT_ENCODING_ORDINAL), ACCEPT_LANGUAGE_BUFFER=CACHE
                    .add(ACCEPT_LANGUAGE,ACCEPT_LANGUAGE_ORDINAL), AUTHORIZATION_BUFFER=CACHE.add(AUTHORIZATION,AUTHORIZATION_ORDINAL), CONNECTION_BUFFER=CACHE
                    .add(CONNECTION,CONNECTION_ORDINAL), CONTENT_TYPE_BUFFER=CACHE.add(CONTENT_TYPE,CONTENT_TYPE_ORDINAL), CONTENT_LENGTH_BUFFER=CACHE.add(
                    CONTENT_LENGTH,CONTENT_LENGTH_ORDINAL), COOKIE_BUFFER=CACHE.add(COOKIE,COOKIE_ORDINAL), COOKIE2_BUFFER=CACHE.add(COOKIE2,COOKIE2_ORDINAL),
            HOST_BUFFER=CACHE.add(HOST,HOST_ORDINAL), PRAGMA_BUFFER=CACHE.add(PRAGMA,PRAGMA_ORDINAL), REFERER_BUFFER=CACHE.add(REFERER,REFERER_ORDINAL),
            USER_AGENT_BUFFER=CACHE.add(USER_AGENT,USER_AGENT_ORDINAL);

    public final static byte 
            CONTEXT_ATTR=1, // Legacy
            SERVLET_PATH_ATTR=2, // Legacy
            REMOTE_USER_ATTR=3, 
            AUTH_TYPE_ATTR=4, 
            QUERY_STRING_ATTR=5, 
            JVM_ROUTE_ATTR=6, 
            SSL_CERT_ATTR=7, 
            SSL_CIPHER_ATTR=8,
            SSL_SESSION_ATTR=9,
            REQUEST_ATTR=10, 
            SSL_KEYSIZE_ATTR=11, 
            SECRET_ATTR=12, 
            STORED_METHOD_ATTR=13;

}

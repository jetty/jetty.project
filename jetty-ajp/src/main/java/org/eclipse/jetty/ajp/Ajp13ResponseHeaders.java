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
public class Ajp13ResponseHeaders extends BufferCache
{

    public final static int MAGIC=0xab00;

    public final static String CONTENT_TYPE="Content-Type", CONTENT_LANGUAGE="Content-Language", CONTENT_LENGTH="Content-Length", DATE="Date",
            LAST_MODIFIED="Last-Modified", LOCATION="Location", SET_COOKIE="Set-Cookie", SET_COOKIE2="Set-Cookie2", SERVLET_ENGINE="Servlet-Engine",
            STATUS="Status", WWW_AUTHENTICATE="WWW-Authenticate";

    public final static int CONTENT_TYPE_ORDINAL=1, CONTENT_LANGUAGE_ORDINAL=2, CONTENT_LENGTH_ORDINAL=3, DATE_ORDINAL=4, LAST_MODIFIED_ORDINAL=5,
            LOCATION_ORDINAL=6, SET_COOKIE_ORDINAL=7, SET_COOKIE2_ORDINAL=8, SERVLET_ENGINE_ORDINAL=9, STATUS_ORDINAL=10, WWW_AUTHENTICATE_ORDINAL=11;

    public final static BufferCache CACHE=new BufferCache();

    public final static Buffer CONTENT_TYPE_BUFFER=CACHE.add(CONTENT_TYPE,CONTENT_TYPE_ORDINAL), CONTENT_LANGUAGE_BUFFER=CACHE.add(CONTENT_LANGUAGE,
            CONTENT_LANGUAGE_ORDINAL), CONTENT_LENGTH_BUFFER=CACHE.add(CONTENT_LENGTH,CONTENT_LENGTH_ORDINAL), DATE_BUFFER=CACHE.add(DATE,DATE_ORDINAL),
            LAST_MODIFIED_BUFFER=CACHE.add(LAST_MODIFIED,LAST_MODIFIED_ORDINAL), LOCATION_BUFFER=CACHE.add(LOCATION,LOCATION_ORDINAL), SET_COOKIE_BUFFER=CACHE
                    .add(SET_COOKIE,SET_COOKIE_ORDINAL), SET_COOKIE2_BUFFER=CACHE.add(SET_COOKIE2,SET_COOKIE2_ORDINAL), SERVLET_ENGINE_BUFFER=CACHE.add(
                    SERVLET_ENGINE,SERVLET_ENGINE_ORDINAL), STATUS_BUFFER=CACHE.add(STATUS,STATUS_ORDINAL), WWW_AUTHENTICATE_BUFFER=CACHE.add(WWW_AUTHENTICATE,
                    WWW_AUTHENTICATE_ORDINAL);

}

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
 */
public class HttpHeaders extends BufferCache
{
    /* ------------------------------------------------------------ */
    /** General Fields.
     */
    public final static String 
        CONNECTION= "Connection",
        CACHE_CONTROL= "Cache-Control",
        DATE= "Date",
        PRAGMA= "Pragma",
        PROXY_CONNECTION = "Proxy-Connection",
        TRAILER= "Trailer",
        TRANSFER_ENCODING= "Transfer-Encoding",
        UPGRADE= "Upgrade",
        VIA= "Via",
        WARNING= "Warning",
        NEGOTIATE= "Negotiate";

    /* ------------------------------------------------------------ */
    /** Entity Fields.
     */
    public final static String ALLOW= "Allow",
        CONTENT_ENCODING= "Content-Encoding",
        CONTENT_LANGUAGE= "Content-Language",
        CONTENT_LENGTH= "Content-Length",
        CONTENT_LOCATION= "Content-Location",
        CONTENT_MD5= "Content-MD5",
        CONTENT_RANGE= "Content-Range",
        CONTENT_TYPE= "Content-Type",
        EXPIRES= "Expires",
        LAST_MODIFIED= "Last-Modified";

    /* ------------------------------------------------------------ */
    /** Request Fields.
     */
    public final static String ACCEPT= "Accept",
        ACCEPT_CHARSET= "Accept-Charset",
        ACCEPT_ENCODING= "Accept-Encoding",
        ACCEPT_LANGUAGE= "Accept-Language",
        AUTHORIZATION= "Authorization",
        EXPECT= "Expect",
        FORWARDED= "Forwarded",
        FROM= "From",
        HOST= "Host",
        IF_MATCH= "If-Match",
        IF_MODIFIED_SINCE= "If-Modified-Since",
        IF_NONE_MATCH= "If-None-Match",
        IF_RANGE= "If-Range",
        IF_UNMODIFIED_SINCE= "If-Unmodified-Since",
        KEEP_ALIVE= "Keep-Alive",
        MAX_FORWARDS= "Max-Forwards",
        PROXY_AUTHORIZATION= "Proxy-Authorization",
        RANGE= "Range",
        REQUEST_RANGE= "Request-Range",
        REFERER= "Referer",
        TE= "TE",
        USER_AGENT= "User-Agent",
        X_FORWARDED_FOR= "X-Forwarded-For",
        X_FORWARDED_PROTO= "X-Forwarded-Proto",
        X_FORWARDED_SERVER= "X-Forwarded-Server",
        X_FORWARDED_HOST= "X-Forwarded-Host";

    /* ------------------------------------------------------------ */
    /** Response Fields.
     */
    public final static String ACCEPT_RANGES= "Accept-Ranges",
        AGE= "Age",
        ETAG= "ETag",
        LOCATION= "Location",
        PROXY_AUTHENTICATE= "Proxy-Authenticate",
        RETRY_AFTER= "Retry-After",
        SERVER= "Server",
        SERVLET_ENGINE= "Servlet-Engine",
        VARY= "Vary",
        WWW_AUTHENTICATE= "WWW-Authenticate";

    /* ------------------------------------------------------------ */
    /** Other Fields.
     */
    public final static String COOKIE= "Cookie",
        SET_COOKIE= "Set-Cookie",
        SET_COOKIE2= "Set-Cookie2",
        MIME_VERSION= "MIME-Version",
        IDENTITY= "identity";

    public final static int CONNECTION_ORDINAL= 1,
        DATE_ORDINAL= 2,
        PRAGMA_ORDINAL= 3,
        TRAILER_ORDINAL= 4,
        TRANSFER_ENCODING_ORDINAL= 5,
        UPGRADE_ORDINAL= 6,
        VIA_ORDINAL= 7,
        WARNING_ORDINAL= 8,
        ALLOW_ORDINAL= 9,
        CONTENT_ENCODING_ORDINAL= 10,
        CONTENT_LANGUAGE_ORDINAL= 11,
        CONTENT_LENGTH_ORDINAL= 12,
        CONTENT_LOCATION_ORDINAL= 13,
        CONTENT_MD5_ORDINAL= 14,
        CONTENT_RANGE_ORDINAL= 15,
        CONTENT_TYPE_ORDINAL= 16,
        EXPIRES_ORDINAL= 17,
        LAST_MODIFIED_ORDINAL= 18,
        ACCEPT_ORDINAL= 19,
        ACCEPT_CHARSET_ORDINAL= 20,
        ACCEPT_ENCODING_ORDINAL= 21,
        ACCEPT_LANGUAGE_ORDINAL= 22,
        AUTHORIZATION_ORDINAL= 23,
        EXPECT_ORDINAL= 24,
        FORWARDED_ORDINAL= 25,
        FROM_ORDINAL= 26,
        HOST_ORDINAL= 27,
        IF_MATCH_ORDINAL= 28,
        IF_MODIFIED_SINCE_ORDINAL= 29,
        IF_NONE_MATCH_ORDINAL= 30,
        IF_RANGE_ORDINAL= 31,
        IF_UNMODIFIED_SINCE_ORDINAL= 32,
        KEEP_ALIVE_ORDINAL= 33,
        MAX_FORWARDS_ORDINAL= 34,
        PROXY_AUTHORIZATION_ORDINAL= 35,
        RANGE_ORDINAL= 36,
        REQUEST_RANGE_ORDINAL= 37,
        REFERER_ORDINAL= 38,
        TE_ORDINAL= 39,
        USER_AGENT_ORDINAL= 40,
        X_FORWARDED_FOR_ORDINAL= 41,
        ACCEPT_RANGES_ORDINAL= 42,
        AGE_ORDINAL= 43,
        ETAG_ORDINAL= 44,
        LOCATION_ORDINAL= 45,
        PROXY_AUTHENTICATE_ORDINAL= 46,
        RETRY_AFTER_ORDINAL= 47,
        SERVER_ORDINAL= 48,
        SERVLET_ENGINE_ORDINAL= 49,
        VARY_ORDINAL= 50,
        WWW_AUTHENTICATE_ORDINAL= 51,
        COOKIE_ORDINAL= 52,
        SET_COOKIE_ORDINAL= 53,
        SET_COOKIE2_ORDINAL= 54,
        MIME_VERSION_ORDINAL= 55,
        IDENTITY_ORDINAL= 56,
        CACHE_CONTROL_ORDINAL=57,
        PROXY_CONNECTION_ORDINAL=58,
        X_FORWARDED_PROTO_ORDINAL=59,
        X_FORWARDED_SERVER_ORDINAL=60,
        X_FORWARDED_HOST_ORDINAL=61;

    public final static HttpHeaders CACHE= new HttpHeaders();
    
    public final static Buffer
        HOST_BUFFER=CACHE.add(HOST,HOST_ORDINAL),
        ACCEPT_BUFFER=CACHE.add(ACCEPT,ACCEPT_ORDINAL),
        ACCEPT_CHARSET_BUFFER=CACHE.add(ACCEPT_CHARSET,ACCEPT_CHARSET_ORDINAL),
        ACCEPT_ENCODING_BUFFER=CACHE.add(ACCEPT_ENCODING,ACCEPT_ENCODING_ORDINAL),
        ACCEPT_LANGUAGE_BUFFER=CACHE.add(ACCEPT_LANGUAGE,ACCEPT_LANGUAGE_ORDINAL),
        
        CONTENT_LENGTH_BUFFER=CACHE.add(CONTENT_LENGTH,CONTENT_LENGTH_ORDINAL),
        CONNECTION_BUFFER=CACHE.add(CONNECTION,CONNECTION_ORDINAL),
        CACHE_CONTROL_BUFFER=CACHE.add(CACHE_CONTROL,CACHE_CONTROL_ORDINAL),
        DATE_BUFFER=CACHE.add(DATE,DATE_ORDINAL),
        PRAGMA_BUFFER=CACHE.add(PRAGMA,PRAGMA_ORDINAL),
        TRAILER_BUFFER=CACHE.add(TRAILER,TRAILER_ORDINAL),
        TRANSFER_ENCODING_BUFFER=CACHE.add(TRANSFER_ENCODING,TRANSFER_ENCODING_ORDINAL),
        UPGRADE_BUFFER=CACHE.add(UPGRADE,UPGRADE_ORDINAL),
        VIA_BUFFER=CACHE.add(VIA,VIA_ORDINAL),
        WARNING_BUFFER=CACHE.add(WARNING,WARNING_ORDINAL),
        ALLOW_BUFFER=CACHE.add(ALLOW,ALLOW_ORDINAL),
        CONTENT_ENCODING_BUFFER=CACHE.add(CONTENT_ENCODING,CONTENT_ENCODING_ORDINAL),
        CONTENT_LANGUAGE_BUFFER=CACHE.add(CONTENT_LANGUAGE,CONTENT_LANGUAGE_ORDINAL),
        CONTENT_LOCATION_BUFFER=CACHE.add(CONTENT_LOCATION,CONTENT_LOCATION_ORDINAL),
        CONTENT_MD5_BUFFER=CACHE.add(CONTENT_MD5,CONTENT_MD5_ORDINAL),
        CONTENT_RANGE_BUFFER=CACHE.add(CONTENT_RANGE,CONTENT_RANGE_ORDINAL),
        CONTENT_TYPE_BUFFER=CACHE.add(CONTENT_TYPE,CONTENT_TYPE_ORDINAL),
        EXPIRES_BUFFER=CACHE.add(EXPIRES,EXPIRES_ORDINAL),
        LAST_MODIFIED_BUFFER=CACHE.add(LAST_MODIFIED,LAST_MODIFIED_ORDINAL),
        AUTHORIZATION_BUFFER=CACHE.add(AUTHORIZATION,AUTHORIZATION_ORDINAL),
        EXPECT_BUFFER=CACHE.add(EXPECT,EXPECT_ORDINAL),
        FORWARDED_BUFFER=CACHE.add(FORWARDED,FORWARDED_ORDINAL),
        FROM_BUFFER=CACHE.add(FROM,FROM_ORDINAL),
        IF_MATCH_BUFFER=CACHE.add(IF_MATCH,IF_MATCH_ORDINAL),
        IF_MODIFIED_SINCE_BUFFER=CACHE.add(IF_MODIFIED_SINCE,IF_MODIFIED_SINCE_ORDINAL),
        IF_NONE_MATCH_BUFFER=CACHE.add(IF_NONE_MATCH,IF_NONE_MATCH_ORDINAL),
        IF_RANGE_BUFFER=CACHE.add(IF_RANGE,IF_RANGE_ORDINAL),
        IF_UNMODIFIED_SINCE_BUFFER=CACHE.add(IF_UNMODIFIED_SINCE,IF_UNMODIFIED_SINCE_ORDINAL),
        KEEP_ALIVE_BUFFER=CACHE.add(KEEP_ALIVE,KEEP_ALIVE_ORDINAL),
        MAX_FORWARDS_BUFFER=CACHE.add(MAX_FORWARDS,MAX_FORWARDS_ORDINAL),
        PROXY_AUTHORIZATION_BUFFER=CACHE.add(PROXY_AUTHORIZATION,PROXY_AUTHORIZATION_ORDINAL),
        RANGE_BUFFER=CACHE.add(RANGE,RANGE_ORDINAL),
        REQUEST_RANGE_BUFFER=CACHE.add(REQUEST_RANGE,REQUEST_RANGE_ORDINAL),
        REFERER_BUFFER=CACHE.add(REFERER,REFERER_ORDINAL),
        TE_BUFFER=CACHE.add(TE,TE_ORDINAL),
        USER_AGENT_BUFFER=CACHE.add(USER_AGENT,USER_AGENT_ORDINAL),
        X_FORWARDED_FOR_BUFFER=CACHE.add(X_FORWARDED_FOR,X_FORWARDED_FOR_ORDINAL),
        X_FORWARDED_PROTO_BUFFER=CACHE.add(X_FORWARDED_PROTO,X_FORWARDED_PROTO_ORDINAL),
        X_FORWARDED_SERVER_BUFFER=CACHE.add(X_FORWARDED_SERVER,X_FORWARDED_SERVER_ORDINAL),
        X_FORWARDED_HOST_BUFFER=CACHE.add(X_FORWARDED_HOST,X_FORWARDED_HOST_ORDINAL),
        ACCEPT_RANGES_BUFFER=CACHE.add(ACCEPT_RANGES,ACCEPT_RANGES_ORDINAL),
        AGE_BUFFER=CACHE.add(AGE,AGE_ORDINAL),
        ETAG_BUFFER=CACHE.add(ETAG,ETAG_ORDINAL),
        LOCATION_BUFFER=CACHE.add(LOCATION,LOCATION_ORDINAL),
        PROXY_AUTHENTICATE_BUFFER=CACHE.add(PROXY_AUTHENTICATE,PROXY_AUTHENTICATE_ORDINAL),
        RETRY_AFTER_BUFFER=CACHE.add(RETRY_AFTER,RETRY_AFTER_ORDINAL),
        SERVER_BUFFER=CACHE.add(SERVER,SERVER_ORDINAL),
        SERVLET_ENGINE_BUFFER=CACHE.add(SERVLET_ENGINE,SERVLET_ENGINE_ORDINAL),
        VARY_BUFFER=CACHE.add(VARY,VARY_ORDINAL),
        WWW_AUTHENTICATE_BUFFER=CACHE.add(WWW_AUTHENTICATE,WWW_AUTHENTICATE_ORDINAL),
        COOKIE_BUFFER=CACHE.add(COOKIE,COOKIE_ORDINAL),
        SET_COOKIE_BUFFER=CACHE.add(SET_COOKIE,SET_COOKIE_ORDINAL),
        SET_COOKIE2_BUFFER=CACHE.add(SET_COOKIE2,SET_COOKIE2_ORDINAL),
        MIME_VERSION_BUFFER=CACHE.add(MIME_VERSION,MIME_VERSION_ORDINAL),
        IDENTITY_BUFFER=CACHE.add(IDENTITY,IDENTITY_ORDINAL),
        PROXY_CONNECTION_BUFFER=CACHE.add(PROXY_CONNECTION,PROXY_CONNECTION_ORDINAL);
    
    
}

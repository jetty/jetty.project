//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.util.EnumSet;

/**
 * HTTP compliance modes:
 * <dl>
 * <dt>RFC7230</dt><dd>(default) Compliance with RFC7230</dd>
 * <dt>RFC2616</dt><dd>Wrapped/Continued headers and HTTP/0.9 supported</dd>
 * <dt>LEGACY</dt><dd>(aka STRICT) Adherence to Servlet Specification requirement for
 * exact case of header names, bypassing the header caches, which are case insensitive.</dd>
 * </dl>
 */
public enum HttpCompliance 
{ 
    LEGACY(EnumSet.noneOf(HttpRFC.class)), 
    RFC2616(EnumSet.complementOf(EnumSet.of(
            HttpRFC.RFC7230_3_2_4_WS_AFTER_FIELD_NAME,
            HttpRFC.RFC7230_3_2_4_NO_FOLDING,
            HttpRFC.RFC7230_A2_NO_HTTP_9))), 
    RFC7230(EnumSet.allOf(HttpRFC.class)), 
    ;
    
    final EnumSet<HttpRFC> _sections;
    
    HttpCompliance(EnumSet<HttpRFC> sections)
    {
        _sections = sections;
    }
    
    public EnumSet<HttpRFC> sections()
    {
        return _sections;
    }

    public EnumSet<HttpRFC> excluding(EnumSet<HttpRFC> exclusions)
    {
        EnumSet<HttpRFC> sections =  EnumSet.copyOf(_sections);
        sections.removeAll(exclusions);
        return sections;
    }
    
}
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

/**
 */
public enum HttpComplianceSection 
{     
    USE_CASE_INSENSITIVE_FIELD_VALUE_CACHE("","Use case insensitive field value cache"),
    RFC7230_3_1_1_METHOD_CASE_SENSITIVE("https://tools.ietf.org/html/rfc7230#section-3.1.1","Method is case-sensitive"),
    RFC7230_3_2_FIELD_COLON("https://tools.ietf.org/html/rfc7230#section-3.2","Fields must have a Colon"), 
    RFC7230_3_2_CASE_INSENSITIVE_FIELD_NAME("https://tools.ietf.org/html/rfc7230#section-3.2","Field name is case-insensitive"),    
    RFC7230_3_2_4_NO_WS_AFTER_FIELD_NAME("https://tools.ietf.org/html/rfc7230#section-3.2.4","Whitespace not allowed after field name"),    
    RFC7230_3_2_4_NO_FOLDING("https://tools.ietf.org/html/rfc7230#section-3.2.4","No line Folding"),
    RFC7230_A2_NO_HTTP_9("https://tools.ietf.org/html/rfc7230#appendix-A.2","No HTTP/0.9"),
    ; 

    HttpComplianceSection(String url,String description)
    {

    }
}
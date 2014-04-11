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

public class HttpCookie
{
    private final String _name;        
    private final String _value;     
    private final String _comment;                               
    private final String _domain;    
    private final int _maxAge;  
    private final String _path;       
    private final boolean _secure;   
    private final int _version;   
    private final boolean _httpOnly;

    /* ------------------------------------------------------------ */
    public HttpCookie(String name, String value)
    {
        super();
        _name = name;
        _value = value;
        _comment = null;
        _domain = null;
        _httpOnly = false;
        _maxAge = -1;
        _path = null;
        _secure = false;
        _version = 0;
    }
    
    /* ------------------------------------------------------------ */
    public HttpCookie(String name, String value, String domain, String path)
    {
        super();
        _name = name;
        _value = value;
        _comment = null;
        _domain = domain;
        _httpOnly = false;
        _maxAge = -1;
        _path = path;
        _secure = false;
        _version = 0;
        
    }
    
    /* ------------------------------------------------------------ */
    public HttpCookie(String name, String value, int maxAge)
    {
        super();
        _name = name;
        _value = value;
        _comment = null;
        _domain = null;
        _httpOnly = false;
        _maxAge = maxAge;
        _path = null;
        _secure = false;
        _version = 0;
    }
    
    /* ------------------------------------------------------------ */
    public HttpCookie(String name, String value, String domain, String path, int maxAge, boolean httpOnly, boolean secure)
    {
        super();
        _comment = null;
        _domain = domain;
        _httpOnly = httpOnly;
        _maxAge = maxAge;
        _name = name;
        _path = path;
        _secure = secure;
        _value = value;
        _version = 0;
    }
    
    /* ------------------------------------------------------------ */
    public HttpCookie(String name, String value, String domain, String path, int maxAge, boolean httpOnly, boolean secure, String comment, int version)
    {
        super();
        _comment = comment;
        _domain = domain;
        _httpOnly = httpOnly;
        _maxAge = maxAge;
        _name = name;
        _path = path;
        _secure = secure;
        _value = value;
        _version = version;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the name.
     * @return the name
     */
    public String getName()
    {
        return _name;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the value.
     * @return the value
     */
    public String getValue()
    {
        return _value;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the comment.
     * @return the comment
     */
    public String getComment()
    {
        return _comment;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the domain.
     * @return the domain
     */
    public String getDomain()
    {
        return _domain;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the maxAge.
     * @return the maxAge
     */
    public int getMaxAge()
    {
        return _maxAge;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the path.
     * @return the path
     */
    public String getPath()
    {
        return _path;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the secure.
     * @return the secure
     */
    public boolean isSecure()
    {
        return _secure;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the version.
     * @return the version
     */
    public int getVersion()
    {
        return _version;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the isHttpOnly.
     * @return the isHttpOnly
     */
    public boolean isHttpOnly()
    {
        return _httpOnly;
    }
    
    
}

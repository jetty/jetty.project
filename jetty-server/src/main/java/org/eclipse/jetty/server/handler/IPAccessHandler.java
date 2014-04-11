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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.IPAddressMap;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * IP Access Handler
 * <p>
 * Controls access to the wrapped handler by the real remote IP. Control is provided
 * by white/black lists that include both internet addresses and URIs. This handler
 * uses the real internet address of the connection, not one reported in the forwarded
 * for headers, as this cannot be as easily forged. 
 * <p>
 * Typically, the black/white lists will be used in one of three modes:
 * <ul>
 * <li>Blocking a few specific IPs/URLs by specifying several black list entries.
 * <li>Allowing only some specific IPs/URLs by specifying several white lists entries.
 * <li>Allowing a general range of IPs/URLs by specifying several general white list
 * entries, that are then further refined by several specific black list exceptions
 * </ul>
 * <p>
 * An empty white list is treated as match all. If there is at least one entry in
 * the white list, then a request must match a white list entry. Black list entries
 * are always applied, so that even if an entry matches the white list, a black list 
 * entry will override it.
 * <p>
 * Internet addresses may be specified as absolute address or as a combination of 
 * four octet wildcard specifications (a.b.c.d) that are defined as follows.
 * </p>
 * <pre>
 * nnn - an absolute value (0-255)
 * mmm-nnn - an inclusive range of absolute values, 
 *           with following shorthand notations:
 *           nnn- => nnn-255
 *           -nnn => 0-nnn
 *           -    => 0-255
 * a,b,... - a list of wildcard specifications
 * </pre>
 * <p>
 * Internet address specification is separated from the URI pattern using the "|" (pipe)
 * character. URI patterns follow the servlet specification for simple * prefix and 
 * suffix wild cards (e.g. /, /foo, /foo/bar, /foo/bar/*, *.baz).
 * <p>
 * Earlier versions of the handler used internet address prefix wildcard specification
 * to define a range of the internet addresses (e.g. 127., 10.10., 172.16.1.).
 * They also used the first "/" character of the URI pattern to separate it from the 
 * internet address. Both of these features have been deprecated in the current version. 
 * <p>
 * Examples of the entry specifications are:
 * <ul>
 * <li>10.10.1.2 - all requests from IP 10.10.1.2
 * <li>10.10.1.2|/foo/bar - all requests from IP 10.10.1.2 to URI /foo/bar
 * <li>10.10.1.2|/foo/* - all requests from IP 10.10.1.2 to URIs starting with /foo/
 * <li>10.10.1.2|*.html - all requests from IP 10.10.1.2 to URIs ending with .html
 * <li>10.10.0-255.0-255 - all requests from IPs within 10.10.0.0/16 subnet
 * <li>10.10.0-.-255|/foo/bar - all requests from IPs within 10.10.0.0/16 subnet to URI /foo/bar
 * <li>10.10.0-3,1,3,7,15|/foo/* - all requests from IPs addresses with last octet equal
 *                                  to 1,3,7,15 in subnet 10.10.0.0/22 to URIs starting with /foo/
 * </ul>
 * <p>
 * Earlier versions of the handler used internet address prefix wildcard specification
 * to define a range of the internet addresses (e.g. 127., 10.10., 172.16.1.).
 * They also used the first "/" character of the URI pattern to separate it from the 
 * internet address. Both of these features have been deprecated in the current version. 
 */
public class IPAccessHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(IPAccessHandler.class);

    IPAddressMap<PathMap> _white = new IPAddressMap<PathMap>();
    IPAddressMap<PathMap> _black = new IPAddressMap<PathMap>();

    /* ------------------------------------------------------------ */
    /**
     * Creates new handler object
     */
    public IPAccessHandler()
    {
        super();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Creates new handler object and initializes white- and black-list
     * 
     * @param white array of whitelist entries
     * @param black array of blacklist entries
     */
    public IPAccessHandler(String[] white, String []black)
    {
        super();
        
        if (white != null && white.length > 0)
            setWhite(white);
        if (black != null && black.length > 0)
            setBlack(black);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add a whitelist entry to an existing handler configuration
     * 
     * @param entry new whitelist entry
     */
    public void addWhite(String entry)
    {
        add(entry, _white);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add a blacklist entry to an existing handler configuration
     * 
     * @param entry new blacklist entry
     */
    public void addBlack(String entry)
    {
        add(entry, _black);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Re-initialize the whitelist of existing handler object
     * 
     * @param entries array of whitelist entries
     */
    public void setWhite(String[] entries)
    {
        set(entries, _white);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Re-initialize the blacklist of existing handler object
     * 
     * @param entries array of blacklist entries
     */
    public void setBlack(String[] entries)
    {
        set(entries, _black);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Checks the incoming request against the whitelist and blacklist
     * 
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Get the real remote IP (not the one set by the forwarded headers (which may be forged))
        AbstractHttpConnection connection = baseRequest.getConnection();
        if (connection!=null)
        {
            EndPoint endp=connection.getEndPoint();
            if (endp!=null)
            {
                String addr = endp.getRemoteAddr();
                if (addr!=null && !isAddrUriAllowed(addr,baseRequest.getPathInfo()))
                {
                    response.sendError(HttpStatus.FORBIDDEN_403);
                    baseRequest.setHandled(true);
                    return;
                }
            }
        }
        
        getHandler().handle(target,baseRequest, request, response);
    }
    

    /* ------------------------------------------------------------ */
    /**
     * Helper method to parse the new entry and add it to 
     * the specified address pattern map.
     * 
     * @param entry new entry
     * @param patternMap target address pattern map
     */
    protected void add(String entry, IPAddressMap<PathMap> patternMap)
    {
        if (entry != null && entry.length() > 0)
        {
            boolean deprecated = false;
            int idx;
            if (entry.indexOf('|') > 0 )
            {
                idx = entry.indexOf('|');
            }
            else
            {
                idx = entry.indexOf('/');
                deprecated = (idx >= 0);
            }
            
            String addr = idx > 0 ? entry.substring(0,idx) : entry;        
            String path = idx > 0 ? entry.substring(idx) : "/*";
            
            if (addr.endsWith("."))
                deprecated = true;
            if (path!=null && (path.startsWith("|") || path.startsWith("/*.")))
                path=path.substring(1);
           
            PathMap pathMap = patternMap.get(addr);
            if (pathMap == null)
            {
                pathMap = new PathMap(true);
                patternMap.put(addr,pathMap);
            }
            if (path != null && !"".equals(path))
                pathMap.put(path,path);
            
            if (deprecated)
                LOG.debug(toString() +" - deprecated specification syntax: "+entry);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Helper method to process a list of new entries and replace 
     * the content of the specified address pattern map
     * 
     * @param entries new entries
     * @param patternMap target address pattern map
     */
    protected void set(String[] entries,  IPAddressMap<PathMap> patternMap)
    {
        patternMap.clear();
        
        if (entries != null && entries.length > 0)
        {
            for (String addrPath:entries)
            {
                add(addrPath, patternMap);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Check if specified request is allowed by current IPAccess rules.
     * 
     * @param addr internet address
     * @param path context path
     * @return true if request is allowed
     *
     */
    protected boolean isAddrUriAllowed(String addr, String path)
    {
        if (_white.size()>0)
        {
            boolean match = false;
            
            Object whiteObj = _white.getLazyMatches(addr);
            if (whiteObj != null) 
            {
                List whiteList = (whiteObj instanceof List) ? (List)whiteObj : Collections.singletonList(whiteObj);

                for (Object entry: whiteList)
                {
                    PathMap pathMap = ((Map.Entry<String,PathMap>)entry).getValue();
                    if (match = (pathMap!=null && (pathMap.size()==0 || pathMap.match(path)!=null)))
                        break;
                }
            }
            
            if (!match)
                return false;
        }

        if (_black.size() > 0)
        {
            Object blackObj = _black.getLazyMatches(addr);
            if (blackObj != null) 
            {
                List blackList = (blackObj instanceof List) ? (List)blackObj : Collections.singletonList(blackObj);
    
                for (Object entry: blackList)
                {
                    PathMap pathMap = ((Map.Entry<String,PathMap>)entry).getValue();
                    if (pathMap!=null && (pathMap.size()==0 || pathMap.match(path)!=null))
                        return false;
                }
            }
        }
        
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Dump the white- and black-list configurations when started
     * 
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStart()
     */
    @Override
    protected void doStart()
        throws Exception
    {
        super.doStart();
        
        if (LOG.isDebugEnabled())
        {
            System.err.println(dump());
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Dump the handler configuration
     */
    public String dump()
    {
        StringBuilder buf = new StringBuilder();
        
        buf.append(toString());
        buf.append(" WHITELIST:\n");
        dump(buf, _white);
        buf.append(toString());
        buf.append(" BLACKLIST:\n");
        dump(buf, _black);
        
        return buf.toString();
    }    
    
    /* ------------------------------------------------------------ */
    /**
     * Dump a pattern map into a StringBuilder buffer
     * 
     * @param buf buffer
     * @param patternMap pattern map to dump
     */
    protected void dump(StringBuilder buf, IPAddressMap<PathMap> patternMap)
    {
        for (String addr: patternMap.keySet())
        {
            for (Object path: ((PathMap)patternMap.get(addr)).values())
            {
                buf.append("# ");
                buf.append(addr);
                buf.append("|");
                buf.append(path);
                buf.append("\n");
            }       
        }
    }
 }

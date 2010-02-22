// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;


/**
 * IP Access Handler
 * <p>
 * Control access to the wrapped handler by the real remote IP.
 * The real IP of the connection is used (not the IP reported in the forwarded for headers),
 * as this cannot be as easily forged. 
 * <p>
 * Control is provided by white/black lists of both internet addresses and URIs.
 * Internet addresses may be absolute (eg 10.1.2.3) or a prefix pattern (eg 10.1.3. ).
 * URI patterns follow the servlet specification for simple prefix and suffix wild cards.
 * <p>
 * An empty white list is treated as match all. If there is at least one entry in the
 * white list, then a request must match a white list entry. Black list entries are always
 * appied, so that even if an entry matches the white list, a black list entry will override.
 * </p>
 * <p>
 * Examples of match specifications are:
 * <ul>
 * <li>10.1.2.3 - all requests from IP 10.1.2.3
 * <li>10.1.2.3/foo/bar - all requests from IP 10.1.2.3 to URI /foo/bar
 * <li>10.1.2.3/foo/* - all requests from IP 10.1.2.3 to URIs starting with /foo/
 * <li>10.1.2.3/*.html - all requests from IP 10.1.2.3 to URIs ending with .html
 * <li>10.1. - all requests from IPs starting with 10.1.
 * <li>10.1./foo/bar - all requests from IPs starting with 10.1. to URI /foo/bar
 * <li>10.1./foo/* - all requests from IPs starting with 10.1. to URIs starting with /foo/
 * </ul>
 * <p>
 * Typically, the black/white lists will be used in one of three modes:
 * <nl>
 * <li>Blocking a few specific IPs/URLs by specifying several black list entries.
 * <li>Allowing only some specific IPs/URLs by specifying several white lists entries.
 * <li>Allowing a general range of IPs/URLs by specifying serveral general white list
 * entries, that are then further refined by several specific black list exceptions
 * </ul>
 * 
 */
public class IPAccessHandler extends HandlerWrapper
{
    Map<String,PathMap> _whiteAddr = new HashMap<String, PathMap>();
    List<String> _whitePattern = new CopyOnWriteArrayList<String>();
    Map<String,PathMap> _blackAddr = new HashMap<String, PathMap>();
    List<String> _blackPattern = new CopyOnWriteArrayList<String>();
    
    /**
     */
    public IPAccessHandler()
    {
    }
    
    public void addBlack(String addrPath)
    {
        add(addrPath, _blackAddr, _blackPattern);
    }
    
    public void addWhite(String addrPath)
    {
        add(addrPath, _whiteAddr, _whitePattern);
    }
    
    public void setBlack(String[] addrPaths)
    {
        set(addrPaths, _blackAddr, _blackPattern);
    }
    
    public void setWhite(String[] addrPaths)
    {
        set(addrPaths, _whiteAddr, _whitePattern);
    }
    
    /**
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Get the real remote IP (not the one set by the forwarded headers (which may be forged))
        HttpConnection connection = baseRequest.getConnection();
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

    protected void add(String addrPath, Map<String,PathMap> addrMap, List<String> patternList)
    {
        int idx = addrPath.indexOf('/');
        String addr = idx > 0 ? addrPath.substring(0,idx) : addrPath;        
        String path = idx > 0 ? addrPath.substring(idx) : null;
        if (path!=null && path.length()>1 && path.charAt(1)=='*')
            path=path.substring(1);
        System.err.println("addr="+addr+" path="+path);

        PathMap map = addrMap.get(addr);
        if (map==null)
        {
            map = new PathMap(true);
            addrMap.put(addr,map);
            if (addr.endsWith("."))
                patternList.add(addr);
        }

        if (path != null)
            map.put(path,path);
    }

    protected void set(String[] addrPaths, Map<String,PathMap> addrMap, List<String> patternList)
    {
        addrMap.clear();
        patternList.clear();
        for (String addrPath:addrPaths)
            add(addrPath, addrMap, patternList);
    }
    
    protected boolean isAddrUriAllowed(String addr, String path)
    {
        if (_whiteAddr.size()>0)
        {
            PathMap white=_whiteAddr.get(addr);
            
            if (white==null || (white.size()>0 && white.match(path)==null))
            {
                boolean match=false;
                for (String pattern:_whitePattern)
                {
                    if (addr.startsWith(pattern))
                    {
                        white=_whiteAddr.get(pattern);
                        if (white!=null && white.size()>0 && white.match(path)!=null)
                        {
                            match=true;
                            break;
                        }
                    }
                }
                if (!match)
                    return false;
            }
        }

        PathMap black=_blackAddr.get(addr);
        if (black!=null && (black.size()==0 || black.match(path)!=null))
            return false;

        for (String pattern:_blackPattern)
        {
            if (addr.startsWith(pattern))
            {
                black=_blackAddr.get(pattern);
                if (black!=null && black.match(path)!=null)
                    return false;
                break;
            }
        }
        
        return true;
    }

}

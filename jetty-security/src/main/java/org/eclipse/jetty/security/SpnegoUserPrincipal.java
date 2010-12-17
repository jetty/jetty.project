package org.eclipse.jetty.security;

import java.security.Principal;

import org.eclipse.jetty.http.security.B64Code;

public class SpnegoUserPrincipal implements Principal
{
    private final String _name;
    private byte[] _token;
    private String _encodedToken;
    
    public SpnegoUserPrincipal( String name, String encodedToken )
    {
        _name = name;
        _encodedToken = encodedToken;
    }
    
    public SpnegoUserPrincipal( String name, byte[] token )
    {
        _name = name;
        _token = token;
    }
    
    public String getName()
    {
        return _name;
    }

    public byte[] getToken()
    {
        if ( _token == null )
        {
            _token = B64Code.decode(_encodedToken);
        }
        return _token;
    }
    
    public String getEncodedToken()
    {
        if ( _encodedToken == null )
        {
            _encodedToken = new String(B64Code.encode(_token,true));
        }
        return _encodedToken;
    }   
}

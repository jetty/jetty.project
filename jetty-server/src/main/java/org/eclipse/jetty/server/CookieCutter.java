//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** Cookie parser
 * <p>Optimized stateful cookie parser.  Cookies fields are added with the
 * {@link #addCookieField(String)} method and parsed on the next subsequent
 * call to {@link #getCookies()}.
 * If the added fields are identical to those last added (as strings), then the 
 * cookies are not re parsed.
 *
 */
public class CookieCutter
{
    private static final Logger LOG = Log.getLogger(CookieCutter.class);

    private final CookieCompliance _compliance;
    private Cookie[] _cookies;
    private Cookie[] _lastCookies;
    private final List<String> _fieldList = new ArrayList<>();
    private int _fields;
    
    public CookieCutter()
    {  
        this(CookieCompliance.RFC6265);
    }
    
    public CookieCutter(CookieCompliance compliance)
    {  
        _compliance = compliance;
    }
    
    public Cookie[] getCookies()
    {
        if (_cookies!=null)
            return _cookies;
        
        if (_lastCookies!=null && _fields==_fieldList.size())
            _cookies=_lastCookies;
        else
            parseFields();
        _lastCookies=_cookies;
        return _cookies;
    }
    
    public void setCookies(Cookie[] cookies)
    {
        _cookies=cookies;
        _lastCookies=null;
        _fieldList.clear();
        _fields=0;
    }
    
    public void reset()
    {
        _cookies=null;
        _fields=0;
    }
    
    public void addCookieField(String f)
    {
        if (f==null)
            return;
        f=f.trim();
        if (f.length()==0)
            return;
            
        if (_fieldList.size()>_fields)
        {
            if (f.equals(_fieldList.get(_fields)))
            {
                _fields++;
                return;
            }
            
            while (_fieldList.size()>_fields)
                _fieldList.remove(_fields);
        }
        _cookies=null;
        _lastCookies=null;
        _fieldList.add(_fields++,f);
    }
    
    
    private void parseFields()
    {
        _lastCookies=null;
        _cookies=null;
        
        List<Cookie> cookies = new ArrayList<>();

        // delete excess fields
        while (_fieldList.size()>_fields)
            _fieldList.remove(_fields);
        
        // For each cookie field
        for (String hdr : _fieldList)
        {
            FieldParser fieldParser = new FieldParser(hdr);
            cookies.addAll(fieldParser.parse());
        }

        _cookies = cookies.toArray(new Cookie[cookies.size()]);
        _lastCookies=_cookies;
    }

    private class FieldParser
    {
        private int _i = 0;
        private int _length;
        private int _tokenstart = -1;
        private int _tokenend = -1;
        private int _version = 0;
        private boolean _escaped = false;
        private String _field;
        private String _name;
        private String _value;
        private String _unquoted;
        private Cookie _cookie;
        private List<Cookie> _cookies = new ArrayList<>();
        private FieldParserState _state = FieldParserState.PARSING_NAME;

        FieldParser(String field)
        {
            _field = field;
            _length = field.length();
        }

        public List<Cookie> parse()
        {
            while (_i < _length || _state != FieldParserState.PARSING_FINISHED)
            {
                switch (_state)
                {
                    case PARSING_NAME:
                    case PARSING_VALUE: parseToken(); break;
                    case PARSING_QUOTED_NAME:
                    case PARSING_QUOTED_VALUE: parseQuoted(); break;
                    case PARSING_FINISHED: parsingFinished(); break;
                }
            }
            parsingFinished();
            return _cookies;
        }

        private void parseToken()
        {
            for (; _i < _length; _i++)
            {
                char c = _field.charAt(_i);

                if (isEscapeCharacter(c))
                {
                    _escaped = true;
                }
                else if (isBlankCharacter(c))
                {
                    // skip leading blank characters
                }
                else if (isSeparator(c))
                {
                    extractToken();
                    _state = FieldParserState.PARSING_FINISHED;
                    _i++;
                    return;
                }
                else if (parsingNameAndIsEqualCharacter(c))
                {
                    extractName();
                    _state = FieldParserState.PARSING_VALUE;
                }
                else if (isStartingQuoteCharacter(c))
                {
                    switchStateToQuoted();
                    _i++;
                    return;
                }
                else
                {
                    _escaped = false;
                    if (_tokenstart<0)
                        _tokenstart = _i;
                    _tokenend= _i;
                }
            }
            extractToken();
            _state = FieldParserState.PARSING_FINISHED;
        }

        private boolean isEscapeCharacter(char c)
        {
            return !_escaped && c == '\\';
        }

        private boolean isBlankCharacter(char c)
        {
            return !_escaped && c == ' ' || c == '\t';
        }

        private boolean isSeparator(char c)
        {
            return !_escaped && c == ';' || (c == ',' && _compliance == CookieCompliance.RFC2965);
        }

        private boolean parsingNameAndIsEqualCharacter(char c)
        {
            return !_escaped && c == '=' && _state == FieldParserState.PARSING_NAME;
        }

        private boolean isStartingQuoteCharacter(char c)
        {
            return !_escaped && c == '"' && _tokenstart < 0;
        }

        private void extractToken()
        {
            if (_state == FieldParserState.PARSING_NAME)
                extractName();
            else if (_state == FieldParserState.PARSING_VALUE)
                extractValue();
            else
                throw new IllegalStateException(_state.toString());

        }

        private void extractName()
        {
            if (_unquoted != null)
            {
                _name = _unquoted;
                _unquoted = null;
            }
            else if(_tokenstart>=0 && _tokenend>=0)
            {
                _name = _field.substring(_tokenstart, _tokenend+1);
            }
            _tokenstart = -1;
        }

        private void extractValue()
        {
            if (_unquoted != null)
            {
                _value = _unquoted;
                _unquoted = null;
            }
            else if (_tokenstart >= 0 && _tokenend >= 0)
                _value = _field.substring(_tokenstart, _tokenend + 1);
            else
                _value = "";

            _tokenstart = -1;
        }

        private void parseQuoted()
        {
            StringBuilder output = new StringBuilder();
            StringBuilder rawOutput = new StringBuilder("\"");
            boolean closingQuoteFound = false;

            for (; _i < _length; _i++)
            {
                char c = _field.charAt(_i);

                if (isEscapeCharacter(c))
                {
                    _escaped = true;
                }
                else if (isStartingQuoteCharacter(c))
                {
                    closingQuoteFound = true;
                    rawOutput.append(c);
                }
                else if (closingQuoteFound && isSeparator(c))
                {
                    switchStateFromQuoted();
                    _unquoted = output.toString();
                    return;
                }
                else if (closingQuoteFound && isBlankCharacter(c))
                {
                    rawOutput.append(c);
                }
                else
                {
                    _escaped = false;
                    closingQuoteFound = false;
                    output.append(c);
                    rawOutput.append(c);
                }
            }
            // The algorithm reaches this state only if the input contains syntax errors.
            // See CookieCutter_LenientTest.
            if (_escaped)
                rawOutput.append('\\');
            _unquoted = trimQuotes(rawOutput.toString());
            switchStateFromQuoted();
        }

        private String trimQuotes(String str)
        {
            if (str.startsWith("\"") && str.endsWith("\""))
                return str.substring(1, str.length() - 1);
            return str;
        }

        private void switchStateToQuoted()
        {
            if (_state == FieldParserState.PARSING_NAME)
                _state = FieldParserState.PARSING_QUOTED_NAME;
            else if (_state == FieldParserState.PARSING_VALUE)
                _state = FieldParserState.PARSING_QUOTED_VALUE;
            else
                throw new IllegalStateException(_state.toString());
        }

        private void switchStateFromQuoted()
        {
            if (_state == FieldParserState.PARSING_QUOTED_NAME)
                _state = FieldParserState.PARSING_NAME;
            else if (_state == FieldParserState.PARSING_QUOTED_VALUE)
                _state = FieldParserState.PARSING_VALUE;
            else
                throw new IllegalStateException(_state.toString());
        }

        private void parsingFinished()
        {
            if (_name!=null && _value!=null)
                assembleCookie();
            resetState();
        }

        private void assembleCookie()
        {
            if (_name.startsWith("$") && _compliance == CookieCompliance.RFC2965)
                handleRFC2965();
            else
                appendCookie();
        }

        private void handleRFC2965()
        {
            String lowercaseName = _name.toLowerCase(Locale.ENGLISH);

            if ("$path".equals(lowercaseName))
            {
                if (_cookie!=null)
                    _cookie.setPath(_value);
            }
            else if ("$domain".equals(lowercaseName))
            {
                if (_cookie!=null)
                    _cookie.setDomain(_value);
            }
            else if ("$port".equals(lowercaseName))
            {
                if (_cookie!=null)
                    _cookie.setComment("$port="+_value);
            }
            else if ("$version".equals(lowercaseName))
            {
                _version = Integer.parseInt(_value);
            }
        }

        private void appendCookie()
        {
            try
            {
                _cookie = new Cookie(_name, _value);
                if (_version > 0)
                    _cookie.setVersion(_version);
                _cookies.add(_cookie);
            }
            catch (Exception e)
            {
                LOG.debug(e);
            }
        }

        private void resetState()
        {
            _name = null;
            _value = null;
            _unquoted = null;
            _tokenstart = -1;
            _tokenend = -1;
            _state = FieldParserState.PARSING_NAME;
        }
    }

    private enum FieldParserState
    {
        PARSING_NAME, PARSING_VALUE, PARSING_QUOTED_NAME, PARSING_QUOTED_VALUE, PARSING_FINISHED
    }
    
}

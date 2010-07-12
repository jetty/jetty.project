//========================================================================
//Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package com.acme;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.StringTokenizer;

import javax.servlet.jsp.JspContext;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.JspFragment;
import javax.servlet.jsp.tagext.SimpleTagSupport;

public class Date2Tag extends SimpleTagSupport
{
    String format;
    
    public void setFormat(String value) {
        this.format = value;
    }

    public void doTag() throws JspException, IOException {
        String formatted = 
            new SimpleDateFormat("long".equals(format)?"EEE 'the' d:MMM:yyyy":"d:MM:yy")
            .format(new Date());
        StringTokenizer tok = new StringTokenizer(formatted,":");
        JspContext context = getJspContext();
        context.setAttribute("day", tok.nextToken() );
        context.setAttribute("month", tok.nextToken() );
        context.setAttribute("year", tok.nextToken() );

        JspFragment fragment = getJspBody();
        fragment.invoke(null);
    }
}


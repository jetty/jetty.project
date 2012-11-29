package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ValidUrlRule extends Rule
{
    String _code = "400";
    String _reason = "Illegal Url";
    
    public ValidUrlRule()
    {
        _handling = true;
        _terminating = true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the response status code.
     * 
     * @param code
     *            response code
     */
    public void setCode(String code)
    {
        _code = code;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the reason for the response status code. Reasons will only reflect if the code value is greater or equal to 400.
     * 
     * @param reason
     */
    public void setReason(String reason)
    {
        _reason = reason;
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String uri = request.getRequestURI();

        for (int i = 0; i < uri.length(); ++i)
        {
            if (!isPrintableChar(uri.charAt(i)))
            {
                int code = Integer.parseInt(_code);

                // status code 400 and up are error codes so include a reason
                if (code >= 400)
                {
                    response.sendError(code,_reason);
                }
                else
                {
                    response.setStatus(code);
                }

                return target;
            }
        }

        return null;
    }

    protected boolean isPrintableChar(char c)
    {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        
        return (!Character.isISOControl(c)) && block != null && block != Character.UnicodeBlock.SPECIALS;
    }

    public String toString()
    {
        return super.toString() + "[" + _code + ":" + _reason + "]";
    }
}

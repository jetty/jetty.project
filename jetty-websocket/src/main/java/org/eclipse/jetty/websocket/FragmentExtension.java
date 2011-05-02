package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.Map;

public class FragmentExtension extends AbstractExtension
{
    private int _maxLength=-1;
    private int _fragments=1;
    
    public FragmentExtension()
    {
        super("fragment",0,0,0);
    }

    @Override
    public boolean init(Map<String, String> parameters)
    {
        if(super.init(parameters))
        {
            _maxLength=getInitParameter("maxLength",_maxLength);
            _fragments=getInitParameter("fragments",_fragments);
            return true;
        }
        return false;
    }

    @Override
    public void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        if (getConnection().isControl(opcode))
        {
            super.addFrame(flags,opcode,content,offset,length);
            return;
        }
        
        int fragments=1;
        
        while (_maxLength>0 && length>_maxLength)
        {
            fragments++;
            super.addFrame((byte)(flags&~getConnection().finMask()),opcode,content,offset,_maxLength);
            length-=_maxLength;
            offset+=_maxLength;
            opcode=getConnection().continuationOpcode();
        }
        
        while (fragments<_fragments)
        {
            int frag=length/2;
            fragments++;
            super.addFrame((byte)(flags&0x7),opcode,content,offset,frag);
            length-=frag;
            offset+=frag;
            opcode=getConnection().continuationOpcode();
        }

        super.addFrame((byte)(flags|getConnection().finMask()),opcode,content,offset,length);
    }
    
    
}

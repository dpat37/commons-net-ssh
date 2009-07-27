package org.apache.commons.net.ssh.connection;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.net.ssh.SSHException;
import org.apache.commons.net.ssh.transport.Transport;
import org.apache.commons.net.ssh.transport.TransportException;
import org.apache.commons.net.ssh.util.Buffer;
import org.apache.commons.net.ssh.util.Constants;

public interface Channel extends Closeable
{
    
    interface Direct extends Channel
    {
        void open() throws ConnectionException, TransportException;
    }
    
    interface Forwarded extends Channel
    {
        
        void confirm() throws TransportException;
        
        String getOriginatorIP();
        
        int getOriginatorPort();
        
        void reject(int reasonCode, String message) throws TransportException;
        
    }
    
    void close() throws TransportException, ConnectionException;
    
    int getID();
    
    InputStream getInputStream();
    
    int getLocalMaxPacketSize();
    
    int getLocalWinSize();
    
    OutputStream getOutputStream();
    
    int getRecipient();
    
    int getRemoteMaxPacketSize();
    
    int getRemoteWinSize();
    
    Transport getTransport();
    
    String getType();
    
    void handle(Constants.Message cmd, Buffer buf) throws ConnectionException, TransportException;
    
    boolean isOpen();
    
    void notifyError(SSHException exception);
    
    void sendEOF() throws TransportException;
    
}
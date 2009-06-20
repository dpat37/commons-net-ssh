package org.apache.commons.net.ssh.trans;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.PublicKey;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.net.ssh.FactoryManager;
import org.apache.commons.net.ssh.Random;
import org.apache.commons.net.ssh.SSHConstants;
import org.apache.commons.net.ssh.SSHException;
import org.apache.commons.net.ssh.Service;
import org.apache.commons.net.ssh.Session;
import org.apache.commons.net.ssh.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transport layer
 * 
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 * @author <a href="mailto:shikhar@schmizz.net">Shikhar Bhushan</a>
 */
public class Transport implements Session
{
    
    /*
     * This enum describes what 'phase' this SSH session is in, so we that we know which class to delegate message
     * handling to, can wait for some phase to be reached, etc.
     */
    private enum State
    {
        KEX, // delegate message handling to Kex
        KEX_DONE, // indicates kex done
        SERVICE_REQ, // a service has been requested
        SERVICE_OK, // indicates service request was successful
        SERVICE,
        RUNNING,
        ERROR, // indicates an error event in one of the threads
        STOPPED, //indicates this session has been stopped
    }
    
    private State state;
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final FactoryManager fm;
    
    final Random prng;
    
    private final KexHandler kex;
    
    final EncDec bin;
    
    /*
     * Outgoing data is put here. Since it is a SynchronousQueue, until outPump is interested in taking, putting an item
     * blocks.
     */
    private final SynchronousQueue<byte[]> outQ = new SynchronousQueue<byte[]>();
    
    /*
     * If an error occurs in one of the threads spawned by this class, it is set in this field.
     */
    private Exception exception;
    
    /* Lock object for session phase */
    private final Object stateLock = new Object();
    
    /* Lock object supporting correct encoding and queuing of packets */
    private final Object encodeLock = new Object();
    
    // private final ReentrantLock lock = new ReentrantLock();
    
    private InputStream input;
    private OutputStream output;
    
    /*
     * This thread reads data from the input stream, putting it into decodeBuffer. Packets are decoded and handled.
     */
    private final Thread inPump = new Thread() {
        @Override
        public void run()
        {
            while (!stopPumping)
                try
                {
                    bin.decode((byte) input.read());
                } catch (Exception e)
                {
                    log.error("Encountered error: {}", e.toString());
                    if (!stopPumping)
                        setError(e);
                }
            log.debug("Stopping");
        }
    };
    
    /*
     * This thread takes byte[] from outQ and sends it over the output stream for this session.
     */
    private final Thread outPump = new Thread() {
        @Override
        public void run()
        {
            while (!stopPumping)
                try
                {
                    output.write(outQ.take());
                    log.debug("Sent packet");
                } catch (Exception e)
                {
                    log.error("Encountered error: {}", e.toString());
                    if (!stopPumping)
                        setError(e);
                }
            log.debug("Stopping");
        }
    };
    
    /* true value tells inPump and outPump to stop */
    private volatile boolean stopPumping = false;
    
    private Service service; // active service e.g. ssh-userauth, ssh-connection
    
    boolean authed = false;
    
    /* Client version identification string */
    private String clientVersion;
    
    /* Server version identification string */
    private String serverVersion;
    
    public Transport(FactoryManager factoryManager)
    {
        super();
        
        fm = factoryManager;
        prng = factoryManager.getRandomFactory().create();
        bin = new EncDec(this);
        kex = new KexHandler(this);
        
        inPump.setName("inPump");
        inPump.setDaemon(true);
        outPump.setName("outPump");
        outPump.setDaemon(true);
    }
    
    /**
     * Create a new buffer for the specified SSH packet and reserve the needed space (5 bytes) for the packet header.
     * 
     * @param cmd
     *            the SSH command
     * @return a new buffer ready for write
     */
    public Buffer createBuffer(SSHConstants.Message cmd)
    {
        Buffer buffer = new Buffer();
        buffer.rpos(5);
        buffer.wpos(5);
        buffer.putByte(cmd.toByte());
        return buffer;
    }
    
    /**
     * Send a disconnect packet with the given reason and message, and close the session.
     * 
     * @param reason
     *            the reason code for this disconnect
     * @param msg
     *            the text message
     * @throws IOException
     *             if an error occured sending the packet
     */
    public void disconnect(int reason, String msg) throws IOException
    {
        log.debug("Sending SSH_MSG_DISCONNECT: reason=[{}], msg=[{}]", reason, msg);
        Buffer buffer = createBuffer(SSHConstants.Message.SSH_MSG_DISCONNECT);
        buffer.putInt(reason);
        buffer.putString(msg);
        buffer.putString("");
        writePacket(buffer);
        stop();
    }
    
    public String getClientVersion()
    {
        return clientVersion;
    }
    
    /**
     * Retrieve the factory manager
     * 
     * @return the factory manager for this session
     */
    public FactoryManager getFactoryManager()
    {
        return fm;
    }
    
    public Random getPRNG()
    {
        return prng;
    }
    
    public String getServerVersion()
    {
        return serverVersion;
    }
    
    public void init(InputStream input, OutputStream output) throws IOException
    {
        this.input = input;
        this.output = output;
        
        clientVersion = "SSH-2.0-" + fm.getVersion();
        log.info("Client version string: {}", clientVersion);
        output.write((clientVersion + "\r\n").getBytes());
        
        Buffer buf = new Buffer();
        while ((serverVersion = readIdentification(buf)) == null)
            buf.putByte((byte) input.read());
        log.info("Server version string: {}", serverVersion);
        
        setState(State.KEX);
        
        outPump.start();
        inPump.start();
        
        kex.init();
        
        try
        {
            waitFor(State.KEX_DONE);
        } catch (Exception e)
        {
            throw new IOException(e);
        }
    }
    
    //    public void requestService(ServiceHandler serviceHandler) throws Exception
    //    {
    //        this.serviceHandler = serviceHandler;
    //        setState(State.SERVICE_REQ);
    //        sendServiceRequest(serviceHandler.getServiceName());
    //        waitFor(State.SERVICE_OK);
    //        setState(State.SERVICE);
    //    }
    
    public boolean isAuthenticated()
    {
        // TODO Auto-generated method stub
        return false;
    }
    
    public boolean isRunning()
    {
        // super.isConnected() tells us about the socket, the state stuff tells us about SSH-specificities
        return !(state == State.ERROR || state == State.STOPPED);
    }
    
    public void setService(Service service) throws Exception
    {
        log.info("Setting active service to {}", service.getName());
        this.service = service;
        sendServiceRequest(service.getName());
        setState(State.SERVICE_REQ);
        waitFor(State.SERVICE_OK);
    }
    
    public boolean verifyHost(PublicKey key)
    {
        // TODO: verify host key!! -- PRIORITY
        return true;
    }
    
    /**
     * Encode the payload as an SSH packet and send it over the session.
     * 
     * @param payload
     * @throws IOException
     */
    public void writePacket(Buffer payload) throws IOException
    {
        /*
         * Synchronize all write requests as needed by the encoding algorithm and also queue the write request in this
         * synchronized block to ensure packets are sent in the correct order.
         */
        synchronized (encodeLock)
        {
            bin.encode(payload);
            byte[] data = payload.getCompactData();
            try
            {
                while (!outQ.offer(data, 1L, TimeUnit.SECONDS))
                    if (!outPump.isAlive())
                        throw new IOException("Output pumping thread is dead");
            } catch (InterruptedException e)
            {
                InterruptedIOException ioe = new InterruptedIOException();
                ioe.initCause(e);
                throw ioe;
            }
        }
    }
    
    private String readIdentification(Buffer buffer) throws IOException
    {
        String serverVersion;
        
        byte[] data = new byte[256];
        for (;;)
        {
            int rpos = buffer.rpos();
            int pos = 0;
            boolean needLf = false;
            for (;;)
            {
                if (buffer.available() == 0)
                {
                    // need more data, so undo reading and return null
                    buffer.rpos(rpos);
                    return null;
                }
                byte b = buffer.getByte();
                if (b == '\r')
                {
                    needLf = true;
                    continue;
                }
                if (b == '\n')
                    break;
                if (needLf)
                    throw new IllegalStateException("Incorrect identification: bad line ending");
                if (pos >= data.length)
                    throw new IllegalStateException("Incorrect identification: line too long");
                data[pos++] = b;
            }
            serverVersion = new String(data, 0, pos);
            if (serverVersion.startsWith("SSH-"))
                break;
            if (buffer.rpos() > 16 * 1024)
                throw new IllegalStateException("Incorrect identification: too many header lines");
        }
        
        if (!serverVersion.startsWith("SSH-2.0-"))
            throw new SSHException(SSHConstants.SSH_DISCONNECT_PROTOCOL_VERSION_NOT_SUPPORTED);
        
        return serverVersion;
    }
    
    private void sendServiceRequest(String serviceName) throws IOException
    {
        log.info("Sending SSH_MSG_SERVICE_REQUEST for {}", serviceName);
        Buffer buffer = createBuffer(SSHConstants.Message.SSH_MSG_SERVICE_REQUEST);
        buffer.putString(serviceName);
        writePacket(buffer);
    }
    
    /**
     * Used by inPump and outPump to notify of an error, since it would otherwise escape unnoticed
     * 
     * @param e
     *            Exception
     */
    private void setError(Exception e)
    {
        exception = e;
        log.debug("A pumping thread reported {}", e.toString());
        
        // Future TODO: notify open channels
        
        /*
         * will result in exception being thrown in any thread that was waiting for state change; see waitFor()
         */
        setState(State.ERROR);
        
        stop(); // stop inPump and outPump
    }
    
    /**
     * Block for specified state.
     * 
     * @param s
     *            State
     * @throws Exception
     *             in case of error event while waiting
     */
    private void waitFor(State s) throws Exception
    {
        synchronized (stateLock)
        {
            while (state != s && state != State.ERROR && state != State.STOPPED)
                try
                {
                    stateLock.wait(0);
                } catch (InterruptedException e)
                {
                    throw e;
                }
        }
        log.debug("Woke up to {}", state.toString());
        if (state != s)
            if (state == State.ERROR)
                throw exception;
            else if (state == State.STOPPED)
                throw new SSHException("Stopped");
    }
    
    void handle(Buffer packet) throws Exception
    {
        SSHConstants.Message cmd = packet.getCommand();
        log.debug("Received packet {}", cmd);
        switch (cmd)
        {
            case SSH_MSG_DISCONNECT:
            {
                int code = packet.getInt();
                String msg = packet.getString();
                log.info("Received SSH_MSG_DISCONNECT (reason={}, msg={})", code, msg);
                throw new SSHException(code, msg);
            }
            case SSH_MSG_UNIMPLEMENTED:
            {
                int code = packet.getInt();
                log.info("Received SSH_MSG_UNIMPLEMENTED #{}", code);
                break;
            }
            case SSH_MSG_DEBUG:
            {
                boolean display = packet.getBoolean();
                String msg = packet.getString();
                log.info("Received SSH_MSG_DEBUG (display={}) '{}'", display, msg);
                break;
            }
            case SSH_MSG_IGNORE:
            {
                log.info("Received SSH_MSG_IGNORE");
                break;
            }
            default:
            {
                switch (state)
                {
                    case KEX:
                    {
                        if (kex.handle(cmd, packet)) // key-exchange completed
                            setState(State.KEX_DONE);
                        break;
                    }
                    case SERVICE_REQ:
                    {
                        if (cmd != SSHConstants.Message.SSH_MSG_SERVICE_ACCEPT)
                        {
                            disconnect(SSHConstants.SSH_DISCONNECT_PROTOCOL_ERROR,
                                       "Protocol error: expected packet SSH_MSG_SERVICE_ACCEPT, got " + cmd);
                            return;
                        }
                        setState(State.SERVICE_OK);
                        break;
                    }
                    case SERVICE:
                    {
                        if (cmd != SSHConstants.Message.SSH_MSG_KEXINIT)
                            service.handle(cmd, packet);
                        else
                        {
                            /*
                             * Key re-exchange. Per RFC should be after every GB or 1 hour, whichever is sooner. ---
                             * TODO: ?initiate?
                             */
                            setState(State.KEX);
                            kex.handle(cmd, packet);
                        }
                        break;
                    }
                    case KEX_DONE:
                    case SERVICE_OK:
                        break;
                    default:
                        assert false;
                }
            }
        }
    }
    
    /**
     * Send an unimplemented packet. This packet should contain the sequence id of the unsupported packet.
     * 
     * @throws IOException
     *             if an error occured sending the packet
     */
    void sendNotImplemented(int num) throws IOException
    {
        Buffer buffer = createBuffer(SSHConstants.Message.SSH_MSG_UNIMPLEMENTED);
        buffer.putInt(num);
        writePacket(buffer);
    }
    
    void setAuthenticated(boolean authed)
    {
        this.authed = authed;
    }
    
    void setState(State newState)
    {
        log.debug("Changing state  [ {} -> {} ]", state, newState);
        synchronized (stateLock)
        {
            state = newState;
            stateLock.notifyAll();
        }
    }
    
    void stop()
    {
        // stop inPump and outPump
        stopPumping = true;
        while (inPump.isAlive() || outPump.isAlive())
            ;
        // will wakeup any thread that was waiting for phase change, see waitFor()
        setState(State.STOPPED);
        
        // can safely reset these refs
        input = null;
        output = null;
    }
    
}

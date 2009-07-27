/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.net.ssh.connection;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.net.ssh.SSHException;
import org.apache.commons.net.ssh.transport.Transport;
import org.apache.commons.net.ssh.transport.TransportException;
import org.apache.commons.net.ssh.util.Buffer;
import org.apache.commons.net.ssh.util.BufferUtils;
import org.apache.commons.net.ssh.util.Event;
import org.apache.commons.net.ssh.util.IOUtils;
import org.apache.commons.net.ssh.util.Constants.DisconnectReason;
import org.apache.commons.net.ssh.util.Constants.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 * @author <a href="mailto:shikhar@schmizz.net">Shikhar Bhushan</a>
 */
public abstract class AbstractChannel implements Channel
{
    
    protected final Logger log;
    
    protected final Transport trans;
    protected final ConnectionService conn;
    protected final int id;
    
    protected final LocalWindow lwin = new LocalWindow(this);
    protected final RemoteWindow rwin = new RemoteWindow(this);
    
    protected final Queue<Event<ConnectionException>> reqs = new LinkedList<Event<ConnectionException>>();
    
    protected final ReentrantLock lock = new ReentrantLock();
    protected final Event<ConnectionException> open;
    protected final Event<ConnectionException> close;
    
    protected int recipient;
    
    protected ChannelInputStream in = new ChannelInputStream(this, lwin);
    protected ChannelOutputStream out; // initialized in init()
    
    protected boolean eofSent;
    protected boolean eofGot;
    protected boolean closeReqd;
    
    protected AbstractChannel(ConnectionService conn)
    {
        this.conn = conn;
        this.trans = conn.getTransport();
        id = conn.nextID();
        log = LoggerFactory.getLogger("chan#" + id);
        lwin.init(conn.getWindowSize(), conn.getMaxPacketSize());
        open = newEvent("open");
        close = newEvent("close");
    }
    
    public void close() throws ConnectionException, TransportException
    {
        sendClose();
        close.await(conn.getTimeout());
    }
    
    public int getID()
    {
        return id;
    }
    
    public InputStream getInputStream()
    {
        return in;
    }
    
    public int getLocalMaxPacketSize()
    {
        return lwin.getMaxPacketSize();
    }
    
    public int getLocalWinSize()
    {
        return lwin.getSize();
    }
    
    public OutputStream getOutputStream()
    {
        return out;
    }
    
    public int getRecipient()
    {
        return recipient;
    }
    
    public int getRemoteMaxPacketSize()
    {
        return rwin.getMaxPacketSize();
    }
    
    public int getRemoteWinSize()
    {
        return rwin.getSize();
    }
    
    public Transport getTransport()
    {
        return trans;
    }
    
    public void handle(Message cmd, Buffer buf) throws ConnectionException, TransportException
    {
        switch (cmd)
        {
            case CHANNEL_WINDOW_ADJUST:
            {
                int howmuch = buf.getInt();
                log.info("Received window adjustment for {} bytes", howmuch);
                rwin.expand(howmuch);
                break;
            }
            case CHANNEL_DATA:
            {
                doWrite(buf, in);
                break;
            }
            case CHANNEL_EXTENDED_DATA:
            {
                handleExtendedData(buf.getInt(), buf);
                break;
            }
            case CHANNEL_REQUEST:
            {
                String reqType = buf.getString();
                buf.getBoolean(); // We don't ever reply to requests, so ignore this value
                log.info("Got request for `{}`", reqType);
                handleRequest(reqType, buf);
                break;
            }
            case CHANNEL_SUCCESS:
            {
                gotResponse(true);
                break;
            }
            case CHANNEL_FAILURE:
            {
                gotResponse(false);
                break;
            }
            case CHANNEL_EOF:
            {
                log.info("Got EOF");
                gotEOF();
                break;
            }
            case CHANNEL_CLOSE:
            {
                log.info("Got close");
                sendClose();
                close.set();
                closeStreams();
                conn.forget(this);
                break;
            }
            default:
            {
                gotUnknown(cmd, buf);
            }
        }
    }
    
    public void init(Buffer buf)
    {
        this.recipient = buf.getInt();
        rwin.init(buf.getInt(), buf.getInt());
        log.info("Initialized - {}", this);
        out = new ChannelOutputStream(this, rwin);
    }
    
    public synchronized boolean isOpen()
    {
        lock.lock();
        try {
            return open.isSet() && !close.isSet() && !closeReqd;
        } finally {
            lock.unlock();
        }
    }
    
    @SuppressWarnings("unchecked")
    public void notifyError(SSHException exception)
    {
        Event.Util.<ConnectionException> notifyError(exception, open, close);
        Event.Util.<ConnectionException> notifyError(exception, reqs);
    }
    
    public synchronized void sendEOF() throws TransportException
    {
        try {
            if (!closeReqd && !eofSent) {
                log.info("Sending EOF");
                trans.writePacket(newBuffer(Message.CHANNEL_EOF));
                if (eofGot)
                    sendClose();
            }
        } finally {
            eofSent = true;
        }
    }
    
    @Override
    public String toString()
    {
        return "< " + getType() + " channel: id=" + id + ", recipient=" + recipient + ", localWin=" + lwin
                + ", remoteWin=" + rwin + " >";
    }
    
    protected void closeStreams()
    {
        IOUtils.closeQuietly(in, out);
    }
    
    protected void doWrite(Buffer buf, ChannelInputStream stream) throws ConnectionException, TransportException
    {
        int len = buf.getInt();
        if (len < 0 || len > getLocalMaxPacketSize())
            throw new ConnectionException(DisconnectReason.PROTOCOL_ERROR, "Bad item length: " + len);
        // log.debug("Got data");
        if (log.isTraceEnabled())
            log.trace("IN: {}", BufferUtils.printHex(buf.array(), buf.rpos(), len));
        stream.receive(buf.array(), buf.rpos(), len);
    }
    
    protected synchronized void gotEOF() throws TransportException
    {
        eofGot = true;
        in.eof();
        if (eofSent)
            sendClose();
    }
    
    protected synchronized void gotResponse(boolean success) throws ConnectionException
    {
        Event<ConnectionException> event = reqs.poll();
        if (event != null) {
            if (success)
                event.set();
            else
                event.error("Request failed");
        } else
            throw new ConnectionException(DisconnectReason.PROTOCOL_ERROR,
                                          "Received response to channel request when none was requested");
    }
    
    protected void gotUnknown(Message cmd, Buffer buf) throws TransportException
    {
        trans.sendUnimplemented();
    }
    
    protected void handleExtendedData(int dataTypeCode, Buffer buf) throws ConnectionException, TransportException
    {
        throw new ConnectionException(DisconnectReason.PROTOCOL_ERROR, "Extended data not supported on " + getType()
                + " channel");
    }
    
    protected void handleRequest(String reqType, Buffer buf) throws ConnectionException, TransportException
    {
        trans.writePacket(new Buffer(Message.CHANNEL_FAILURE));
    }
    
    protected Buffer newBuffer(Message cmd)
    {
        return new Buffer(cmd).putInt(recipient);
    }
    
    protected Event<ConnectionException> newEvent(String name)
    {
        return new Event<ConnectionException>("chan#" + id + " / " + name, ConnectionException.chainer, lock);
    }
    
    protected synchronized Event<ConnectionException> sendChannelRequest(String reqType, boolean wantReply,
            Buffer reqSpecific) throws TransportException
    {
        log.info("Making channel request for `{}`", reqType);
        Buffer reqBuf = newBuffer(Message.CHANNEL_REQUEST).putString(reqType) //
                                                          .putBoolean(wantReply) //
                                                          .putBuffer(reqSpecific);
        trans.writePacket(reqBuf);
        Event<ConnectionException> event = null;
        if (wantReply) {
            event = newEvent("chanreq for " + reqType);
            reqs.add(event);
        }
        return event;
    }
    
    protected synchronized void sendClose() throws TransportException
    {
        try {
            if (!closeReqd) {
                log.info("Sending close");
                trans.writePacket(newBuffer(Message.CHANNEL_CLOSE));
            }
        } finally {
            closeReqd = true;
        }
    }
    
}
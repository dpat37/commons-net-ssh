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
package org.apache.commons.net.ssh.transport;

import java.net.InetAddress;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.net.ssh.ErrorNotifiable;
import org.apache.commons.net.ssh.Factory;
import org.apache.commons.net.ssh.HostKeyVerifier;
import org.apache.commons.net.ssh.PacketHandler;
import org.apache.commons.net.ssh.SSHException;
import org.apache.commons.net.ssh.SSHPacket;
import org.apache.commons.net.ssh.cipher.Cipher;
import org.apache.commons.net.ssh.compression.Compression;
import org.apache.commons.net.ssh.digest.Digest;
import org.apache.commons.net.ssh.kex.KeyExchange;
import org.apache.commons.net.ssh.mac.MAC;
import org.apache.commons.net.ssh.util.Event;
import org.apache.commons.net.ssh.util.SecurityUtils;
import org.apache.commons.net.ssh.util.Buffer.PlainBuffer;
import org.apache.commons.net.ssh.util.Constants.DisconnectReason;
import org.apache.commons.net.ssh.util.Constants.KeyType;
import org.apache.commons.net.ssh.util.Constants.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Algorithm negotiation and key exchange.
 */
final class KeyExchanger implements PacketHandler, ErrorNotifiable
{
    
    private static enum Expected
    {
        /** we have sent or are sending KEXINIT, and expect the server's KEXINIT */
        KEXINIT,
        /** we are expecting some followup data as part of the exchange */
        FOLLOWUP,
        /** we are expecting SSH_MSG_NEWKEYS */
        NEWKEYS,
    }
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final TransportProtocol transport;
    
    /**
     * {@link HostKeyVerifier#verify(InetAddress, PublicKey)} is invoked by {@link #verifyHost(PublicKey)} when we are
     * ready to verify the the server's host key.
     */
    private final Queue<HostKeyVerifier> hostVerifiers = new LinkedList<HostKeyVerifier>();
    
    private final AtomicBoolean kexOngoing = new AtomicBoolean();
    
    /** What we are expecting from the next packet */
    private Expected expected = Expected.KEXINIT;
    
    /** Instance of negotiated key exchange algorithm */
    private KeyExchange kex;
    
    /** Computed session ID */
    private byte[] sessionID;
    
    private Proposal clientProposal, serverProposal;
    private NegotiatedAlgorithms negotiatedAlgs;
    
    private final Event<TransportException> kexInitSent = new Event<TransportException>("kexinit sent",
            TransportException.chainer);
    
    private final Event<TransportException> done;
    
    KeyExchanger(TransportProtocol trans)
    {
        this.transport = trans;
        /*
         * Use TransportProtocol's writeLock, since TransportProtocol.write() may wait on this event and the lock should
         * be released while waiting.
         */
        this.done = new Event<TransportException>("kex done", TransportException.chainer, trans.getWriteLock());
    }
    
    /**
     * Add a callback for host key verification.
     * <p>
     * Any of the {@link HostKeyVerifier} implementations added this way can deem a host key to be acceptable, allowing
     * key exchange to successfuly complete. Otherwise, a {@link TransportException} will result during key exchange.
     * 
     * @param hkv
     *            object whose {@link HostKeyVerifier#verify} method will be invoked
     */
    synchronized void addHostKeyVerifier(HostKeyVerifier hkv)
    {
        hostVerifiers.add(hkv);
    }
    
    /**
     * Returns the session identifier computed during key exchange.
     * <p>
     * If the session has not yet been initialized via {@link #open}, it will be {@code null}.
     * 
     * @return session identifier as a byte array
     */
    byte[] getSessionID()
    {
        return Arrays.copyOf(sessionID, sessionID.length);
    }
    
    /**
     * Returns whether key exchange has been completed
     */
    boolean isKexDone()
    {
        return done.isSet();
    }
    
    /**
     * Returns whether key exchange is currently ongoing
     */
    boolean isKexOngoing()
    {
        return kexOngoing.get();
    }
    
    /**
     * Starts key exchange by sending a {@code SSH_MSG_KEXINIT} packet. Key exchange needs to be done once mandatorily
     * after initializing the {@link Transport} for it to be usable and may be initiated at any later point e.g. if
     * {@link Transport#getConfig() algorithms} have changed and should be renegotiated.
     * 
     * @param waitForDone
     *            whether should block till key exchange completed
     * @throws TransportException
     *             if there is an error during key exchange
     * @see {@link Transport#setTimeout} for setting timeout for kex
     */
    void startKex(boolean waitForDone) throws TransportException
    {
        if (!kexOngoing.getAndSet(true))
        {
            done.clear();
            sendKexInit();
        }
        if (waitForDone)
            waitForDone();
    }
    
    void waitForDone() throws TransportException
    {
        done.await(transport.getTimeout());
    }
    
    private synchronized void ensureKexOngoing() throws TransportException
    {
        if (!isKexOngoing())
            throw new TransportException(DisconnectReason.PROTOCOL_ERROR,
                    "Key exchange packet received when key exchange was not ongoing");
    }
    
    private static void ensureReceivedMatchesExpected(Message got, Message expected) throws TransportException
    {
        if (got != expected)
            throw new TransportException(DisconnectReason.PROTOCOL_ERROR, "Was expecting " + expected);
    }
    
    /**
     * Sends SSH_MSG_KEXINIT and sets the {@link kexInitSent} event.
     * 
     * @throws TransportException
     */
    private void sendKexInit() throws TransportException
    {
        log.info("Sending SSH_MSG_KEXINIT");
        clientProposal = new Proposal(transport.getConfig());
        transport.write(clientProposal.getPacket());
        kexInitSent.set();
    }
    
    private void sendNewKeys() throws TransportException
    {
        log.info("Sending SSH_MSG_NEWKEYS");
        transport.write(new SSHPacket(Message.NEWKEYS));
    }
    
    /**
     * Tries to validate host key with all the host key verifiers known to this instance ( {@link #hostVerifiers})
     * 
     * @param key
     *            the host key to verify
     */
    private synchronized void verifyHost(PublicKey key) throws TransportException
    {
        for (HostKeyVerifier hkv : hostVerifiers)
        {
            log.debug("Trying to verify host key with {}", hkv);
            if (hkv.verify(transport.getRemoteHost(), key))
                return;
        }
        
        throw new TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "Could not verify `"
                + KeyType.fromKey(key) + "` host key with fingerprint `" + SecurityUtils.getFingerprint(key)
                + "` for `" + transport.getRemoteHost() + "`");
    }
    
    private void setKexDone()
    {
        kexOngoing.set(false);
        kexInitSent.clear();
        done.set();
    }
    
    private void gotKexInit(SSHPacket buf) throws TransportException
    {
        serverProposal = new Proposal(buf);
        negotiatedAlgs = clientProposal.negotiate(serverProposal);
        log.debug("Negotiated algorithms: {}", negotiatedAlgs);
        kex = Factory.Named.Util.create(transport.getConfig().getKeyExchangeFactories(), negotiatedAlgs
                .getKeyExchangeAlgorithm());
        kex.init(transport, transport.getServerID().getBytes(), transport.getClientID().getBytes(), buf
                .getCompactData(), clientProposal.getPacket().getCompactData());
    }
    
    /**
     * Private method used while putting new keys into use that will resize the key used to initialize the cipher to the
     * needed length.
     * 
     * @param E
     *            the key to resize
     * @param blockSize
     *            the cipher block size
     * @param hash
     *            the hash algorithm
     * @param K
     *            the key exchange K parameter
     * @param H
     *            the key exchange H parameter
     * @return the resized key
     */
    private static byte[] resizedKey(byte[] E, int blockSize, Digest hash, byte[] K, byte[] H)
    {
        while (blockSize > E.length)
        {
            PlainBuffer buffer = new PlainBuffer() //
                    .putMPInt(K) //
                    .putRawBytes(H) //
                    .putRawBytes(E);
            hash.update(buffer.array(), 0, buffer.available());
            byte[] foo = hash.digest();
            byte[] bar = new byte[E.length + foo.length];
            System.arraycopy(E, 0, bar, 0, E.length);
            System.arraycopy(foo, 0, bar, E.length, foo.length);
            E = bar;
        }
        return E;
    }
    
    /* See Sec. 7.2. "Output from Key Exchange", RFC 4253 */
    private void gotNewKeys()
    {
        final Digest hash = kex.getHash();
        
        if (sessionID == null)
            // session id is 'H' from the first key exchange and does not change thereafter
            sessionID = Arrays.copyOf(kex.getH(), kex.getH().length);
        
        final PlainBuffer buf = new PlainBuffer() //
                .putMPInt(kex.getK()) //
                .putRawBytes(kex.getH()) //
                .putByte((byte) 0) // <placeholder>
                .putRawBytes(sessionID);
        final int pos = buf.available() - sessionID.length - 1; // Position of <placeholder>
        
        buf.array()[pos] = 'A';
        hash.update(buf.array(), 0, buf.available());
        final byte[] initialIV_C2S = hash.digest();
        
        buf.array()[pos] = 'B';
        hash.update(buf.array(), 0, buf.available());
        final byte[] initialIV_S2C = hash.digest();
        
        buf.array()[pos] = 'C';
        hash.update(buf.array(), 0, buf.available());
        final byte[] encryptionKey_C2S = hash.digest();
        
        buf.array()[pos] = 'D';
        hash.update(buf.array(), 0, buf.available());
        final byte[] encryptionKey_S2C = hash.digest();
        
        buf.array()[pos] = 'E';
        hash.update(buf.array(), 0, buf.available());
        final byte[] integrityKey_C2S = hash.digest();
        
        buf.array()[pos] = 'F';
        hash.update(buf.array(), 0, buf.available());
        final byte[] integrityKey_S2C = hash.digest();
        
        final Cipher cipher_C2S = Factory.Named.Util.create(transport.getConfig().getCipherFactories(), negotiatedAlgs
                .getClient2ServerCipherAlgorithm());
        cipher_C2S.init(Cipher.Mode.Encrypt, //
                resizedKey(encryptionKey_C2S, cipher_C2S.getBlockSize(), hash, kex.getK(), kex.getH()), //
                initialIV_C2S);
        
        final Cipher cipher_S2C = Factory.Named.Util.create(transport.getConfig().getCipherFactories(), //
                negotiatedAlgs.getServer2ClientCipherAlgorithm());
        cipher_S2C.init(Cipher.Mode.Decrypt, //
                resizedKey(encryptionKey_S2C, cipher_S2C.getBlockSize(), hash, kex.getK(), kex.getH()), //
                initialIV_S2C);
        
        final MAC mac_C2S = Factory.Named.Util.create(transport.getConfig().getMACFactories(), negotiatedAlgs
                .getClient2ServerMACAlgorithm());
        mac_C2S.init(integrityKey_C2S);
        
        final MAC mac_S2C = Factory.Named.Util.create(transport.getConfig().getMACFactories(), //
                negotiatedAlgs.getServer2ClientMACAlgorithm());
        mac_S2C.init(integrityKey_S2C);
        
        final Compression compression_S2C = Factory.Named.Util.create(transport.getConfig().getCompressionFactories(),
                negotiatedAlgs.getServer2ClientCompressionAlgorithm());
        final Compression compression_C2S = Factory.Named.Util.create(transport.getConfig().getCompressionFactories(),
                negotiatedAlgs.getClient2ServerCompressionAlgorithm());
        
        transport.getEncoder().setAlgorithms(cipher_C2S, mac_C2S, compression_C2S);
        transport.getDecoder().setAlgorithms(cipher_S2C, mac_S2C, compression_S2C);
    }
    
    public void handle(Message msg, SSHPacket buf) throws TransportException
    {
        switch (expected)
        {
        
        case KEXINIT:
            ensureReceivedMatchesExpected(msg, Message.KEXINIT);
            log.info("Received SSH_MSG_KEXINIT");
            startKex(false); // Will start key exchange if not already on
            /*
             * We block on this event to prevent a race condition where we may have received a SSH_MSG_KEXINIT before
             * having sent the packet ourselves (would cause gotKexInit() to fail)
             */
            kexInitSent.await(transport.getTimeout());
            buf.rpos(buf.rpos() - 1);
            gotKexInit(buf);
            expected = Expected.FOLLOWUP;
            break;
        
        case FOLLOWUP:
            ensureKexOngoing();
            log.info("Received kex followup data");
            buf.rpos(buf.rpos() - 1); // un-read the message byte
            if (kex.next(buf))
            {
                verifyHost(kex.getHostKey());
                sendNewKeys();
                expected = Expected.NEWKEYS;
            }
            break;
        
        case NEWKEYS:
            ensureReceivedMatchesExpected(msg, Message.NEWKEYS);
            ensureKexOngoing();
            log.info("Received SSH_MSG_NEWKEYS");
            gotNewKeys();
            setKexDone();
            expected = Expected.KEXINIT;
            break;
        
        default:
            assert false;
            
        }
    }
    
    @SuppressWarnings("unchecked")
    public void notifyError(SSHException error)
    {
        log.debug("Got notified of {}", error.toString());
        ErrorNotifiable.Util.alertAll(error, kexInitSent, done);
    }
    
}
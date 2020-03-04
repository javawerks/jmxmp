/*
 * @(#)file      SocketConnection.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.37
 * @(#)lastedit  07/03/08
 * @(#)build     @BUILD_TAG_PLACEHOLDER@
 *
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007 Sun Microsystems, Inc. All Rights Reserved.
 *
 * The contents of this file are subject to the terms of either the GNU General
 * Public License Version 2 only ("GPL") or the Common Development and
 * Distribution License("CDDL")(collectively, the "License"). You may not use
 * this file except in compliance with the License. You can obtain a copy of the
 * License at http://opendmk.dev.java.net/legal_notices/licenses.txt or in the
 * LEGAL_NOTICES folder that accompanied this code. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file found at
 *     http://opendmk.dev.java.net/legal_notices/licenses.txt
 * or in the LEGAL_NOTICES folder that accompanied this code.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.
 *
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 *
 *       "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding
 *
 *       "[Contributor] elects to include this software in this distribution
 *        under the [CDDL or GPL Version 2] license."
 *
 * If you don't indicate a single choice of license, a recipient has the option
 * to distribute your version of this file under either the CDDL or the GPL
 * Version 2, or to extend the choice of license to its licensees as provided
 * above. However, if you add GPL Version 2 code and therefore, elected the
 * GPL Version 2 license, then the option applies only if the new code is made
 * subject to such option by the copyright holder.
 *
 */
package com.sun.jmx.remote.socket;

import com.sun.jmx.remote.generic.DefaultConfig;
import com.sun.jmx.remote.opt.util.ClassLogger;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.Principal;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.generic.MessageConnection;
import javax.management.remote.message.Message;
import javax.security.auth.Subject;

/**
 * This class uses TCP sockets to implement a JMX client MessageConnection.
 */
public class SocketConnection implements SocketConnectionIf, MessageConnection {

    //-------------
    // Constructors
    //-------------
    /**
     * Constructs a TCP socket client connection from a given socket.
     *
     * @param socket a TCP socket created by a user.
     * @throws java.io.IOException
     */
    public SocketConnection(Socket socket) throws IOException {
        LOGGER.trace("Constructor", "Creating with a socket " + socket);

        this.sock = socket;

        addr = sock.getInetAddress().getHostName();
        port = sock.getPort();
        replaceStreams(socket.getInputStream(), socket.getOutputStream());
    }

    /**
     * Constructs a TCP socket client connection from the given address and port.
     *
     * @param addr the server host address.
     * @param port the server port.
     * @throws java.io.IOException
     */
    public SocketConnection(String addr, int port) throws IOException {
        LOGGER.trace("Constructor", "Creating with a socket address: " + addr + " " + port);

        this.addr = addr;
        this.port = port;
    }

    @Override
    public void connect(Map env) throws IOException {
        waitConnectedState = DefaultConfig.getTimeoutForWaitConnectedState(env);

        synchronized (stateLock) {
            if (state == UNCONNECTED) {
                LOGGER.trace("connect", "First time to connect to the server.");

                state = CONNECTING;
                stateLock.notifyAll();

                if (sock == null) {
                    sock = createAndConnectSocket(addr, port);
                }
                LOGGER.fine("connect", "Connecting to socket " + sock);
                replaceStreams(sock.getInputStream(), sock.getOutputStream());

                if (env != null) {
                    defaultClassLoader = (ClassLoader) env.get(JMXConnectorFactory.DEFAULT_CLASS_LOADER);
                }

                state = CONNECTED;
                stateLock.notifyAll();

            } else if (state == FAILED || state == CONNECTED) {
                // reconnecting
                LOGGER.trace("connect", "Try to re-connect to the server.");

                if (state == CONNECTED) {
                    state = FAILED;
                    stateLock.notifyAll();

                    try {
                        sock.close();
                    } catch (IOException ioe) {
                        // OK.
                        // We are closing the socket.
                    }
                }

                state = CONNECTING;
                stateLock.notifyAll();

                LOGGER.trace("connect", "connecting...");

                LOGGER.trace("connect", "before");
                sock = createAndConnectSocket(addr, port);
                LOGGER.trace("connect", "after");

                replaceStreams(sock.getInputStream(), sock.getOutputStream());
                LOGGER.trace("connect", "streams replaced");

                state = CONNECTED;
                LOGGER.trace("connect", "connected");
                stateLock.notifyAll();
            } else if (state == TERMINATED) {
                throw new IllegalStateException("The connection has been closed.");
            } else {
                LOGGER.trace("connect", "Waiting the state changing.");
                checkState();
            }
        }
    }

    protected Socket createAndConnectSocket(String addr, int port) throws UnknownHostException, IOException {
        return new Socket(addr, port);
    }

    //---------------------------------------------------
    // Implementation of the SocketConnectionIf interface
    //---------------------------------------------------
    /**
     * ******************************************************
     */
    /* Methods required to support the TLS and SASL profiles */
    /**
     * ******************************************************
     */
    /**
     * Returns a reference to the underlying socket.
     *
     * @return
     */
    @Override
    public Socket getSocket() {
        return sock;
    }

    /**
     * Replaces the underlying socket.
     */
    @Override
    public void setSocket(Socket s) throws IOException {
        sock = s;
        replaceStreams(sock.getInputStream(), sock.getOutputStream());
    }

    /**
     * Replaces the current socket's input/output streams by the ones provided.
     */
    @Override
    public void replaceStreams(InputStream is, OutputStream os) throws IOException {
        in = is;
        out = os;
        replaceInputStreamFlag = true;
        replaceOutputStreamFlag = true;
    }

    /**
     * Sets the subject authenticated through this socket connection.
     */
    @Override
    public void setSubject(Subject subject) {
        this.subject = subject;
    }

    //--------------------------------------------------
    // Implementation of the MessageConnection interface
    //--------------------------------------------------
    @Override
    public Message readMessage() throws IOException, ClassNotFoundException {
        checkState();

        LOGGER.debug("readMessage", "Read a message ...");

        try {
            if (replaceInputStreamFlag) {
                if (in instanceof BufferedInputStream) {
                    oin = new ObjectInputStreamWithLoader(in, defaultClassLoader);
                } else {
                    oin = new ObjectInputStreamWithLoader(new BufferedInputStream(in), defaultClassLoader);
                }
                replaceInputStreamFlag = false;
            }

            return (Message) oin.readObject();
        } catch (IOException ioe) {
            throw new IOException(ioe.getMessage() + " for socket " + this.addr + ":" + this.port, ioe);
        }
    }

    @Override
    public void writeMessage(Message msg) throws IOException {
        LOGGER.debug("writeMessage", "Write a message ...");
        checkState();

        try {
            if (replaceOutputStreamFlag) {
                if (out instanceof BufferedOutputStream) {
                    oout = new ObjectOutputStream(out);
                } else {
                    oout = new ObjectOutputStream(new BufferedOutputStream(out));
                }
                replaceOutputStreamFlag = false;
            }
            oout.writeObject(msg);
            oout.flush();
            oout.reset();
        } catch (IOException ioe) {
            throw new IOException(ioe.getMessage() + " for socket " + this.addr + ":" + this.port, ioe);
        }
    }

    @Override
    public void close() {
        LOGGER.trace("close", "Close the socket connection.");

        synchronized (stateLock) {
            if (state == TERMINATED) {
                return;
            }

            state = TERMINATED;

            // For ssl, if A sends a message to another B, and A closes the socket
            // immediately after receiving the message, it will make the side A receive a
            // SocketCloseException for its operation sending the messsage.
            // See bug 4926015
            if (sock instanceof javax.net.ssl.SSLSocket) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ire) {
                    // OK: we are closing
                }
            }

            try {
                oin.close();
            } catch (Exception e) {
                LOGGER.debug("close", e);
            }

            try {
                oout.close();
            } catch (Exception e) {
                LOGGER.debug("close", e);
            }

            try {
                sock.close();
                LOGGER.fine("close", "socket " + this.addr + ":" + this.port + " closed");
            } catch (Exception e) {
                LOGGER.debug("close", e);
            }

            stateLock.notify();
        }
    }

    @Override
    public String getConnectionId() {
        if (sock == null) {
            return defaultConnectionId;
        }
        StringBuilder buf = new StringBuilder();
        buf.append("jmxmp://").append(sock.getInetAddress().getHostAddress()).append(":").append(sock.getPort()).append(" ");
        if (subject != null) {
            Set principals = subject.getPrincipals();
            String sep = "";
            for (Iterator it = principals.iterator(); it.hasNext();) {
                Principal p = (Principal) it.next();
                String n = p.getName().replace(' ', '_').replace(';', ':');
                buf.append(sep).append(n);
                sep = ";";
            }
        }
        buf.append(" ").append(System.identityHashCode(this));
        connectionId = buf.toString();
        return connectionId;
    }

    @Override
    public String toString() {
        return "SocketConnection{" + "subject=" + subject + ", connectionId=" + connectionId + ", sock=" + sock + ", addr=" + addr + ", port=" + port + ", state=" + state + ", waitConnectedState=" + waitConnectedState + '}';
    }

//----------------------------------------
// Private methods
//----------------------------------------
    // check or wait(1s) the state as CONNECTED
    //
    private void checkState() throws IllegalStateException {
        synchronized (stateLock) {
            if (state == CONNECTED) {
                return;
            } else if (state == TERMINATED) {
                throw new IllegalStateException("The connection has been closed.");
            }

            // waiting
            long waitingTime = waitConnectedState;
            final long endTime = System.currentTimeMillis() + waitingTime;

            while (state != CONNECTED && state != TERMINATED && waitingTime > 0) {
                try {
                    stateLock.wait(waitingTime);
                } catch (InterruptedException ire) {
                    break;
                }

                waitingTime = endTime - System.currentTimeMillis();
            }

            if (state != CONNECTED) {
                throw new IllegalStateException("The connection is not currently established.");
            }
        }
    }

//----------------------------------------
// Private variables
//----------------------------------------
    private static class ObjectInputStreamWithLoader extends ObjectInputStream {

        public ObjectInputStreamWithLoader(InputStream in, ClassLoader cl) throws IOException {
            super(in);
            this.cloader = cl;
        }

        @Override
        protected Class resolveClass(ObjectStreamClass aClass) throws IOException, ClassNotFoundException {
            return cloader == null ? super.resolveClass(aClass) : Class.forName(aClass.getName(), false, cloader);
        }

        private final ClassLoader cloader;
    }

    private Subject subject;
    private String connectionId;
    private Socket sock;
    private InputStream in;
    private ObjectInputStream oin;
    private OutputStream out;
    private ObjectOutputStream oout;
    private boolean replaceInputStreamFlag = false;
    private boolean replaceOutputStreamFlag = false;
    private final String addr;
    private final int port;
    private ClassLoader defaultClassLoader;
    private final String defaultConnectionId = "Uninitialized connection id";

    // state issues
    private static final int UNCONNECTED = 1;
    private static final int CONNECTING = 2;
    private static final int CONNECTED = 4;
    private static final int FAILED = 8;
    private static final int TERMINATED = 16;

    private int state = UNCONNECTED;
    private final int[] stateLock = new int[0];

    private long waitConnectedState = 1000;

    private static final ClassLogger LOGGER = new ClassLogger("javax.management.remote.misc", "SocketConnection");
}

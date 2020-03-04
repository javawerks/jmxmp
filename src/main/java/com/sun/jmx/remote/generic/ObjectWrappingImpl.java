/*
 * @(#)file      ObjectWrappingImpl.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.9
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
package com.sun.jmx.remote.generic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import javax.management.remote.generic.ObjectWrapping;

/**
 * This class is the default implementation of the interface <code>ObjectWrapping</code>. Objects are wrapped in a byte
 * array containing the output of {@link
 * ObjectOutputStream#writeObject(Object)}.
 */
public class ObjectWrappingImpl implements ObjectWrapping {

    public ObjectWrappingImpl() {
    }

    @Override
    public Object wrap(Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);

        return baos.toByteArray();
    }

    @Override
    public Object unwrap(Object wrapped, ClassLoader cloader) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream((byte[]) wrapped);
        ObjectInputStreamWithLoader ois = new ObjectInputStreamWithLoader(bais);
        return ois.readObject(cloader);
    }

    private static class ObjectInputStreamWithLoader extends ObjectInputStream {

        public ObjectInputStreamWithLoader(InputStream in) throws IOException {
            super(in);
        }

        public Object readObject(ClassLoader cloader) throws IOException, ClassNotFoundException {
            this.cloader = cloader;

            return readObject();
        }

        @Override
        protected Class resolveClass(ObjectStreamClass aClass) throws IOException, ClassNotFoundException {
            return cloader == null ? super.resolveClass(aClass) : Class.forName(aClass.getName(), false, cloader);
        }

        private ClassLoader cloader;
    }
}

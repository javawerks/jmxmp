/*
 * @(#)JMXSubjectDomainCombiner.java	1.3
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
package com.sun.jmx.remote.opt.security;

import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import javax.security.auth.Subject;
import javax.security.auth.SubjectDomainCombiner;

/**
 * <p>
 * This class represents an extension to the {@link SubjectDomainCombiner} and is used to add a new
 * {@link ProtectionDomain}, comprised of a null codesource/signers and an empty permission set, to the access control
 * context with which this combiner is combined.</p>
 *
 * <p>
 * When the {@link #combine} method is called the {@link ProtectionDomain} is augmented with the permissions granted to
 * the set of principals present in the supplied {@link Subject}.</p>
 */
public class JMXSubjectDomainCombiner extends SubjectDomainCombiner {

    public JMXSubjectDomainCombiner(Subject s) {
        super(s);
    }

    @Override
    public ProtectionDomain[] combine(ProtectionDomain[] current,
            ProtectionDomain[] assigned) {
        // Add a new ProtectionDomain with the null codesource/signers, and
        // the empty permission set, to the end of the array containing the
        // 'current' protections domains, i.e. the ones that will be augmented
        // with the permissions granted to the set of principals present in
        // the supplied subject.
        //
        ProtectionDomain[] newCurrent;
        if (current == null || current.length == 0) {
            newCurrent = new ProtectionDomain[1];
            newCurrent[0] = PD;
        } else {
            newCurrent = new ProtectionDomain[current.length + 1];
            System.arraycopy(current, 0, newCurrent, 0, current.length);
            // insert new protection domain
            newCurrent[current.length] = PD;
        }
        return super.combine(newCurrent, assigned);
    }

    private static final CodeSource NULL_CODE_SOURCE = new CodeSource(null, (java.security.cert.Certificate[]) null);
    private static final ProtectionDomain PD = new ProtectionDomain(NULL_CODE_SOURCE, new Permissions());
}

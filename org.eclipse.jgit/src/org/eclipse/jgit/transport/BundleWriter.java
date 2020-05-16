/*
 * Copyright (C) 2008-2010, Google Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.pack.PackConfig;

/**
 * Creates a Git bundle file, for sneaker-net transport to another system.
 * <p>
 * Bundles generated by this class can be later read in from a file URI using
 * the bundle transport, or from an application controlled buffer by the more
 * generic {@link org.eclipse.jgit.transport.TransportBundleStream}.
 * <p>
 * Applications creating bundles need to call one or more <code>include</code>
 * calls to reflect which objects should be available as refs in the bundle for
 * the other side to fetch. At least one include is required to create a valid
 * bundle file, and duplicate names are not permitted.
 * <p>
 * Optional <code>assume</code> calls can be made to declare commits which the
 * recipient must have in order to fetch from the bundle file. Objects reachable
 * from these assumed commits can be used as delta bases in order to reduce the
 * overall bundle size.
 */
public class BundleWriter {
	private final Repository db;

	private final ObjectReader reader;

	private final Map<String, ObjectId> include;

	private final Set<RevCommit> assume;

	private final Set<ObjectId> tagTargets;

	private PackConfig packConfig;

	private ObjectCountCallback callback;

	/**
	 * Create a writer for a bundle.
	 *
	 * @param repo
	 *            repository where objects are stored.
	 */
	public BundleWriter(Repository repo) {
		db = repo;
		reader = null;
		include = new TreeMap<>();
		assume = new HashSet<>();
		tagTargets = new HashSet<>();
	}

	/**
	 * Create a writer for a bundle.
	 *
	 * @param or
	 *            reader for reading objects. Will be closed at the end of {@link
	 *            #writeBundle(ProgressMonitor, OutputStream)}, but readers may be
	 *            reused after closing.
	 * @since 4.8
	 */
	public BundleWriter(ObjectReader or) {
		db = null;
		reader = or;
		include = new TreeMap<>();
		assume = new HashSet<>();
		tagTargets = new HashSet<>();
	}

	/**
	 * Set the configuration used by the pack generator.
	 *
	 * @param pc
	 *            configuration controlling packing parameters. If null the
	 *            source repository's settings will be used, or the default
	 *            settings if constructed without a repo.
	 */
	public void setPackConfig(PackConfig pc) {
		this.packConfig = pc;
	}

	/**
	 * Include an object (and everything reachable from it) in the bundle.
	 *
	 * @param name
	 *            name the recipient can discover this object as from the
	 *            bundle's list of advertised refs . The name must be a valid
	 *            ref format and must not have already been included in this
	 *            bundle writer.
	 * @param id
	 *            object to pack. Multiple refs may point to the same object.
	 */
	public void include(String name, AnyObjectId id) {
		boolean validRefName = Repository.isValidRefName(name) || Constants.HEAD.equals(name);
		if (!validRefName)
			throw new IllegalArgumentException(MessageFormat.format(JGitText.get().invalidRefName, name));
		if (include.containsKey(name))
			throw new IllegalStateException(JGitText.get().duplicateRef + name);
		include.put(name, id.toObjectId());
	}

	/**
	 * Include a single ref (a name/object pair) in the bundle.
	 * <p>
	 * This is a utility function for:
	 * <code>include(r.getName(), r.getObjectId())</code>.
	 *
	 * @param r
	 *            the ref to include.
	 */
	public void include(Ref r) {
		include(r.getName(), r.getObjectId());

		if (r.getPeeledObjectId() != null)
			tagTargets.add(r.getPeeledObjectId());

		else if (r.getObjectId() != null
				&& r.getName().startsWith(Constants.R_HEADS))
			tagTargets.add(r.getObjectId());
	}

	/**
	 * Assume a commit is available on the recipient's side.
	 * <p>
	 * In order to fetch from a bundle the recipient must have any assumed
	 * commit. Each assumed commit is explicitly recorded in the bundle header
	 * to permit the recipient to validate it has these objects.
	 *
	 * @param c
	 *            the commit to assume being available. This commit should be
	 *            parsed and not disposed in order to maximize the amount of
	 *            debugging information available in the bundle stream.
	 */
	public void assume(RevCommit c) {
		if (c != null)
			assume.add(c);
	}

	/**
	 * Generate and write the bundle to the output stream.
	 * <p>
	 * This method can only be called once per BundleWriter instance.
	 *
	 * @param monitor
	 *            progress monitor to report bundle writing status to.
	 * @param os
	 *            the stream the bundle is written to. The stream should be
	 *            buffered by the caller. The caller is responsible for closing
	 *            the stream.
	 * @throws java.io.IOException
	 *             an error occurred reading a local object's data to include in
	 *             the bundle, or writing compressed object data to the output
	 *             stream.
	 */
	public void writeBundle(ProgressMonitor monitor, OutputStream os)
			throws IOException {
		try (PackWriter packWriter = newPackWriter()) {
			packWriter.setObjectCountCallback(callback);

			final HashSet<ObjectId> inc = new HashSet<>();
			final HashSet<ObjectId> exc = new HashSet<>();
			inc.addAll(include.values());
			for (RevCommit r : assume)
				exc.add(r.getId());
			packWriter.setIndexDisabled(true);
			packWriter.setDeltaBaseAsOffset(true);
			packWriter.setThin(exc.size() > 0);
			packWriter.setReuseValidatingObjects(false);
			if (exc.size() == 0)
				packWriter.setTagTargets(tagTargets);
			packWriter.preparePack(monitor, inc, exc);

			final Writer w = new OutputStreamWriter(os, UTF_8);
			w.write(TransportBundle.V2_BUNDLE_SIGNATURE);
			w.write('\n');

			final char[] tmp = new char[Constants.OBJECT_ID_STRING_LENGTH];
			for (RevCommit a : assume) {
				w.write('-');
				a.copyTo(tmp, w);
				if (a.getRawBuffer() != null) {
					w.write(' ');
					w.write(a.getShortMessage());
				}
				w.write('\n');
			}
			for (Map.Entry<String, ObjectId> e : include.entrySet()) {
				e.getValue().copyTo(tmp, w);
				w.write(' ');
				w.write(e.getKey());
				w.write('\n');
			}

			w.write('\n');
			w.flush();
			packWriter.writePack(monitor, monitor, os);
		}
	}

	private PackWriter newPackWriter() {
		PackConfig pc = packConfig;
		if (pc == null) {
			pc = db != null ? new PackConfig(db) : new PackConfig();
		}
		return new PackWriter(pc, reader != null ? reader : db.newObjectReader());
	}

	/**
	 * Set the {@link org.eclipse.jgit.transport.ObjectCountCallback}.
	 * <p>
	 * It should be set before calling
	 * {@link #writeBundle(ProgressMonitor, OutputStream)}.
	 * <p>
	 * This callback will be passed on to
	 * {@link org.eclipse.jgit.internal.storage.pack.PackWriter#setObjectCountCallback}.
	 *
	 * @param callback
	 *            the callback to set
	 * @return this object for chaining.
	 * @since 4.1
	 */
	public BundleWriter setObjectCountCallback(ObjectCountCallback callback) {
		this.callback = callback;
		return this;
	}
}

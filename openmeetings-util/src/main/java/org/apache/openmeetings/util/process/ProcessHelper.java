/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
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
package org.apache.openmeetings.util.process;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.openmeetings.util.CalendarHelper.formatMillis;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getExtProcessTtl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessHelper {
	public static final Logger log = LoggerFactory.getLogger(ProcessHelper.class);

	// This is necessary to prevent 'buffer overflow'
	// https://stackoverflow.com/questions/9885643/ffmpeg-executed-from-javas-processbuilder-does-not-return-under-windows-7
	private static class StreamWatcher extends Thread {
		public final StringBuilder output = new StringBuilder();
		private final InputStream is;
		private boolean run = true;

		private StreamWatcher(final InputStream is) {
			this.is = is;
		}

		@Override
		public void run() {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is, UTF_8))) {
				String line = br.readLine();
				while (run && line != null) {
					output.append(line).append('\n');
					line = br.readLine();
				}
			} catch (IOException ioexception) {
				// no-op
			}
		}

		public void finish() {
			run = false;
		}

		@Override
		public String toString() {
			return output.toString();
		}
	}

	private ProcessHelper() {}

	private static String getCommand(String[] argv) {
		StringBuilder tString = new StringBuilder();
		for (int i = 0; i < argv.length; i++) {
			tString.append(argv[i]).append(" ");
		}
		return tString.toString();
	}

	private static void debugCommandStart(String desc, String[] argv) {
		if (log.isDebugEnabled()) {
			log.debug("START {} ################# ", desc);
			log.debug(getCommand(argv));
		}
	}

	private static void debugCommandEnd(String desc) {
		if (log.isDebugEnabled()) {
			log.debug("END {} ################# ", desc);
		}
	}

	public static ProcessResult executeScript(String process, String[] argv) {
		return executeScript(process, argv, false);
	}

	public static ProcessResult executeScript(String process, String[] argv, boolean optional) {
		Map<String, String> env = new HashMap<>();
		return executeScript(process, argv, env, optional);
	}

	private static ProcessResult executeScript(String process, String[] argv, Map<? extends String, ? extends String> env, boolean optional) {
		ProcessResult res = new ProcessResult()
				.setProcess(process)
				.setOptional(optional);
		debugCommandStart(process, argv);

		Process proc = null;
		StreamWatcher errorWatcher = null;
		StreamWatcher inputWatcher = null;
		final long start = System.currentTimeMillis();
		try {
			res.setCommand(getCommand(argv)).setOut("");

			// By using the process Builder we have access to modify the
			// environment variables
			// that is handy to set variables to run it inside eclipse
			ProcessBuilder pb = new ProcessBuilder(argv);
			pb.environment().putAll(env);

			proc = pb.start();
			errorWatcher = new StreamWatcher(proc.getErrorStream());
			inputWatcher = new StreamWatcher(proc.getInputStream());
			errorWatcher.start();
			inputWatcher.start();

			// 20-minute timeout for command execution
			// FFMPEG conversion of Recordings may take a real long time until
			// its finished
			proc.waitFor(getExtProcessTtl(), TimeUnit.MINUTES);

			res.setExitCode(proc.exitValue())
				.setOut(inputWatcher.toString())
				.setError(errorWatcher.toString());
		} catch (Throwable t) {
			log.error("executeScript", t);
			res.setExitCode(-1)
				.setError(String.format("Exception after %s of work; %s", formatMillis(System.currentTimeMillis() - start), t.getMessage()))
				.setException(t.toString());
		} finally {
			if (proc != null) {
				errorWatcher.finish();
				inputWatcher.finish();
				proc.destroy();
			}
		}

		debugCommandEnd(process);
		return res;
	}
}

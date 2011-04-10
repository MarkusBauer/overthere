/*
 * This file is part of Overthere.
 * 
 * Overthere is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Overthere is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Overthere.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.xebialabs.overthere.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.xebialabs.overthere.RuntimeIOException;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.LoggerFactory;

/**
 * An input stream from a file on a host connected through SSH w/ SCP.
 */
class SshScpInputStream extends InputStream {

	protected SshScpOverthereFile file;

	protected Session session;

	protected ChannelExec channel;

	protected InputStream channelIn;

	protected OutputStream channelOut;

	protected long bytesRemaining;

	private static final String CHANNEL_PURPOSE = " (for SCP input stream)";

	SshScpInputStream(SshScpOverthereFile file) {
		this.file = file;
	}

	void open() {
		try {
			// connect to SSH and start scp in source mode
			session = file.sshHostConnection.openSession(CHANNEL_PURPOSE);
			channel = (ChannelExec) session.openChannel("exec");
			// no password in this command, so use 'false'
			String command = file.sshHostConnection.encodeCommandLineForExecution("scp", "-f", file.getPath());
			channel.setCommand(command);
			channelIn = channel.getInputStream();
			channelOut = channel.getOutputStream();
			channel.connect();
			logger.info("Executing remote command \"" + command + "\" on " + file.sshHostConnection + " to open SCP stream for reading");

			// perform SCP read protocol
			sendAck();
			readAck('C');
			readPermissions();
			bytesRemaining = readFileLength();
			readFilename();
			sendAck();
			logger.info("Opened SCP stream to read from remote file " + file);
		} catch (IOException exc) {
			throw new RuntimeIOException("Cannot open SCP stream to read remote file " + file, exc);
		} catch (JSchException exc) {
			throw new RuntimeIOException("Cannot open SCP stream to read remote file " + file, exc);
		}
	}

	private void sendAck() throws IOException {
		if (logger.isDebugEnabled())
			logger.debug("Sending ACK");

		byte[] buf = new byte[1];
		buf[0] = 0;
		channelOut.write(buf);
		channelOut.flush();
	}

	private void readAck(int expectedChar) {
		if (logger.isDebugEnabled())
			logger.debug("Reading ACK");

		int c = SshStreamUtils.checkAck(channelIn);
		if (c != expectedChar) {
			throw new RuntimeIOException("Protocol error on SCP stream to read remote file " + file + " (remote scp command returned acked with a \"" + c + "\")");
		}
	}

	private void readPermissions() throws IOException {
		if (logger.isDebugEnabled())
			logger.debug("Reading permissions");

		// read '0644 '
		byte[] buf = new byte[5];
		channelIn.read(buf, 0, 5);
	}

	private long readFileLength() throws IOException {
		if (logger.isDebugEnabled())
			logger.debug("Reading file length");

		// read file length terminated by a space
		long filelength = 0L;
		while (true) {
			int c;
			if ((c = channelIn.read()) < 0) {
				throw new RuntimeIOException("Protocol error on SCP stream to read remote file " + file);
			}
			if (c == ' ') {
				if (logger.isDebugEnabled())
					logger.debug("File length = " + filelength);
				return filelength;
			}
			filelength = filelength * 10L + (long) (c - '0');
		}

	}

	private void readFilename() throws IOException {
		if (logger.isDebugEnabled())
			logger.debug("Reading file name");

		// read filename terminated by a newline
		while (true) {
			int c;
			if ((c = channelIn.read()) < 0) {
				throw new RuntimeIOException("Protocol error on SCP stream to read remote file " + file);
			}
			if (c == '\n') {
				break;
			}
		}
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (bytesRemaining > 0) {
			int bytesRead = channelIn.read(b, off, (int) Math.min(len, bytesRemaining));
			bytesRemaining -= bytesRead;
			return bytesRead;
		} else {
			return -1;
		}
	}

	@Override
	public int read() throws IOException {
		if (bytesRemaining > 0) {
			int b = channelIn.read();
			if (b >= 0) {
				bytesRemaining--;
			}
			return b;
		} else {
			return -1;
		}
	}

	@Override
	public void close() {
		try {
			readAck(0);
			sendAck();
		} catch (IOException ignore) {
		} catch(RuntimeIOException ignore2) {
		}
		IOUtils.closeQuietly(channelIn);
		IOUtils.closeQuietly(channelOut);
		channel.disconnect();
		file.sshHostConnection.disconnectSession(session, CHANNEL_PURPOSE);
	}

	private Logger logger = LoggerFactory.getLogger(SshScpInputStream.class);

}


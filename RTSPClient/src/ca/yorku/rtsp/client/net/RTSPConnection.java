/*
 * Author: Jonatan Schroeder
 * Updated: October 2022
 *
 * This code may not be used without written consent of the authors.
 */

package ca.yorku.rtsp.client.net;

import ca.yorku.rtsp.client.exception.RTSPException;
import ca.yorku.rtsp.client.model.Frame;
import ca.yorku.rtsp.client.model.Session;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

    private static final int BUFFER_LENGTH = 0x10000;
    private final Session session;

    // RTSP connection
    private Socket rtspSocket;
    private BufferedReader rtspReader;
    private BufferedWriter rtspWriter;

    // RTP connection
    private DatagramSocket rtpSocket;
    private String videoName;
    private String sessionId;
    private int sequenceNumber;
    private RTPReceivingThread rtpThread;
    private boolean isPlaying;

    /**
     * Establishes a new connection with an RTSP server. No message is
     * sent at this point, and no stream is set up.
     *
     * @param session The Session object to be used for connectivity with the UI.
     * @param server  The hostname or IP address of the server.
     * @param port    The TCP port number where the server is listening to.
     * @throws RTSPException If the connection couldn't be accepted,
     *                       such as if the host name or port number
     *                       are invalid or there is no connectivity.
     */
    public RTSPConnection(Session session, String server, int port) throws RTSPException {
        this.session = session;
        this.sequenceNumber = 1;
        this.isPlaying = false;

        try {
            // Establish RTSP socket connection
            rtspSocket = new Socket(server, port);
            rtspReader = new BufferedReader(new InputStreamReader(rtspSocket.getInputStream()));
            rtspWriter = new BufferedWriter(new OutputStreamWriter(rtspSocket.getOutputStream()));
        } catch (IOException e) {
            throw new RTSPException("Failed to establish RTSP connection: " + e.getMessage(), e);
        }
    }

    /**
     * Sets up a new video stream with the server. This method is
     * responsible for sending the SETUP request, receiving the
     * response and retrieving the session identification to be used
     * in future messages. It is also responsible for establishing an
     * RTP datagram socket to be used for data transmission by the
     * server. The datagram socket should be created with a random
     * available UDP port number, and the port number used in that
     * connection has to be sent to the RTSP server for setup. This
     * datagram socket should also be defined to timeout after 2
     * seconds if no packet is received.
     *
     * @param videoName The name of the video to be setup.
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the RTP
     *                       socket could not be created, or if the
     *                       server did not return a successful
     *                       response.
     */
    public synchronized void setup(String videoName) throws RTSPException {
        try {
            // Create RTP socket with random port
            rtpSocket = new DatagramSocket();
            rtpSocket.setSoTimeout(2000); // 2 second timeout
            int rtpPort = rtpSocket.getLocalPort();

            // Save video name for later use
            this.videoName = videoName;

            // Build and send SETUP request
            String setupRequest = "SETUP " + videoName + " RTSP/1.0\r\n" +
                    "CSeq: " + sequenceNumber + "\r\n" +
                    "Transport: RTP/UDP; client_port= " + rtpPort + "\r\n\r\n";

            rtspWriter.write(setupRequest);
            rtspWriter.flush();

            // Read response
            RTSPResponse response = readRTSPResponse();

            // Check if response is successful (200 OK)
            if (response.getResponseCode() != 200) {
                rtpSocket.close();
                throw new RTSPException("Setup failed: " + response.getResponseCode() + " " + response.getResponseMessage());
            }

            // Extract session ID from response
            sessionId = response.getHeaderValue("Session");
            if (sessionId == null) {
                rtpSocket.close();
                throw new RTSPException("No session ID provided in SETUP response");
            }

            // Increment sequence number for next request
            sequenceNumber++;

        } catch (IOException e) {
            throw new RTSPException("Error during SETUP: " + e.getMessage(), e);
        }
    }

    /**
     * Starts (or resumes) the playback of a set up stream. This
     * method is responsible for sending the request, receiving the
     * response and, in case of a successful response, starting a
     * separate thread responsible for receiving RTP packets with
     * frames (achieved by calling start() on a new object of type
     * RTPReceivingThread).
     *
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the server
     *                       did not return a successful response.
     */
    public synchronized void play() throws RTSPException {
        if (sessionId == null) {
            throw new RTSPException("Cannot play: No session is set up");
        }

        try {
            // Build and send PLAY request
            String playRequest = "PLAY " + videoName + " RTSP/1.0\r\n" +
                    "CSeq: " + sequenceNumber + "\r\n" +
                    "Session: " + sessionId + "\r\n\r\n";

            rtspWriter.write(playRequest);
            rtspWriter.flush();

            // Read response
            RTSPResponse response = readRTSPResponse();

            // Check if response is successful (200 OK)
            if (response.getResponseCode() != 200) {
                throw new RTSPException("Play failed: " + response.getResponseCode() + " " + response.getResponseMessage());
            }

            // Increment sequence number for next request
            sequenceNumber++;

            // Start RTP receiving thread if not already playing
            if (!isPlaying) {
                isPlaying = true;
                rtpThread = new RTPReceivingThread();
                rtpThread.start();
            }

        } catch (IOException e) {
            throw new RTSPException("Error during PLAY: " + e.getMessage(), e);
        }
    }

    private class RTPReceivingThread extends Thread {
        private boolean running = true;

        public void stopRunning() {
            running = false;
            this.interrupt();
        }

        /**
         * Continuously receives RTP packets until the thread is
         * cancelled or until an RTP packet is received with a
         * zero-length payload. Each packet received from the datagram
         * socket is assumed to be no larger than BUFFER_LENGTH
         * bytes. This data is then parsed into a Frame object (using
         * the parseRTPPacket() method) and the method
         * session.processReceivedFrame() is called with the resulting
         * packet. The receiving process should be configured to
         * timeout if no RTP packet is received after two seconds. If
         * a frame with zero-length payload is received, indicating
         * the end of the stream, the method session.videoEnded() is
         * called, and the thread is terminated.
         */
        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_LENGTH];

            while (running) {
                try {
                    // Create datagram packet for receiving data
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                    // Receive packet (will timeout after 2 seconds)
                    rtpSocket.receive(packet);

                    // Parse the RTP packet
                    Frame frame = parseRTPPacket(packet);

                    // Check if this is an end-of-stream packet (zero payload)
                    if (frame.getPayload().length == 0) {
                        session.videoEnded(sequenceNumber);
                        running = false;
                        break;
                    }

                    // Process the received frame
                    session.processReceivedFrame(frame);

                } catch (SocketTimeoutException e) {
                    // Socket timeout - just continue trying
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error receiving RTP packet: " + e.getMessage());
                    }
                    // If there's an error or if the socket is closed, exit the thread
                    break;
                }
            }

            isPlaying = false;
        }
    }

    /**
     * Pauses the playback of a set up stream. This method is
     * responsible for sending the request, receiving the response
     * and, in case of a successful response, stopping the thread
     * responsible for receiving RTP packets with frames.
     *
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the server
     *                       did not return a successful response.
     */
    public synchronized void pause() throws RTSPException {
        if (sessionId == null) {
            throw new RTSPException("Cannot pause: No session is set up");
        }

        try {
            // Build and send PAUSE request
            String pauseRequest = "PAUSE " + videoName + " RTSP/1.0\r\n" +
                    "CSeq: " + sequenceNumber + "\r\n" +
                    "Session: " + sessionId + "\r\n\r\n";

            rtspWriter.write(pauseRequest);
            rtspWriter.flush();

            // Read response
            RTSPResponse response = readRTSPResponse();

            // Check if response is successful (200 OK)
            if (response.getResponseCode() != 200) {
                throw new RTSPException("Pause failed: " + response.getResponseCode() + " " + response.getResponseMessage());
            }

            // Increment sequence number for next request
            sequenceNumber++;

            // Stop RTP thread if it's running
            if (isPlaying && rtpThread != null) {
                rtpThread.stopRunning();
                rtpThread = null;
                isPlaying = false;
            }

        } catch (IOException e) {
            throw new RTSPException("Error during PAUSE: " + e.getMessage(), e);
        }
    }

    /**
     * Terminates a set up stream. This method is responsible for
     * sending the request, receiving the response and, in case of a
     * successful response, closing the RTP socket. This method does
     * not close the RTSP connection, and a further SETUP in the same
     * connection should be accepted. Also, this method can be called
     * both for a paused and for a playing stream, so the thread
     * responsible for receiving RTP packets will also be cancelled,
     * if active.
     *
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the server
     *                       did not return a successful response.
     */
    public synchronized void teardown() throws RTSPException {
        if (sessionId == null) {
            throw new RTSPException("Cannot teardown: No session is set up");
        }

        try {
            // Build and send TEARDOWN request
            String teardownRequest = "TEARDOWN " + videoName + " RTSP/1.0\r\n" +
                    "CSeq: " + sequenceNumber + "\r\n" +
                    "Session: " + sessionId + "\r\n\r\n";

            rtspWriter.write(teardownRequest);
            rtspWriter.flush();

            // Read response
            RTSPResponse response = readRTSPResponse();

            // Check if response is successful (200 OK)
            if (response.getResponseCode() != 200) {
                throw new RTSPException("Teardown failed: " + response.getResponseCode() + " " + response.getResponseMessage());
            }

            // Increment sequence number for next request
            sequenceNumber++;

            // Stop RTP thread if it's running
            if (isPlaying && rtpThread != null) {
                rtpThread.stopRunning();
                rtpThread = null;
                isPlaying = false;
            }

            // Close RTP socket
            if (rtpSocket != null && !rtpSocket.isClosed()) {
                rtpSocket.close();
                rtpSocket = null;
            }

            // Clear session information
            sessionId = null;

        } catch (IOException e) {
            throw new RTSPException("Error during TEARDOWN: " + e.getMessage(), e);
        }
    }

    /**
     * Closes the connection with the RTSP server. This method should
     * also close any open resource associated to this connection,
     * such as the RTP connection and thread, if it is still open.
     */
    public synchronized void closeConnection() {
        try {
            // If a session is active, try to teardown first
            if (sessionId != null) {
                try {
                    teardown();
                } catch (RTSPException e) {
                    System.err.println("Error during teardown in closeConnection: " + e.getMessage());
                }
            }

            // Close RTSP connection resources
            if (rtspReader != null) {
                rtspReader.close();
                rtspReader = null;
            }

            if (rtspWriter != null) {
                rtspWriter.close();
                rtspWriter = null;
            }

            if (rtspSocket != null && !rtspSocket.isClosed()) {
                rtspSocket.close();
                rtspSocket = null;
            }

        } catch (IOException e) {
            System.err.println("Error closing RTSP connection: " + e.getMessage());
        }
    }

    /**
     * Parses an RTP packet into a Frame object. This method is
     * intended to be a helper method in this class, but it is made
     * public to facilitate testing.
     *
     * @param packet the byte representation of a frame, corresponding to the RTP
     *               packet.
     * @return A Frame object.
     */
    public static Frame parseRTPPacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int offset = packet.getOffset();
        int length = packet.getLength();

        if (length < 12) {
            throw new IllegalArgumentException("RTP packet too short to be valid");
        }

        // RTP Header Parsing
        int firstByte = data[offset] & 0xFF;
        boolean marker = (data[offset + 1] & 0x80) != 0; // Marker bit
        byte payloadType = (byte) (data[offset + 1] & 0x7F); // 7 bits for Payload Type

        short sequenceNumber = (short) (((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF));
        int timestamp = ((data[offset + 4] & 0xFF) << 24) |
                ((data[offset + 5] & 0xFF) << 16) |
                ((data[offset + 6] & 0xFF) << 8) |
                (data[offset + 7] & 0xFF);

        // Payload extraction (excluding 12-byte RTP header)
        int payloadOffset = offset + 12;
        int payloadLength = length - 12;

        return new Frame(payloadType, marker, sequenceNumber, timestamp, data, payloadOffset, payloadLength);
    }

    /**
     * Reads and parses an RTSP response from the socket's input. This
     * method is intended to be a helper method in this class, but it
     * is made public to facilitate testing.
     *
     * @return An RTSPResponse object if the response was read
     *         completely, or null if the end of the stream was reached.
     * @throws IOException   In case of an I/O error, such as loss of connectivity.
     * @throws RTSPException If the response doesn't match the expected format.
     */
    public RTSPResponse readRTSPResponse() throws IOException, RTSPException {
        // Read status line
        String statusLine = rtspReader.readLine();
        if (statusLine == null) {
            return null; // End of stream
        }

        // Parse status line
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 3 || !statusParts[0].startsWith("RTSP/")) {
            throw new RTSPException("Invalid RTSP response format: " + statusLine);
        }

        // Extract RTSP version, response code and message
        String rtspVersion = statusParts[0];
        int responseCode;
        try {
            responseCode = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException e) {
            throw new RTSPException("Invalid response code: " + statusParts[1]);
        }
        String responseMessage = statusParts[2];

        // Create response object
        RTSPResponse response = new RTSPResponse(rtspVersion, responseCode, responseMessage);

        // Read headers
        String line;
        while ((line = rtspReader.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim();
                String headerValue = line.substring(colonIndex + 1).trim();
                response.addHeaderValue(headerName, headerValue);
            }
        }

        return response;
    }
}
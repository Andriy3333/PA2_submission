/*
 * Author: Jonatan Schroeder
 * Updated: March 2022
 *
 * This code may not be used without written consent of the authors.
 */

package ca.yorku.rtsp.client.model;

import ca.yorku.rtsp.client.exception.RTSPException;
import ca.yorku.rtsp.client.net.RTSPConnection;

import java.util.*;

/**
 * This class manages an open session with an RTSP server. It provides the main interaction between the network
 * interface and the user interface.
 */
public class Session {

    private Set<SessionListener> sessionListeners = new HashSet<SessionListener>();
    private RTSPConnection rtspConnection;
    private String videoName = null;

    // Buffering elements
    private TreeSet<Frame> frameBuffer = new TreeSet<>();
    private Timer playbackTimer = null;
    private boolean isPlayingToUI = false;
    private boolean endOfStream = false;
    private Short lastPlayedSequence = null;

    // Server state tracking
    private boolean serverPlaying = false;

    /**
     * Creates a new RTSP session. This constructor will also create a new network connection with the server. No stream
     * setup is established at this point.
     *
     * @param server The IP address or host name of the RTSP server.
     * @param port   The port where the RTSP server is listening to.
     * @throws RTSPException If it was not possible to establish a connection with the server.
     */
    public Session(String server, int port) throws RTSPException {
        rtspConnection = new RTSPConnection(this, server, port);
    }

    /**
     * Adds a new listener interface to be called every time a session event (such as a change in video name or a new
     * frame) happens. Any interaction with user interfaces is done through these listeners.
     *
     * @param listener A SessionListener to be called when a session event happens.
     */
    public synchronized void addSessionListener(SessionListener listener) {
        sessionListeners.add(listener);
        listener.videoNameChanged(this.videoName);
    }

    /**
     * Removes an existing listener from the list of listeners to be called for session events.
     *
     * @param listener A SessionListener that should no longer be called when a session event happens.
     */
    public synchronized void removeSessionListener(SessionListener listener) {
        sessionListeners.remove(listener);
    }

    /**
     * Opens a new video file in the interface.
     *
     * @param videoName The name (URL) of the video to be opened. It should correspond to a local file in the server.
     */
    public synchronized void open(String videoName) {
        try {
            // Stop any existing playback
            if (playbackTimer != null) {
                playbackTimer.cancel();
                playbackTimer = null;
            }

            // Reset all state
            frameBuffer.clear();
            isPlayingToUI = false;
            endOfStream = false;
            lastPlayedSequence = null;
            serverPlaying = false;

            // Setup the connection with the new video
            rtspConnection.setup(videoName);
            this.videoName = videoName;

            // Start getting frames immediately (but don't display them)
            rtspConnection.play();
            serverPlaying = true;

            // Notify listeners about the new video
            for (SessionListener listener : sessionListeners) {
                listener.videoNameChanged(this.videoName);
            }
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    /**
     * Starts to play the existing file. It should only be called once a file has been opened. This function will return
     * immediately after the request was responded. Frames will be received in the background and will be handled by
     * the <code>processReceivedFrame</code> method. If the video has been paused previously, playback will resume where it
     * stopped.
     */
    public synchronized void play() {
        if (videoName == null) return;

        // Set flag to indicate we should be playing to UI
        isPlayingToUI = true;

        // Make sure server is sending frames if it's not already
        try {
            if (!serverPlaying) {
                rtspConnection.play();
                serverPlaying = true;
            }

            // Start playback immediately if we have any frames available
            if (!frameBuffer.isEmpty()) {
                // Send first frame immediately
                sendNextFrame();
                // Then start the timer for subsequent frames
                startPlayback();
            }
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    /**
     * Pauses the playback the existing file. It should only be called once a file has started playing. This function
     * will return immediately after the request was responded. The server might still send a few frames before stopping
     * the playback completely.
     */
    public synchronized void pause() {
        // Just stop sending frames to UI - don't affect server
        isPlayingToUI = false;

        // Stop the playback timer if it's running
        if (playbackTimer != null) {
            playbackTimer.cancel();
            playbackTimer = null;
        }
    }

    /**
     * Starts playback to UI at 25fps
     */
    private synchronized void startPlayback() {
        // Cancel any existing timer
        if (playbackTimer != null) {
            playbackTimer.cancel();
        }

        // Create new timer to send frames to UI at 25fps (40ms intervals)
        playbackTimer = new Timer();
        playbackTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendNextFrame();
            }
        }, 40, 40);  // Start after 40ms, then every 40ms
    }

    /**
     * Sends the next frame to the UI
     */
    private synchronized void sendNextFrame() {
        // Check if we should stop
        if (!isPlayingToUI || videoName == null) {
            if (playbackTimer != null) {
                playbackTimer.cancel();
                playbackTimer = null;
            }
            return;
        }

        // Check for empty buffer
        if (frameBuffer.isEmpty()) {
            // Stop playback timer
            if (playbackTimer != null) {
                playbackTimer.cancel();
                playbackTimer = null;
            }

            // If end of stream, notify listeners
            if (endOfStream) {
                for (SessionListener listener : sessionListeners) {
                    listener.videoEnded();
                }
                return;
            }

            // Make sure server is sending frames
            try {
                if (!serverPlaying) {
                    rtspConnection.play();
                    serverPlaying = true;
                }
            } catch (RTSPException e) {
                listenerException(e);
            }
            return;
        }

        // Get the next frame in sequence
        Frame nextFrame = frameBuffer.pollFirst();
        if (nextFrame != null) {
            // Update last played sequence
            lastPlayedSequence = nextFrame.getSequenceNumber();

            // Send frame to UI
            for (SessionListener listener : sessionListeners) {
                listener.frameReceived(nextFrame);
            }

            // Check buffer levels
            checkBufferLevels();
        }
    }

    /**
     * Check buffer levels and control server accordingly
     */
    private synchronized void checkBufferLevels() {
        try {
            // If buffer gets too full (100+ frames), pause server
            if (frameBuffer.size() >= 100 && serverPlaying) {
                rtspConnection.pause();
                serverPlaying = false;
            }
            // If buffer gets too low (less than 80 frames), resume server
            else if (frameBuffer.size() < 80 && !serverPlaying) {
                rtspConnection.play();
                serverPlaying = true;
            }
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    /**
     * Closes the currently open file. It should only be called once a file has been open.
     */
    public synchronized void close() {
        try {
            // Stop playback timer
            if (playbackTimer != null) {
                playbackTimer.cancel();
                playbackTimer = null;
            }

            // Reset state
            isPlayingToUI = false;
            frameBuffer.clear();
            endOfStream = false;
            lastPlayedSequence = null;
            serverPlaying = false;

            // Teardown connection
            rtspConnection.teardown();
            videoName = null;

            // Notify listeners
            for (SessionListener listener : sessionListeners) {
                listener.videoNameChanged(null);
            }
        } catch (RTSPException e) {
            listenerException(e);
        }
    }

    /**
     * Notifies listeners of an exception
     */
    private synchronized void listenerException(RTSPException e) {
        for (SessionListener listener : sessionListeners) {
            listener.exceptionThrown(e);
        }
    }

    /**
     * Closes the connection with the current server. This session element should not be used anymore after this point.
     */
    public synchronized void closeConnection() {
        // Stop playback timer
        if (playbackTimer != null) {
            playbackTimer.cancel();
            playbackTimer = null;
        }

        // Reset state
        isPlayingToUI = false;

        // Close connection
        rtspConnection.closeConnection();
    }

    /**
     * Processes a frame received from the RTSP server. This method
     * will buffer the frame and manage playback according to buffer levels.
     *
     * @param frame The recently received frame.
     */
    public synchronized void processReceivedFrame(Frame frame) {
        if (videoName == null) return;

        if (frame == null) {
            return;
        }

        // Check if empty frame (end of stream)
        if (frame.getPayloadLength() == 0) {
            endOfStream = true;

            // If we're supposed to be playing and have no frames buffered,
            // send the end notice to UI
            if (isPlayingToUI && frameBuffer.isEmpty()) {
                for (SessionListener listener : sessionListeners) {
                    listener.videoEnded();
                }
            }
            // If we're supposed to be playing but no timer running, start playback
            else if (isPlayingToUI && playbackTimer == null && !frameBuffer.isEmpty()) {
                sendNextFrame();  // Send first frame immediately
                startPlayback();
            }

            return;
        }

        // Drop frames that come too late
        if (lastPlayedSequence != null && frame.getSequenceNumber() <= lastPlayedSequence) {
            return;
        }

        // Add frame to buffer
        frameBuffer.add(frame);

        // Check buffer levels
        checkBufferLevels();

        // If we're supposed to be playing and have enough frames but no timer running, start playback
        if (isPlayingToUI && playbackTimer == null && frameBuffer.size() >= 50) {
            sendNextFrame();  // Send first frame immediately
            startPlayback();
        }
    }

    /**
     * Processes a notification received from the RTSP server that the
     * video ended. This method will mark the stream as ended.
     *
     * @param sequenceNumber The sequence number for the end
     * notification. Corresponds to the last frame plus one. Can be
     * used to identify a missing frame at the end of the stream.
     */
    public synchronized void videoEnded(int sequenceNumber) {
        endOfStream = true;

        // If we're supposed to be playing and have no frames buffered,
        // send the end notice to UI
        if (isPlayingToUI && frameBuffer.isEmpty()) {
            for (SessionListener listener : sessionListeners) {
                listener.videoEnded();
            }
        }
        // If we're supposed to be playing but no timer running, start playback
        else if (isPlayingToUI && playbackTimer == null && !frameBuffer.isEmpty()) {
            sendNextFrame();  // Send first frame immediately
            startPlayback();
        }
    }

    /**
     * Returns the name of the currently opened video.
     *
     * @return The name of the video currently open, or null if no video is open.
     */
    public synchronized String getVideoName() {
        return videoName;
    }
}
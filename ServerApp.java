/**
 * ============================================================================
 * ServerApp.java - Production-Grade Remote Desktop & Full-Duplex Chat Station
 * ============================================================================
 * 
 * ARCHITECTURE OVERVIEW & OSI MODEL MAPPING (Layers 4 through 7):
 * ----------------------------------------------------------------------------
 * Layer 4 (Transport Layer):
 *   - Utilizes standard TCP stream sockets (java.net.ServerSocket, java.net.Socket).
 *   - Employs dedicated TCP ports to isolate network traffic:
 *       * Port 5000: Asynchronous, low-bandwidth text messaging.
 *       * Port 5001: High-bandwidth, continuous video frame streaming.
 *       * Port 5002: Latency-critical, serialized mouse/keyboard control commands.
 *   - Configures TCP_NODELAY (socket.setTcpNoDelay(true)) on all sockets to
 *     disable Nagle's algorithm, guaranteeing immediate packet dispatch for
 *     interactive input events and real-time messaging.
 * 
 * Layer 5 (Session Layer):
 *   - Orchestrates multi-socket synchronization and connection lifecycle.
 *   - Initial connection handshake on Port 5001 transmits client native resolution
 *     (width, height) prior to streaming image frames.
 *   - Resilient session management: detects client disconnections, cleans up
 *     zombie streams, and smoothly returns to the listening state without crashing
 *     the UI or blocking the Swing Event Dispatch Thread (EDT).
 * 
 * Layer 6 (Presentation Layer):
 *   - Length-prefixed binary framing: Video frames are transmitted with a 4-byte
 *     Big-Endian integer prefix denoting payload length, read via readFully() to
 *     prevent buffer underflow and frame corruption.
 *   - Image decompression: Compressed JPEG bytes are decoded into BufferedImage
 *     objects in-memory using javax.imageio.ImageIO.
 *   - Character encoding: Text chat and control commands are encoded in standard
 *     UTF-8 character streams via BufferedReader and PrintWriter.
 * 
 * Layer 7 (Application Layer):
 *   - Swing GUI Desktop Application providing:
 *       * Real-time video canvas with aspect-ratio preservation and HUD overlay.
 *       * Full-duplex chat interface with timestamped messages and auto-scrolling.
 *       * System status bar monitoring active connections, client IP, resolution,
 *         and live frames per second (FPS).
 *   - Coordinate Scaling Engine:
 *       ClientX = (ServerEventX * ClientNativeWidth) / ServerPanelWidth
 *       ClientY = (ServerEventY * ClientNativeHeight) / ServerPanelHeight
 *   - Input Event Dispatcher: Captures mouse/keyboard events on the video canvas
 *     and serializes them into formatted command strings (MV, MP, MR, MW, KP, KR).
 * 
 * Compatibility: Standard JDK 8, 11, 17, and 21. No external libraries required.
 * ============================================================================
 */

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

public class ServerApp extends JFrame {

    // --- Networking Constants (Layer 4) ---
    public static final int CHAT_PORT = 5000;
    public static final int SCREEN_PORT = 5001;
    public static final int CONTROL_PORT = 5002;

    // --- Server Sockets ---
    private ServerSocket chatServerSocket;
    private ServerSocket screenServerSocket;
    private ServerSocket controlServerSocket;

    // --- Active Client Sockets ---
    private Socket chatSocket;
    private Socket screenSocket;
    private Socket controlSocket;

    // --- Network I/O Streams ---
    private BufferedReader chatReader;
    private PrintWriter chatWriter;
    private DataInputStream screenDis;
    private PrintWriter controlWriter;

    // --- Client Screen Resolution Metadata (Layer 5/7) ---
    private volatile int clientNativeWidth = 0;
    private volatile int clientNativeHeight = 0;

    // --- State & Performance Metrics ---
    private final AtomicBoolean isClientConnected = new AtomicBoolean(false);
    private final AtomicBoolean isServerRunning = new AtomicBoolean(true);
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private volatile int currentFps = 0;
    private volatile boolean remoteControlEnabled = true;

    // --- Server Network Info ---
    private String serverIpAddress = "Detecting...";

    // --- GUI Components (Swing) ---
    private ScreenCanvas screenCanvas;
    private JTextArea chatLogArea;
    private JTextField chatInputField;
    private JButton chatSendButton;
    private JLabel statusBadge;
    private JLabel clientIpLabel;
    private JLabel resolutionLabel;
    private JLabel fpsLabel;
    private JCheckBox remoteControlToggle;
    private JButton disconnectClientButton;

    // --- Thread Management ---
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private ScheduledExecutorService metricsScheduler;

    public ServerApp() {
        super("NetDesk - Remote Desktop & Full-Duplex Collaboration Station [SERVER]");
        detectServerIp();
        initUI();
        startServerListeners();
        startMetricsEngine();
    }

    /**
     * Detects the server machine's local IP address (non-loopback, site-local preferred).
     * Falls back to InetAddress.getLocalHost() if no suitable interface is found.
     */
    private void detectServerIp() {
        try {
            // Try to find a proper LAN IP (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        serverIpAddress = addr.getHostAddress();
                        return;
                    }
                }
            }
            // Fallback
            serverIpAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            serverIpAddress = "Unknown";
        }
    }

    /**
     * Initializes the modern dark-themed Swing User Interface.
     */
    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(960, 600));
        setLocationRelativeTo(null);

        // Dark Palette
        Color bgDark = new Color(24, 24, 27);        // Zinc 900
        Color panelDark = new Color(39, 39, 42);     // Zinc 800
        Color borderDark = new Color(63, 63, 70);    // Zinc 700
        Color textLight = new Color(244, 244, 245);  // Zinc 100
        Color accentBlue = new Color(59, 130, 246);  // Blue 500

        getContentPane().setBackground(bgDark);
        setLayout(new BorderLayout(0, 0));

        // -------------------------------------------------------------
        // TOP CONTROL & STATUS BAR
        // -------------------------------------------------------------
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(panelDark);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderDark),
                new EmptyBorder(8, 16, 8, 16)
        ));

        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        leftStatusPanel.setOpaque(false);

        statusBadge = new JLabel("Status: LISTENING ON PORTS 5000, 5001, 5002");
        statusBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
        statusBadge.setForeground(new Color(251, 191, 36)); // Amber / Warning

        // Server IP Label - shows this machine's IP for clients to connect
        JLabel serverIpInfoLabel = new JLabel("Server IP: " + serverIpAddress + " | Ports: 5000, 5001, 5002");
        serverIpInfoLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        serverIpInfoLabel.setForeground(new Color(96, 165, 250)); // Blue 400 - highlight

        JButton copyIpButton = new JButton("Copy IP");
        copyIpButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        copyIpButton.setBackground(new Color(59, 130, 246));
        copyIpButton.setForeground(Color.WHITE);
        copyIpButton.setFocusPainted(false);
        copyIpButton.setBorder(new EmptyBorder(3, 10, 3, 10));
        copyIpButton.setToolTipText("Copy Server IP to paste into ClientApp");
        copyIpButton.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(serverIpAddress), null);
            copyIpButton.setText("Copied!");
            copyIpButton.setBackground(new Color(16, 185, 129)); // Green
            Timer resetTimer = new Timer(1800, evt -> {
                copyIpButton.setText("Copy IP");
                copyIpButton.setBackground(new Color(59, 130, 246));
            });
            resetTimer.setRepeats(false);
            resetTimer.start();
        });

        clientIpLabel = new JLabel("Client IP: Disconnected");
        clientIpLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        clientIpLabel.setForeground(textLight);

        resolutionLabel = new JLabel("Client Screen: -- x --");
        resolutionLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        resolutionLabel.setForeground(textLight);

        fpsLabel = new JLabel("Stream: 0 FPS");
        fpsLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        fpsLabel.setForeground(new Color(52, 211, 153)); // Emerald green

        leftStatusPanel.add(statusBadge);
        leftStatusPanel.add(serverIpInfoLabel);
        leftStatusPanel.add(copyIpButton);
        leftStatusPanel.add(clientIpLabel);
        leftStatusPanel.add(resolutionLabel);
        leftStatusPanel.add(fpsLabel);

        JPanel rightControlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        rightControlPanel.setOpaque(false);

        remoteControlToggle = new JCheckBox("Remote Control Active", remoteControlEnabled);
        remoteControlToggle.setOpaque(false);
        remoteControlToggle.setForeground(textLight);
        remoteControlToggle.setFocusPainted(false);
        remoteControlToggle.setFont(new Font("SansSerif", Font.BOLD, 12));
        remoteControlToggle.addActionListener(e -> {
            remoteControlEnabled = remoteControlToggle.isSelected();
            screenCanvas.setRemoteControlState(remoteControlEnabled);
        });

        disconnectClientButton = new JButton("Disconnect Client");
        disconnectClientButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        disconnectClientButton.setBackground(new Color(220, 38, 38));
        disconnectClientButton.setForeground(Color.WHITE);
        disconnectClientButton.setFocusPainted(false);
        disconnectClientButton.setEnabled(false);
        disconnectClientButton.addActionListener(e -> disconnectCurrentClient());

        rightControlPanel.add(remoteControlToggle);
        rightControlPanel.add(disconnectClientButton);

        topBar.add(leftStatusPanel, BorderLayout.WEST);
        topBar.add(rightControlPanel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // -------------------------------------------------------------
        // CENTER: SPLIT PANE (SCREEN CANVAS ON LEFT, CHAT ON RIGHT)
        // -------------------------------------------------------------
        screenCanvas = new ScreenCanvas();
        screenCanvas.setBackground(new Color(15, 15, 18));
        screenCanvas.setServerIp(serverIpAddress);

        // Attach Input Listeners to Canvas for Remote Desktop Control
        setupCanvasControlListeners();

        // Chat Panel
        JPanel chatPanel = createChatPanel(panelDark, borderDark, textLight, accentBlue);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, screenCanvas, chatPanel);
        splitPane.setResizeWeight(0.72); // 72% screen, 28% chat
        splitPane.setDividerSize(4);
        splitPane.setBorder(null);
        splitPane.setBackground(borderDark);
        add(splitPane, BorderLayout.CENTER);

        // Graceful Window Close
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownServer();
            }
        });
    }

    /**
     * Builds the Full-Duplex Chat Panel UI.
     */
    private JPanel createChatPanel(Color panelDark, Color borderDark, Color textLight, Color accentBlue) {
        JPanel chatPanel = new JPanel(new BorderLayout(0, 0));
        chatPanel.setBackground(panelDark);
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, borderDark));

        // Chat Header
        JPanel chatHeader = new JPanel(new BorderLayout());
        chatHeader.setBackground(new Color(30, 30, 33));
        chatHeader.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel chatTitle = new JLabel("Full-Duplex Chat (Port 5000)");
        chatTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        chatTitle.setForeground(textLight);
        chatHeader.add(chatTitle, BorderLayout.WEST);
        chatPanel.add(chatHeader, BorderLayout.NORTH);

        // Chat History Log Area
        chatLogArea = new JTextArea();
        chatLogArea.setEditable(false);
        chatLogArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        chatLogArea.setBackground(new Color(24, 24, 27));
        chatLogArea.setForeground(new Color(228, 228, 231));
        chatLogArea.setLineWrap(true);
        chatLogArea.setWrapStyleWord(true);
        chatLogArea.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane chatScrollPane = new JScrollPane(chatLogArea);
        chatScrollPane.setBorder(null);
        chatPanel.add(chatScrollPane, BorderLayout.CENTER);

        // Chat Input Bar
        JPanel inputBar = new JPanel(new BorderLayout(8, 0));
        inputBar.setBackground(panelDark);
        inputBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderDark),
                new EmptyBorder(10, 10, 10, 10)
        ));

        chatInputField = new JTextField();
        chatInputField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        chatInputField.setBackground(new Color(24, 24, 27));
        chatInputField.setForeground(textLight);
        chatInputField.setCaretColor(textLight);
        chatInputField.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(borderDark, 1, true),
                new EmptyBorder(6, 8, 6, 8)
        ));
        chatInputField.addActionListener(e -> sendChatMessage());

        chatSendButton = new JButton("Send");
        chatSendButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        chatSendButton.setBackground(accentBlue);
        chatSendButton.setForeground(Color.WHITE);
        chatSendButton.setFocusPainted(false);
        chatSendButton.setBorder(new EmptyBorder(6, 16, 6, 16));
        chatSendButton.addActionListener(e -> sendChatMessage());

        inputBar.add(chatInputField, BorderLayout.CENTER);
        inputBar.add(chatSendButton, BorderLayout.EAST);
        chatPanel.add(inputBar, BorderLayout.SOUTH);

        return chatPanel;
    }

    /**
     * Sets up mouse and keyboard listeners on the video viewport.
     * Maps local viewport coordinate space into native client desktop coordinates.
     */
    private void setupCanvasControlListeners() {
        screenCanvas.setFocusable(true);

        // Request focus when mouse enters the viewport for immediate keyboard responsiveness
        screenCanvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                screenCanvas.requestFocusInWindow();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                screenCanvas.requestFocusInWindow();
                if (!remoteControlEnabled || !isClientConnected.get()) return;
                // Ensure remote cursor is at the exact coordinate before click occurs
                processAndSendMouseMove(e.getX(), e.getY());
                int mask = getMouseMask(e.getButton());
                if (mask != 0) {
                    sendControlPacket("MP:" + mask);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!remoteControlEnabled || !isClientConnected.get()) return;
                processAndSendMouseMove(e.getX(), e.getY());
                int mask = getMouseMask(e.getButton());
                if (mask != 0) {
                    sendControlPacket("MR:" + mask);
                }
            }
        });

        // Mouse Motion (Moves and Drags)
        screenCanvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                processAndSendMouseMove(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                processAndSendMouseMove(e.getX(), e.getY());
            }
        });

        // Mouse Wheel Scrolling
        screenCanvas.addMouseWheelListener(e -> {
            if (!remoteControlEnabled || !isClientConnected.get()) return;
            sendControlPacket("MW:" + e.getWheelRotation());
        });

        // Keyboard Actions
        screenCanvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!remoteControlEnabled || !isClientConnected.get()) return;
                sendControlPacket("KP:" + e.getKeyCode());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (!remoteControlEnabled || !isClientConnected.get()) return;
                sendControlPacket("KR:" + e.getKeyCode());
            }
        });
    }

    /**
     * Maps mouse button identifiers to InputEvent button down masks.
     * Uses InputEvent.getMaskForButton with safe fallback for maximum JDK compatibility.
     */
    private int getMouseMask(int button) {
        try {
            return InputEvent.getMaskForButton(button);
        } catch (Throwable t) {
            switch (button) {
                case MouseEvent.BUTTON1:
                    return InputEvent.BUTTON1_DOWN_MASK;
                case MouseEvent.BUTTON2:
                    return InputEvent.BUTTON2_DOWN_MASK;
                case MouseEvent.BUTTON3:
                    return InputEvent.BUTTON3_DOWN_MASK;
                default:
                    return 0;
            }
        }
    }

    /**
     * PROPORTIONAL COORDINATE SCALING ENGINE:
     * Calculates the exact proportional coordinates from Server viewport to Client display:
     *   ClientX = (ServerEventX * ClientNativeWidth) / ServerPanelWidth
     *   ClientY = (ServerEventY * ClientNativeHeight) / ServerPanelHeight
     */
    private void processAndSendMouseMove(int eventX, int eventY) {
        if (!remoteControlEnabled || !isClientConnected.get()) return;

        int panelW = screenCanvas.getWidth();
        int panelH = screenCanvas.getHeight();

        if (panelW <= 0 || panelH <= 0 || clientNativeWidth <= 0 || clientNativeHeight <= 0) {
            return;
        }

        // Apply Proportional Scaling Formula
        int clientX = (int) Math.round(((double) eventX * (double) clientNativeWidth) / (double) panelW);
        int clientY = (int) Math.round(((double) eventY * (double) clientNativeHeight) / (double) panelH);

        // Boundary Clamping to prevent out-of-bounds pointer exceptions on client
        clientX = Math.max(0, Math.min(clientNativeWidth - 1, clientX));
        clientY = Math.max(0, Math.min(clientNativeHeight - 1, clientY));

        sendControlPacket("MV:" + clientX + ":" + clientY);
    }

    /**
     * Transmits a serialized control packet over Port 5002 with immediate buffer flush.
     */
    private synchronized void sendControlPacket(String packet) {
        if (controlWriter != null && isClientConnected.get()) {
            controlWriter.println(packet);
            controlWriter.flush(); // Crucial: Flushes TCP buffer to eliminate command latency
        }
    }

    /**
     * Transmits a text chat message over Port 5000.
     */
    private synchronized void sendChatMessage() {
        String message = chatInputField.getText().trim();
        if (message.isEmpty()) return;

        if (!isClientConnected.get() || chatWriter == null) {
            appendChat("System", "Cannot send message: No client connected.");
            return;
        }

        try {
            chatWriter.println(message);
            chatWriter.flush();
            appendChat("Me (Server)", message);
            chatInputField.setText("");
        } catch (Exception ex) {
            appendChat("System", "Failed to send message: " + ex.getMessage());
        }
    }

    /**
     * Appends a formatted line to the chat transcript area in a thread-safe manner.
     */
    private void appendChat(String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            chatLogArea.append(String.format("[%s] %s: %s%n", timestamp, sender, message));
            chatLogArea.setCaretPosition(chatLogArea.getDocument().getLength());
        });
    }

    /**
     * Starts listening threads for Ports 5000, 5001, and 5002.
     */
    private void startServerListeners() {
        networkExecutor.submit(() -> {
            try {
                chatServerSocket = new ServerSocket(CHAT_PORT);
                screenServerSocket = new ServerSocket(SCREEN_PORT);
                controlServerSocket = new ServerSocket(CONTROL_PORT);

                // Re-use addresses to prevent bind failures on rapid restarts
                chatServerSocket.setReuseAddress(true);
                screenServerSocket.setReuseAddress(true);
                controlServerSocket.setReuseAddress(true);

                appendChat("System", "Server IP: " + serverIpAddress);
                appendChat("System", "Server listening on Ports 5000 (Chat), 5001 (Screen), 5002 (Control).");

                while (isServerRunning.get()) {
                    updateStatus(false, null);
                    appendChat("System", "Waiting for incoming client connections...");

                    // Accept connections across all 3 ports
                    Socket cSock = chatServerSocket.accept();
                    Socket sSock = screenServerSocket.accept();
                    Socket ctrlSock = controlServerSocket.accept();

                    // Apply TCP_NODELAY to disable Nagle's algorithm for low-latency transmission
                    cSock.setTcpNoDelay(true);
                    sSock.setTcpNoDelay(true);
                    ctrlSock.setTcpNoDelay(true);

                    // Sockets verified - initialize session
                    chatSocket = cSock;
                    screenSocket = sSock;
                    controlSocket = ctrlSock;

                    chatReader = new BufferedReader(new InputStreamReader(chatSocket.getInputStream(), StandardCharsets.UTF_8));
                    chatWriter = new PrintWriter(new OutputStreamWriter(chatSocket.getOutputStream(), StandardCharsets.UTF_8), true);

                    screenDis = new DataInputStream(new BufferedInputStream(screenSocket.getInputStream(), 128 * 1024));

                    controlWriter = new PrintWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8), true);

                    isClientConnected.set(true);
                    String clientIp = chatSocket.getInetAddress().getHostAddress();
                    updateStatus(true, clientIp);
                    appendChat("System", "Client connected from " + clientIp + " on all 3 channels.");

                    // Launch Dedicated Channel Threads
                    // Latch = 2: Both chatReceiver AND screenReceiver must finish
                    // before session cleanup runs. This ensures full-duplex operation
                    // where chat and screen share run simultaneously without one
                    // killing the other prematurely.
                    CountDownLatch sessionLatch = new CountDownLatch(2);

                    // Thread A: Chat Receiver
                    networkExecutor.submit(() -> runChatReceiver(sessionLatch));

                    // Thread B: Screen Receiver (Video Pipeline)
                    networkExecutor.submit(() -> runScreenReceiver(sessionLatch));

                    // Await session termination
                    try {
                        sessionLatch.await();
                    } catch (InterruptedException ignored) {
                    }

                    // Perform graceful session cleanup and prepare for next client
                    cleanupClientSession();
                }
            } catch (IOException e) {
                if (isServerRunning.get()) {
                    appendChat("System", "Server socket exception: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Dedicated Thread A: Asynchronous Full-Duplex Chat Receiver (Port 5000).
     */
    private void runChatReceiver(CountDownLatch sessionLatch) {
        try {
            String incomingLine;
            while (isClientConnected.get() && (incomingLine = chatReader.readLine()) != null) {
                appendChat("Client", incomingLine);
            }
        } catch (IOException e) {
            // Channel broken or client disconnected
        } finally {
            sessionLatch.countDown();
        }
    }

    /**
     * Dedicated Thread B: High-Throughput Screen Frame Receiver (Port 5001).
     * Eliminates buffer underflow by reading 4-byte length prefix followed by readFully().
     */
    private void runScreenReceiver(CountDownLatch sessionLatch) {
        try {
            // Layer 5 Handshake: Receive Client Native Screen Resolution
            clientNativeWidth = screenDis.readInt();
            clientNativeHeight = screenDis.readInt();

            SwingUtilities.invokeLater(() -> {
                resolutionLabel.setText(String.format("Client Screen: %d x %d", clientNativeWidth, clientNativeHeight));
                screenCanvas.setClientNativeResolution(clientNativeWidth, clientNativeHeight);
            });

            appendChat("System", String.format("Client screen resolution recognized: %dx%d", clientNativeWidth, clientNativeHeight));

            byte[] buffer = new byte[256 * 1024]; // Reusable buffer

            // Video Streaming Frame Loop (15 - 30 FPS target)
            while (isClientConnected.get()) {
                // Layer 6: Read frame payload length (4-byte Big-Endian integer)
                int frameLength = screenDis.readInt();
                if (frameLength <= 0 || frameLength > 20 * 1024 * 1024) { // Safety ceiling: 20MB
                    throw new IOException("Invalid frame payload size: " + frameLength);
                }

                // Grow buffer if needed
                if (buffer.length < frameLength) {
                    buffer = new byte[frameLength + 32 * 1024];
                }

                // Layer 6: Read complete image bytes without underflow
                screenDis.readFully(buffer, 0, frameLength);

                // Layer 6: Decompress in-memory JPEG to BufferedImage
                ByteArrayInputStream bais = new ByteArrayInputStream(buffer, 0, frameLength);
                BufferedImage frame = ImageIO.read(bais);

                if (frame != null) {
                    screenCanvas.updateFrame(frame);
                    frameCounter.incrementAndGet();
                }
            }
        } catch (EOFException e) {
            // Clean EOF on stream close
        } catch (IOException e) {
            // Socket broken or client disconnected
        } finally {
            sessionLatch.countDown();
        }
    }

    /**
     * Background scheduler computing real-time stream frames per second (FPS).
     */
    private void startMetricsEngine() {
        metricsScheduler = Executors.newSingleThreadScheduledExecutor();
        metricsScheduler.scheduleAtFixedRate(() -> {
            currentFps = frameCounter.getAndSet(0);
            SwingUtilities.invokeLater(() -> {
                if (isClientConnected.get()) {
                    fpsLabel.setText("Stream: " + currentFps + " FPS");
                } else {
                    fpsLabel.setText("Stream: 0 FPS");
                }
            });
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Updates the status badges and buttons on the UI thread.
     */
    private void updateStatus(boolean connected, String ip) {
        SwingUtilities.invokeLater(() -> {
            if (connected) {
                statusBadge.setText("Status: CONNECTED (Streaming)");
                statusBadge.setForeground(new Color(52, 211, 153)); // Emerald green
                clientIpLabel.setText("Client IP: " + (ip != null ? ip : "Unknown"));
                disconnectClientButton.setEnabled(true);
            } else {
                statusBadge.setText("Status: LISTENING ON PORTS 5000, 5001, 5002");
                statusBadge.setForeground(new Color(251, 191, 36)); // Amber
                clientIpLabel.setText("Client IP: Disconnected");
                resolutionLabel.setText("Client Screen: -- x --");
                fpsLabel.setText("Stream: 0 FPS");
                disconnectClientButton.setEnabled(false);
                screenCanvas.clearFrame();
            }
        });
    }

    /**
     * Disconnects the active client session safely.
     */
    private void disconnectCurrentClient() {
        isClientConnected.set(false);
        cleanupClientSession();
    }

    /**
     * Closes current client sockets and releases streams.
     */
    private synchronized void cleanupClientSession() {
        isClientConnected.set(false);
        try { if (chatSocket != null && !chatSocket.isClosed()) chatSocket.close(); } catch (Exception ignored) {}
        try { if (screenSocket != null && !screenSocket.isClosed()) screenSocket.close(); } catch (Exception ignored) {}
        try { if (controlSocket != null && !controlSocket.isClosed()) controlSocket.close(); } catch (Exception ignored) {}
        chatSocket = null;
        screenSocket = null;
        controlSocket = null;
        chatReader = null;
        chatWriter = null;
        screenDis = null;
        controlWriter = null;
        updateStatus(false, null);
    }

    /**
     * Shuts down all servers and terminates executors on application exit.
     */
    private void shutdownServer() {
        isServerRunning.set(false);
        cleanupClientSession();
        try { if (chatServerSocket != null) chatServerSocket.close(); } catch (Exception ignored) {}
        try { if (screenServerSocket != null) screenServerSocket.close(); } catch (Exception ignored) {}
        try { if (controlServerSocket != null) controlServerSocket.close(); } catch (Exception ignored) {}
        if (metricsScheduler != null) metricsScheduler.shutdownNow();
        networkExecutor.shutdownNow();
    }

    // =========================================================================
    // CUSTOM SCREEN CANVAS COMPONENT (Rendering & Live Viewport)
    // =========================================================================
    private static class ScreenCanvas extends JPanel {
        private volatile BufferedImage currentFrame = null;
        private volatile boolean remoteControlActive = true;
        private int nativeW = 0;
        private int nativeH = 0;
        private String serverIp = "Detecting...";

        public ScreenCanvas() {
            setDoubleBuffered(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        public void updateFrame(BufferedImage frame) {
            this.currentFrame = frame;
            repaint();
        }

        public void clearFrame() {
            this.currentFrame = null;
            repaint();
        }

        public void setRemoteControlState(boolean active) {
            this.remoteControlActive = active;
            setCursor(Cursor.getPredefinedCursor(active ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }

        public void setClientNativeResolution(int w, int h) {
            this.nativeW = w;
            this.nativeH = h;
            repaint();
        }

        public void setServerIp(String ip) {
            this.serverIp = ip;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int panelW = getWidth();
            int panelH = getHeight();

            BufferedImage frame = currentFrame;
            if (frame != null) {
                // Render the received desktop frame scaled across the viewport panel
                g2.drawImage(frame, 0, 0, panelW, panelH, null);

                // Small HUD Indicator in top-left of canvas
                g2.setColor(new Color(0, 0, 0, 140));
                g2.fillRoundRect(10, 10, 180, 24, 8, 8);
                g2.setColor(remoteControlActive ? new Color(52, 211, 153) : new Color(248, 113, 113));
                g2.fillOval(18, 18, 8, 8);
                g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                g2.setColor(Color.WHITE);
                g2.drawString(remoteControlActive ? "CONTROL ACTIVE" : "VIEW ONLY", 32, 26);
            } else {
                // Idle / Waiting Background
                g2.setColor(new Color(18, 18, 20));
                g2.fillRect(0, 0, panelW, panelH);

                // Main waiting message
                g2.setFont(new Font("SansSerif", Font.BOLD, 18));
                g2.setColor(new Color(113, 113, 122));
                String message = "Waiting for Client Desktop Stream on Port 5001...";
                FontMetrics fm = g2.getFontMetrics();
                int x = (panelW - fm.stringWidth(message)) / 2;
                int y = panelH / 2 - 40;
                g2.drawString(message, x, y);

                // Server IP and Port info box
                g2.setColor(new Color(30, 30, 40, 220));
                int boxW = 500;
                int boxH = 80;
                int boxX = (panelW - boxW) / 2;
                int boxY = y + 15;
                g2.fillRoundRect(boxX, boxY, boxW, boxH, 12, 12);
                g2.setColor(new Color(75, 85, 99));
                g2.drawRoundRect(boxX, boxY, boxW, boxH, 12, 12);

                // Server IP line
                g2.setFont(new Font("SansSerif", Font.BOLD, 15));
                g2.setColor(new Color(96, 165, 250)); // Blue 400
                String ipLine = "Server IP:  " + serverIp;
                FontMetrics fmIp = g2.getFontMetrics();
                g2.drawString(ipLine, (panelW - fmIp.stringWidth(ipLine)) / 2, boxY + 28);

                // Ports line
                g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
                g2.setColor(new Color(52, 211, 153)); // Emerald
                String portLine = "Channels:  Port 5000 (Chat)  |  Port 5001 (Screen)  |  Port 5002 (Control)";
                FontMetrics fmPort = g2.getFontMetrics();
                g2.drawString(portLine, (panelW - fmPort.stringWidth(portLine)) / 2, boxY + 50);

                // Quick hint
                g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g2.setColor(new Color(209, 213, 219));
                String hintLine = "👉 Enter this Server IP into ClientApp and click 'Connect'";
                FontMetrics fmHint = g2.getFontMetrics();
                g2.drawString(hintLine, (panelW - fmHint.stringWidth(hintLine)) / 2, boxY + 70);

                // Subtitle
                g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
                g2.setColor(new Color(113, 113, 122));
                String sub = "Ensure Client machine is on the same network or VPN.";
                FontMetrics fmSub = g2.getFontMetrics();
                g2.drawString(sub, (panelW - fmSub.stringWidth(sub)) / 2, boxY + boxH + 22);
            }
            g2.dispose();
        }
    }

    // =========================================================================
    // MAIN LAUNCHER
    // =========================================================================
    public static void main(String[] args) {
        // Apply system-native or clean cross-platform look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            ServerApp server = new ServerApp();
            server.setVisible(true);
        });
    }
}

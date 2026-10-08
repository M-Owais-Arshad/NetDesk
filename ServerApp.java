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
    private JButton focusViewButton;
    private JButton aspectRatioButton;
    private JSplitPane mainSplitPane;

    // --- High-Performance Mouse Throttling Engine ---
    private final java.util.concurrent.atomic.AtomicLong lastMouseMoveTime = new java.util.concurrent.atomic.AtomicLong(0);
    private static final long MOUSE_MOVE_THROTTLE_MS = 10; // Max 100 packets/sec for zero lag and silky smooth cursor

    // --- Thread Management ---
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private ScheduledExecutorService metricsScheduler;

    public ServerApp() {
        super("Zeta-NetDesk - Remote Desktop & Full-Duplex Collaboration Station [SERVER]");
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
        // TOP CONTROL & HEADER BAR (Clean, Uncrowded & Non-colliding)
        // -------------------------------------------------------------
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(panelDark);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderDark),
                new EmptyBorder(8, 16, 8, 16)
        ));

        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        leftStatusPanel.setOpaque(false);

        statusBadge = new JLabel("● LISTENING");
        statusBadge.setFont(new Font("SansSerif", Font.BOLD, 13));
        statusBadge.setForeground(new Color(251, 191, 36)); // Amber

        JLabel serverIpInfoLabel = new JLabel("Server IP: " + serverIpAddress);
        serverIpInfoLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        serverIpInfoLabel.setForeground(new Color(96, 165, 250)); // Blue 400

        JButton copyIpButton = createStyledButton("📋 Copy IP", new Color(37, 99, 235), Color.WHITE);
        copyIpButton.setToolTipText("Copy Server IP to paste into ClientApp");
        copyIpButton.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(serverIpAddress), null);
            copyIpButton.setText("✓ Copied!");
            copyIpButton.setBackground(new Color(16, 185, 129)); // Green
            Timer resetTimer = new Timer(1800, evt -> {
                copyIpButton.setText("📋 Copy IP");
                copyIpButton.setBackground(new Color(37, 99, 235));
            });
            resetTimer.setRepeats(false);
            resetTimer.start();
        });

        leftStatusPanel.add(statusBadge);
        leftStatusPanel.add(serverIpInfoLabel);
        leftStatusPanel.add(copyIpButton);

        // Control buttons in Top Right
        JPanel rightControlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightControlPanel.setOpaque(false);

        aspectRatioButton = createStyledButton("16:9 Fit", new Color(63, 63, 70), Color.WHITE);
        aspectRatioButton.setToolTipText("Toggle between aspect-ratio letterboxing and full stretch");
        aspectRatioButton.addActionListener(e -> {
            boolean current = screenCanvas.isFitAspectRatio();
            screenCanvas.setFitAspectRatio(!current);
            aspectRatioButton.setText(!current ? "16:9 Fit" : "Stretch");
        });

        focusViewButton = createStyledButton("⛶ Focus Screen", new Color(63, 63, 70), Color.WHITE);
        focusViewButton.setToolTipText("Toggle Full Screen remote display focus");
        focusViewButton.addActionListener(e -> toggleFocusView());

        remoteControlToggle = new JCheckBox("Remote Control", remoteControlEnabled);
        remoteControlToggle.setOpaque(false);
        remoteControlToggle.setForeground(textLight);
        remoteControlToggle.setFocusPainted(false);
        remoteControlToggle.setFont(new Font("SansSerif", Font.BOLD, 12));
        remoteControlToggle.addActionListener(e -> {
            remoteControlEnabled = remoteControlToggle.isSelected();
            screenCanvas.setRemoteControlState(remoteControlEnabled);
        });

        disconnectClientButton = createStyledButton("✖ Disconnect", new Color(63, 63, 70), new Color(161, 161, 170));
        disconnectClientButton.setEnabled(false);
        disconnectClientButton.addActionListener(e -> disconnectCurrentClient());

        rightControlPanel.add(aspectRatioButton);
        rightControlPanel.add(focusViewButton);
        rightControlPanel.add(remoteControlToggle);
        rightControlPanel.add(disconnectClientButton);

        topBar.add(leftStatusPanel, BorderLayout.WEST);
        topBar.add(rightControlPanel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // -------------------------------------------------------------
        // BOTTOM TELEMETRY FOOTER BAR (Sleek & Non-Intrusive)
        // -------------------------------------------------------------
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(new Color(18, 18, 20));
        bottomBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderDark),
                new EmptyBorder(6, 16, 6, 16)
        ));

        JPanel bottomLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        bottomLeft.setOpaque(false);

        clientIpLabel = new JLabel("Client: Disconnected");
        clientIpLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        clientIpLabel.setForeground(new Color(161, 161, 170));

        resolutionLabel = new JLabel("Screen: -- x --");
        resolutionLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        resolutionLabel.setForeground(new Color(161, 161, 170));

        bottomLeft.add(clientIpLabel);
        bottomLeft.add(resolutionLabel);

        JPanel bottomRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        bottomRight.setOpaque(false);

        fpsLabel = new JLabel("Stream: 0 FPS");
        fpsLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        fpsLabel.setForeground(new Color(52, 211, 153)); // Emerald green

        JLabel channelInfoLabel = new JLabel("TCP Channels: 5000 (Chat) | 5001 (Stream) | 5002 (Control)");
        channelInfoLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        channelInfoLabel.setForeground(new Color(113, 113, 122));

        bottomRight.add(fpsLabel);
        bottomRight.add(channelInfoLabel);

        bottomBar.add(bottomLeft, BorderLayout.WEST);
        bottomBar.add(bottomRight, BorderLayout.EAST);
        add(bottomBar, BorderLayout.SOUTH);



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

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, screenCanvas, chatPanel);
        mainSplitPane.setResizeWeight(0.72); // 72% screen, 28% chat
        mainSplitPane.setDividerSize(4);
        mainSplitPane.setBorder(null);
        mainSplitPane.setBackground(borderDark);
        add(mainSplitPane, BorderLayout.CENTER);

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

        JButton clearChatBtn = new JButton("Clear Log");
        clearChatBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        clearChatBtn.setBackground(new Color(63, 63, 70));
        clearChatBtn.setForeground(Color.LIGHT_GRAY);
        clearChatBtn.setFocusPainted(false);
        clearChatBtn.setBorder(new EmptyBorder(2, 8, 2, 8));
        clearChatBtn.addActionListener(e -> chatLogArea.setText(""));

        chatHeader.add(chatTitle, BorderLayout.WEST);
        chatHeader.add(clearChatBtn, BorderLayout.EAST);
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

        chatSendButton = createStyledButton("Send", accentBlue, Color.WHITE);
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

        // Mouse Motion (Moves and Drags) with Throttle Pacing for zero lag
        screenCanvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                long now = System.currentTimeMillis();
                if (now - lastMouseMoveTime.get() >= MOUSE_MOVE_THROTTLE_MS) {
                    lastMouseMoveTime.set(now);
                    processAndSendMouseMove(e.getX(), e.getY());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                long now = System.currentTimeMillis();
                if (now - lastMouseMoveTime.get() >= MOUSE_MOVE_THROTTLE_MS) {
                    lastMouseMoveTime.set(now);
                    processAndSendMouseMove(e.getX(), e.getY());
                }
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
     * Toggles between standard split layout and Focus Screen mode.
     */
    private void toggleFocusView() {
        if (mainSplitPane == null) return;
        int totalWidth = mainSplitPane.getWidth();
        if (totalWidth <= 0) return;
        int currentDiv = mainSplitPane.getDividerLocation();
        if (currentDiv > totalWidth * 0.90) {
            mainSplitPane.setDividerLocation(0.72);
            focusViewButton.setText("⛶ Focus Screen");
        } else {
            mainSplitPane.setDividerLocation(1.0);
            focusViewButton.setText("◫ Split View");
        }
    }

    /**
     * PROPORTIONAL COORDINATE SCALING ENGINE:
     * Calculates the exact proportional coordinates from Server viewport to Client display.
     * Uses letterbox boundary translation for pixel-perfect precision.
     */
    private void processAndSendMouseMove(int eventX, int eventY) {
        if (!remoteControlEnabled || !isClientConnected.get()) return;

        Point pt = screenCanvas.translateToClient(eventX, eventY);
        if (pt != null) {
            sendControlPacket("MV:" + pt.x + ":" + pt.y);
        }
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

                    // Apply TCP_NODELAY and high-throughput buffer sizes
                    cSock.setTcpNoDelay(true);
                    sSock.setTcpNoDelay(true);
                    ctrlSock.setTcpNoDelay(true);

                    cSock.setSendBufferSize(64 * 1024);
                    cSock.setReceiveBufferSize(64 * 1024);
                    sSock.setSendBufferSize(512 * 1024);
                    sSock.setReceiveBufferSize(512 * 1024);
                    ctrlSock.setSendBufferSize(64 * 1024);
                    ctrlSock.setReceiveBufferSize(64 * 1024);

                    // Sockets verified - initialize session
                    chatSocket = cSock;
                    screenSocket = sSock;
                    controlSocket = ctrlSock;

                    chatReader = new BufferedReader(new InputStreamReader(chatSocket.getInputStream(), StandardCharsets.UTF_8));
                    chatWriter = new PrintWriter(new OutputStreamWriter(chatSocket.getOutputStream(), StandardCharsets.UTF_8), true);

                    screenDis = new DataInputStream(new BufferedInputStream(screenSocket.getInputStream(), 256 * 1024));

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
            } catch (BindException be) {
                logCrash("Port binding conflict on ServerApp", be);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(ServerApp.this,
                            "Port Conflict Detected:\nOne or more required ports (" + CHAT_PORT + ", " + SCREEN_PORT + ", " + CONTROL_PORT + ") are already in use.\n\n" +
                            "Please close any previous NetDesk window or terminate running Java processes.",
                            "Port Conflict Error", JOptionPane.ERROR_MESSAGE);
                });
            } catch (IOException e) {
                if (isServerRunning.get()) {
                    logCrash("Server socket exception", e);
                    appendChat("System", "Server socket exception: " + e.getMessage());
                }
            } catch (Exception e) {
                if (isServerRunning.get()) {
                    logCrash("Server listener unexpected exception", e);
                    appendChat("System", "Server listener error: " + e.getMessage());
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
        } catch (Exception e) {
            logCrash("Chat receiver exception", e);
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
        } catch (Exception e) {
            logCrash("Screen receiver exception", e);
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
                statusBadge.setText("● CONNECTED");
                statusBadge.setForeground(new Color(52, 211, 153)); // Emerald green
                clientIpLabel.setText("Client: " + (ip != null ? ip : "Unknown"));
                clientIpLabel.setForeground(new Color(52, 211, 153));
                disconnectClientButton.setEnabled(true);
                disconnectClientButton.setBackground(new Color(220, 38, 38));
                disconnectClientButton.setForeground(Color.WHITE);
            } else {
                statusBadge.setText("● LISTENING (Ports 5000, 5001, 5002)");
                statusBadge.setForeground(new Color(251, 191, 36)); // Amber
                clientIpLabel.setText("Client: Disconnected");
                clientIpLabel.setForeground(new Color(161, 161, 170));
                resolutionLabel.setText("Screen: -- x --");
                fpsLabel.setText("Stream: 0 FPS");
                disconnectClientButton.setEnabled(false);
                disconnectClientButton.setBackground(new Color(63, 63, 70));
                disconnectClientButton.setForeground(new Color(161, 161, 170));
                screenCanvas.clearFrame();
            }
        });
    }

    /**
     * Creates a modern, high-contrast styled button that renders reliably across all OS platforms.
     */
    public static JButton createStyledButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("SansSerif", Font.BOLD, 11));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(bg.brighter(), 1, true),
                new EmptyBorder(5, 12, 5, 12)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) {
                    btn.setBackground(bg.brighter());
                }
            }
            @Override
            public void mouseExited(MouseEvent e) {
                if (btn.isEnabled()) {
                    btn.setBackground(bg);
                }
            }
        });
        return btn;
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
        private volatile boolean fitAspectRatio = true;
        private int nativeW = 0;
        private int nativeH = 0;
        private String serverIp = "Detecting...";

        // Viewport placement metrics
        private volatile int drawX = 0;
        private volatile int drawY = 0;
        private volatile int drawW = 0;
        private volatile int drawH = 0;

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

        public void setFitAspectRatio(boolean fit) {
            this.fitAspectRatio = fit;
            repaint();
        }

        public boolean isFitAspectRatio() {
            return fitAspectRatio;
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

        /**
         * Maps local mouse coordinate to client screen coordinate space with letterbox awareness.
         */
        public Point translateToClient(int eventX, int eventY) {
            int curW = drawW;
            int curH = drawH;
            int curX = drawX;
            int curY = drawY;
            if (curW <= 0 || curH <= 0 || nativeW <= 0 || nativeH <= 0) return null;
            if (eventX < curX || eventX > curX + curW || eventY < curY || eventY > curY + curH) {
                return null; // Cursor is outside the active display viewport
            }
            int clientX = (int) Math.round(((double)(eventX - curX) * nativeW) / curW);
            int clientY = (int) Math.round(((double)(eventY - curY) * nativeH) / curH);
            clientX = Math.max(0, Math.min(nativeW - 1, clientX));
            clientY = Math.max(0, Math.min(nativeH - 1, clientY));
            return new Point(clientX, clientY);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

            int panelW = getWidth();
            int panelH = getHeight();

            BufferedImage frame = currentFrame;
            if (frame != null) {
                int dX = 0, dY = 0, dW = panelW, dH = panelH;
                if (fitAspectRatio && nativeW > 0 && nativeH > 0) {
                    double imgAspect = (double) nativeW / (double) nativeH;
                    double panelAspect = (double) panelW / (double) panelH;
                    if (panelAspect > imgAspect) {
                        dH = panelH;
                        dW = (int) Math.round(panelH * imgAspect);
                        dX = (panelW - dW) / 2;
                        dY = 0;
                    } else {
                        dW = panelW;
                        dH = (int) Math.round(panelW / imgAspect);
                        dX = 0;
                        dY = (panelH - dH) / 2;
                    }
                    // Letterbox background
                    g2.setColor(new Color(12, 12, 14));
                    g2.fillRect(0, 0, panelW, panelH);
                }
                drawX = dX;
                drawY = dY;
                drawW = dW;
                drawH = dH;

                // Render the received desktop frame scaled across the viewport panel
                g2.drawImage(frame, dX, dY, dW, dH, null);

                // Small HUD Indicator in top-left of canvas
                g2.setColor(new Color(0, 0, 0, 160));
                g2.fillRoundRect(12, 12, 190, 26, 8, 8);
                g2.setColor(remoteControlActive ? new Color(52, 211, 153) : new Color(248, 113, 113));
                g2.fillOval(20, 21, 8, 8);
                g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                g2.setColor(Color.WHITE);
                g2.drawString(remoteControlActive ? "CONTROL ACTIVE" : "VIEW ONLY", 34, 29);
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
    // CRASH REPORTING & DIAGNOSTICS ENGINE
    // =========================================================================
    public static void logCrash(String context, Throwable t) {
        try (PrintWriter pw = new PrintWriter(new FileWriter("netdesk_crash.log", true))) {
            pw.println("==================================================");
            pw.println("NETDESK CRASH REPORT - " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println("Role: Server Station (ServerApp)");
            pw.println("Context: " + context);
            pw.println("Java Version: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            pw.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
            pw.println("Exception: " + t.toString());
            t.printStackTrace(pw);
            pw.println("==================================================");
            pw.println();
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // MAIN LAUNCHER
    // =========================================================================
    public static void main(String[] args) {
        // Register Global Uncaught Exception Handler
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            logCrash("Uncaught exception in thread [" + t.getName() + "]", ex);
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null,
                        "An unexpected error occurred in NetDesk Server:\n" + ex.toString() +
                        "\n\nDetails have been logged to 'netdesk_crash.log'.",
                        "NetDesk Server Error", JOptionPane.ERROR_MESSAGE);
            });
        });

        // Apply system-native or clean cross-platform look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            try {
                ServerApp server = new ServerApp();
                server.setVisible(true);
            } catch (Throwable t) {
                logCrash("Fatal startup error in ServerApp", t);
                JOptionPane.showMessageDialog(null,
                        "Failed to start NetDesk Server:\n" + t.toString() +
                        "\n\nDetails saved to 'netdesk_crash.log'.",
                        "Startup Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}

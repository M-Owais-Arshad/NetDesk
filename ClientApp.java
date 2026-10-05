/**
 * ============================================================================
 * ClientApp.java - Production-Grade Remote Screen Streamer & Execution Engine
 * ============================================================================
 * 
 * ARCHITECTURE OVERVIEW & OSI MODEL MAPPING (Layers 4 through 7):
 * ----------------------------------------------------------------------------
 * Layer 4 (Transport Layer):
 *   - Establishes dedicated TCP client connections (java.net.Socket) to the
 *     server host on three isolated channels:
 *       * Port 5000: Full-Duplex Asynchronous Chat.
 *       * Port 5001: High-Throughput Desktop Frame Streaming.
 *       * Port 5002: Latency-Sensitive Remote Control Execution.
 *   - Sets TCP_NODELAY (socket.setTcpNoDelay(true)) across all three client
 *     sockets, disabling Nagle's packet aggregation to achieve instantaneous
 *     event execution and zero-lag interactive response.
 * 
 * Layer 5 (Session Layer):
 *   - Coordinates multi-socket session initialization and handshake.
 *   - During connection setup on Port 5001, transmits client machine's native
 *     screen dimensions (width and height as 4-byte integers) before initiating
 *     the video capture stream.
 *   - State-machine session tracking (DISCONNECTED -> CONNECTING -> CONNECTED).
 *   - Catches socket disconnects or transmission failures and gracefully restores
 *     the UI to the disconnected state, releasing background threads and resources.
 * 
 * Layer 6 (Presentation Layer):
 *   - Screen capture compression: Frames captured via java.awt.Robot are encoded
 *     in-memory into standard JPEG format using javax.imageio.ImageIO with a
 *     ByteArrayOutputStream.
 *   - Protocol Framing: Precedes each binary JPEG payload with a 4-byte Big-Endian
 *     integer denoting payload size (dos.writeInt(length)), ensuring the server
 *     can consume the exact frame byte boundary without underflow or fragmentation.
 *   - String deserialization: Character streams are formatted and parsed using
 *     UTF-8 encoding (StandardCharsets.UTF_8).
 * 
 * Layer 7 (Application Layer):
 *   - Screen Capturing Engine: Captures desktop surfaces at 20-30 FPS with delta-time
 *     sleep pacing to balance high visual fluidity with minimal CPU utilization.
 *   - Remote Execution Engine: Receives serialized command packets (MV, MP, MR,
 *     MW, KP, KR) from Port 5002 and drives OS-level hardware events using
 *     java.awt.Robot (mouseMove, mousePress, mouseRelease, mouseWheel, keyPress, keyRelease).
 *   - Full-Duplex Chat Client: Provides a clean Swing UI for bi-directional chat
 *     with the remote server operator.
 * 
 * Compatibility: Standard JDK 8, 11, 17, and 21. No external libraries required.
 * ============================================================================
 */

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

public class ClientApp extends JFrame {

    // --- Networking Constants (Layer 4) ---
    public static final int CHAT_PORT = 5000;
    public static final int SCREEN_PORT = 5001;
    public static final int CONTROL_PORT = 5002;

    // --- Network Sockets & Streams ---
    private Socket chatSocket;
    private Socket screenSocket;
    private Socket controlSocket;

    private BufferedReader chatReader;
    private PrintWriter chatWriter;
    private DataOutputStream screenDos;
    private BufferedReader controlReader;

    // --- State & Concurrency Controls ---
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicInteger fpsCounter = new AtomicInteger(0);
    private volatile int currentFps = 0;

    // --- Thread Pools ---
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private ScheduledExecutorService metricsScheduler;

    // --- Hardware Robot (Layer 7 OS Simulation) ---
    private Robot robot;
    private Dimension screenDimension;

    // --- GUI Components ---
    private JTextField serverIpField;
    private JButton connectButton;
    private JButton disconnectButton;
    private JLabel statusBadge;
    private JLabel resolutionBadge;
    private JLabel fpsBadge;
    private JTextArea chatLogArea;
    private JTextField chatInputField;
    private JButton chatSendButton;

    public ClientApp() {
        super("NetDesk - Remote Desktop & Full-Duplex Collaboration Station [CLIENT]");
        initHardwareRobot();
        initUI();
        startMetricsEngine();
    }

    /**
     * Initializes the java.awt.Robot hardware event simulation engine.
     */
    private void initHardwareRobot() {
        try {
            robot = new Robot();
            robot.setAutoDelay(0); // Ultra-low latency execution
            robot.setAutoWaitForIdle(false); // Eliminate OS event pump blocking
            screenDimension = Toolkit.getDefaultToolkit().getScreenSize();
        } catch (AWTException e) {
            JOptionPane.showMessageDialog(null,
                    "Failed to initialize Java AWT Robot engine: " + e.getMessage(),
                    "Hardware Initialization Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /**
     * Constructs the dark-themed modern client Swing UI.
     */
    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(780, 620);
        setMinimumSize(new Dimension(640, 480));
        setLocationRelativeTo(null);

        // Dark Color Scheme
        Color bgDark = new Color(24, 24, 27);        // Zinc 900
        Color panelDark = new Color(39, 39, 42);     // Zinc 800
        Color borderDark = new Color(63, 63, 70);    // Zinc 700
        Color textLight = new Color(244, 244, 245);  // Zinc 100
        Color accentBlue = new Color(59, 130, 246);  // Blue 500
        Color accentGreen = new Color(16, 185, 129); // Emerald 500

        getContentPane().setBackground(bgDark);
        setLayout(new BorderLayout(0, 0));

        // -------------------------------------------------------------
        // TOP CONNECTION & LAUNCHER BAR
        // -------------------------------------------------------------
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(panelDark);
        topPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderDark),
                new EmptyBorder(12, 16, 12, 16)
        ));

        JPanel connectionControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        connectionControls.setOpaque(false);

        JLabel ipLabel = new JLabel("Server IPv4:");
        ipLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        ipLabel.setForeground(textLight);

        serverIpField = new JTextField("127.0.0.1", 12);
        serverIpField.setFont(new Font("SansSerif", Font.PLAIN, 12));
        serverIpField.setBackground(new Color(24, 24, 27));
        serverIpField.setForeground(textLight);
        serverIpField.setCaretColor(textLight);
        serverIpField.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(borderDark, 1, true),
                new EmptyBorder(5, 8, 5, 8)
        ));

        connectButton = createStyledButton("Connect", accentGreen, Color.WHITE);
        connectButton.addActionListener(e -> initiateConnection());

        disconnectButton = createStyledButton("Disconnect", new Color(63, 63, 70), new Color(161, 161, 170));
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> disconnectSession());

        connectionControls.add(ipLabel);
        connectionControls.add(serverIpField);
        connectionControls.add(connectButton);
        connectionControls.add(disconnectButton);

        // Status & Diagnostic Metadata
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        statusPanel.setOpaque(false);

        statusBadge = new JLabel("Status: Disconnected");
        statusBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
        statusBadge.setForeground(new Color(248, 113, 113)); // Red

        resolutionBadge = new JLabel(String.format("Native: %dx%d", screenDimension.width, screenDimension.height));
        resolutionBadge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        resolutionBadge.setForeground(textLight);

        fpsBadge = new JLabel("Stream: 0 FPS");
        fpsBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
        fpsBadge.setForeground(new Color(52, 211, 153));

        statusPanel.add(statusBadge);
        statusPanel.add(resolutionBadge);
        statusPanel.add(fpsBadge);

        topPanel.add(connectionControls, BorderLayout.WEST);
        topPanel.add(statusPanel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // -------------------------------------------------------------
        // CENTER: INTEGRATED FULL-DUPLEX CHAT INTERFACE
        // -------------------------------------------------------------
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(bgDark);
        centerPanel.setBorder(new EmptyBorder(12, 16, 12, 16));

        // Info Banner
        JPanel infoBanner = new JPanel(new BorderLayout());
        infoBanner.setBackground(new Color(30, 30, 35));
        infoBanner.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(borderDark, 1, true),
                new EmptyBorder(10, 14, 10, 14)
        ));

        JLabel infoTitle = new JLabel("Client Screen Sharing & Remote Control Engine");
        infoTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        infoTitle.setForeground(textLight);

        JLabel infoDetails = new JLabel("When connected, your desktop will stream to the Server on Port 5001, and remote commands will execute on Port 5002.");
        infoDetails.setFont(new Font("SansSerif", Font.PLAIN, 11));
        infoDetails.setForeground(new Color(161, 161, 170));

        infoBanner.add(infoTitle, BorderLayout.NORTH);
        infoBanner.add(infoDetails, BorderLayout.SOUTH);
        centerPanel.add(infoBanner, BorderLayout.NORTH);

        // Chat Container
        JPanel chatContainer = new JPanel(new BorderLayout(0, 8));
        chatContainer.setOpaque(false);
        chatContainer.setBorder(new EmptyBorder(12, 0, 0, 0));

        JPanel chatHeaderBar = new JPanel(new BorderLayout());
        chatHeaderBar.setOpaque(false);
        JLabel chatTitleLabel = new JLabel("Collaboration Chat");
        chatTitleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        chatTitleLabel.setForeground(textLight);

        JButton clearChatBtn = createStyledButton("Clear Log", new Color(63, 63, 70), Color.LIGHT_GRAY);
        clearChatBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        clearChatBtn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(82, 82, 91), 1, true),
                new EmptyBorder(3, 8, 3, 8)
        ));
        clearChatBtn.addActionListener(e -> chatLogArea.setText(""));

        chatHeaderBar.add(chatTitleLabel, BorderLayout.WEST);
        chatHeaderBar.add(clearChatBtn, BorderLayout.EAST);
        chatContainer.add(chatHeaderBar, BorderLayout.NORTH);

        chatLogArea = new JTextArea();
        chatLogArea.setEditable(false);
        chatLogArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        chatLogArea.setBackground(panelDark);
        chatLogArea.setForeground(new Color(228, 228, 231));
        chatLogArea.setLineWrap(true);
        chatLogArea.setWrapStyleWord(true);
        chatLogArea.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(chatLogArea);
        scrollPane.setBorder(new LineBorder(borderDark, 1, true));
        chatContainer.add(scrollPane, BorderLayout.CENTER);

        // Chat Input Bar
        JPanel inputBar = new JPanel(new BorderLayout(8, 0));
        inputBar.setOpaque(false);

        chatInputField = new JTextField();
        chatInputField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        chatInputField.setBackground(panelDark);
        chatInputField.setForeground(textLight);
        chatInputField.setCaretColor(textLight);
        chatInputField.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(borderDark, 1, true),
                new EmptyBorder(6, 8, 6, 8)
        ));
        chatInputField.addActionListener(e -> sendChatMessage());

        chatSendButton = createStyledButton("Send", accentBlue, Color.WHITE);
        chatSendButton.setBorder(new EmptyBorder(6, 18, 6, 18));
        chatSendButton.addActionListener(e -> sendChatMessage());

        inputBar.add(chatInputField, BorderLayout.CENTER);
        inputBar.add(chatSendButton, BorderLayout.EAST);
        chatContainer.add(inputBar, BorderLayout.SOUTH);

        centerPanel.add(chatContainer, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // Window Closing Handler
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnectSession();
                if (metricsScheduler != null) metricsScheduler.shutdownNow();
                clientExecutor.shutdownNow();
            }
        });
    }

    /**
     * Initiates connection across Ports 5000, 5001, and 5002 in a background thread.
     */
    private void initiateConnection() {
        if (isConnecting.get() || isConnected.get()) return;

        final String host = serverIpField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a valid Server IPv4 address.",
                    "Input Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        isConnecting.set(true);
        connectButton.setEnabled(false);
        serverIpField.setEnabled(false);
        statusBadge.setText("Status: Connecting...");
        statusBadge.setForeground(new Color(251, 191, 36)); // Amber
        appendChat("System", "Connecting to Server at " + host + " on Ports 5000, 5001, 5002...");

        clientExecutor.submit(() -> {
            try {
                // Layer 4: Establish TCP connections to all three designated ports
                Socket cSock = new Socket();
                cSock.connect(new InetSocketAddress(host, CHAT_PORT), 4000);

                Socket sSock = new Socket();
                sSock.connect(new InetSocketAddress(host, SCREEN_PORT), 4000);

                Socket ctrlSock = new Socket();
                ctrlSock.connect(new InetSocketAddress(host, CONTROL_PORT), 4000);

                // Enable TCP_NODELAY across all sockets to eliminate buffering lag
                cSock.setTcpNoDelay(true);
                sSock.setTcpNoDelay(true);
                ctrlSock.setTcpNoDelay(true);

                // Set optimized send/receive buffer sizes for zero frame-drop streaming
                cSock.setSendBufferSize(64 * 1024);
                cSock.setReceiveBufferSize(64 * 1024);
                sSock.setSendBufferSize(512 * 1024);
                sSock.setReceiveBufferSize(512 * 1024);
                ctrlSock.setSendBufferSize(64 * 1024);
                ctrlSock.setReceiveBufferSize(64 * 1024);

                chatSocket = cSock;
                screenSocket = sSock;
                controlSocket = ctrlSock;

                chatReader = new BufferedReader(new InputStreamReader(chatSocket.getInputStream(), StandardCharsets.UTF_8));
                chatWriter = new PrintWriter(new OutputStreamWriter(chatSocket.getOutputStream(), StandardCharsets.UTF_8), true);

                screenDos = new DataOutputStream(new BufferedOutputStream(screenSocket.getOutputStream(), 256 * 1024));

                controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));

                isConnected.set(true);
                isConnecting.set(false);

                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(false);
                    connectButton.setBackground(new Color(63, 63, 70));
                    connectButton.setForeground(new Color(161, 161, 170));

                    disconnectButton.setEnabled(true);
                    disconnectButton.setBackground(new Color(220, 38, 38));
                    disconnectButton.setForeground(Color.WHITE);

                    statusBadge.setText("Status: CONNECTED");
                    statusBadge.setForeground(new Color(52, 211, 153)); // Green
                });

                appendChat("System", "Successfully connected to Server! Desktop streaming and remote control active.");

                // Latch = 3: All three threads (Chat + Screen + Control) must finish
                // before session cleanup runs. This ensures true full-duplex operation
                // where messaging, screen sharing, and remote control all work
                // simultaneously without one thread ending killing the others.
                CountDownLatch sessionLatch = new CountDownLatch(3);

                // Dedicated Thread 1: Chat Receiver (Port 5000)
                clientExecutor.submit(() -> runChatReceiver(sessionLatch));

                // Dedicated Thread 2: Screen Streaming Engine (Port 5001)
                clientExecutor.submit(() -> runScreenStreamer(sessionLatch));

                // Dedicated Thread 3: Remote Execution Engine (Port 5002)
                clientExecutor.submit(() -> runRemoteExecutionEngine(sessionLatch));

                // Await session completion
                try {
                    sessionLatch.await();
                } catch (InterruptedException ignored) {}

                // Handle session cleanup
                disconnectSession();

            } catch (Exception ex) {
                isConnecting.set(false);
                appendChat("System", "Connection failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    disconnectButton.setEnabled(false);
                    serverIpField.setEnabled(true);
                    statusBadge.setText("Status: Connection Failed");
                    statusBadge.setForeground(new Color(248, 113, 113));
                });
            }
        });
    }

    /**
     * Dedicated Thread 1: Full-Duplex Chat Receiver (Port 5000).
     */
    private void runChatReceiver(CountDownLatch sessionLatch) {
        try {
            String line;
            while (isConnected.get() && (line = chatReader.readLine()) != null) {
                appendChat("Server", line);
            }
        } catch (IOException e) {
            // Disconnection
        } finally {
            sessionLatch.countDown();
        }
    }

    /**
     * Dedicated Thread 2: High-Performance Screen Capturing & Streaming Engine (Port 5001).
     * Compresses frames via ImageIO into JPEG byte streams and transmits length-prefixed packets.
     */
    private void runScreenStreamer(CountDownLatch sessionLatch) {
        try {
            Rectangle captureRect = new Rectangle(screenDimension);

            // Layer 5 Handshake: Send native screen resolution to server
            screenDos.writeInt(captureRect.width);
            screenDos.writeInt(captureRect.height);
            screenDos.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(256 * 1024);
            long targetFrameIntervalMs = 33; // Butter-smooth ~30 FPS target (33ms per frame)

            // High-Performance Cached JPEG ImageWriter
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                throw new IOException("No JPEG ImageWriter available in Java runtime");
            }
            ImageWriter writer = writers.next();
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(0.70f); // 70% quality: crisp text, 50% smaller payload, ultra-fast compression

            while (isConnected.get()) {
                long frameStart = System.currentTimeMillis();

                // 1. Capture screen surface using Robot
                BufferedImage screenshot = robot.createScreenCapture(captureRect);

                // 2. High-speed in-memory compression without SPI lookup overhead
                baos.reset();
                ImageOutputStream ios = new MemoryCacheImageOutputStream(baos);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(screenshot, null, null), writeParam);
                ios.flush();
                ios.close();

                byte[] frameBytes = baos.toByteArray();

                // 3. Layer 6: Stream 4-byte payload size followed by raw image bytes
                screenDos.writeInt(frameBytes.length);
                screenDos.write(frameBytes);
                screenDos.flush(); // Critical: Immediate buffer flush to eliminate streaming latency

                fpsCounter.incrementAndGet();

                // 4. Smooth Pacing: maintain solid 30 FPS without burning CPU
                long elapsed = System.currentTimeMillis() - frameStart;
                long sleepTime = targetFrameIntervalMs - elapsed;
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            }
            writer.dispose();
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            // Disconnection
        } finally {
            sessionLatch.countDown();
        }
    }

    /**
     * Dedicated Thread 3: Remote Desktop Execution Engine (Port 5002).
     * Reads command packets and invokes native OS inputs using java.awt.Robot.
     */
    private void runRemoteExecutionEngine(CountDownLatch sessionLatch) {
        try {
            String packet;
            while (isConnected.get() && (packet = controlReader.readLine()) != null) {
                executeRemoteCommand(packet);
            }
        } catch (IOException e) {
            // Disconnection
        } finally {
            sessionLatch.countDown();
        }
    }

    /**
     * Parses and executes OS hardware input commands.
     * Guarded with exception handling to prevent unmapped keycodes from crashing the engine.
     */
    private void executeRemoteCommand(String packet) {
        if (packet == null || packet.isEmpty()) return;

        try {
            String[] tokens = packet.split(":");
            String command = tokens[0];

            switch (command) {
                case "MV": // Mouse Move -> MV:x:y
                    if (tokens.length >= 3) {
                        int x = Integer.parseInt(tokens[1]);
                        int y = Integer.parseInt(tokens[2]);
                        // Clamp to prevent out-of-screen coordinate bugs
                        x = Math.max(0, Math.min(screenDimension.width - 1, x));
                        y = Math.max(0, Math.min(screenDimension.height - 1, y));
                        robot.mouseMove(x, y);
                    }
                    break;

                case "MP": // Mouse Press -> MP:buttonMask
                    if (tokens.length >= 2) {
                        int mask = Integer.parseInt(tokens[1]);
                        robot.mousePress(mask);
                    }
                    break;

                case "MR": // Mouse Release -> MR:buttonMask
                    if (tokens.length >= 2) {
                        int mask = Integer.parseInt(tokens[1]);
                        robot.mouseRelease(mask);
                    }
                    break;

                case "MW": // Mouse Wheel -> MW:wheelRotation
                    if (tokens.length >= 2) {
                        int wheelRotation = Integer.parseInt(tokens[1]);
                        robot.mouseWheel(wheelRotation);
                    }
                    break;

                case "KP": // Key Press -> KP:keyCode
                    if (tokens.length >= 2) {
                        int keyCode = Integer.parseInt(tokens[1]);
                        try {
                            robot.keyPress(keyCode);
                        } catch (IllegalArgumentException ignored) {
                            // Ignore platform-unmappable keys
                        }
                    }
                    break;

                case "KR": // Key Release -> KR:keyCode
                    if (tokens.length >= 2) {
                        int keyCode = Integer.parseInt(tokens[1]);
                        try {
                            robot.keyRelease(keyCode);
                        } catch (IllegalArgumentException ignored) {
                            // Ignore platform-unmappable keys
                        }
                    }
                    break;

                default:
                    // Unknown command packet - safely ignore
                    break;
            }
        } catch (Exception ex) {
            // Guard against malformed packets to maintain engine stability
        }
    }

    /**
     * Transmits a chat message to the server over Port 5000.
     */
    private synchronized void sendChatMessage() {
        String message = chatInputField.getText().trim();
        if (message.isEmpty()) return;

        if (!isConnected.get() || chatWriter == null) {
            appendChat("System", "Cannot send message: Not connected to Server.");
            return;
        }

        try {
            chatWriter.println(message);
            chatWriter.flush();
            appendChat("Me (Client)", message);
            chatInputField.setText("");
        } catch (Exception ex) {
            appendChat("System", "Failed to send message: " + ex.getMessage());
        }
    }

    /**
     * Appends a message to the chat display in a thread-safe manner.
     */
    private void appendChat(String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            chatLogArea.append(String.format("[%s] %s: %s%n", timestamp, sender, message));
            chatLogArea.setCaretPosition(chatLogArea.getDocument().getLength());
        });
    }

    /**
     * Starts the periodic FPS diagnostic counter.
     */
    private void startMetricsEngine() {
        metricsScheduler = Executors.newSingleThreadScheduledExecutor();
        metricsScheduler.scheduleAtFixedRate(() -> {
            currentFps = fpsCounter.getAndSet(0);
            SwingUtilities.invokeLater(() -> {
                if (isConnected.get()) {
                    fpsBadge.setText("Stream: " + currentFps + " FPS");
                } else {
                    fpsBadge.setText("Stream: 0 FPS");
                }
            });
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Gracefully disconnects all active sockets and resets state.
     */
    private synchronized void disconnectSession() {
        if (!isConnected.get() && !isConnecting.get()) return;
        isConnected.set(false);
        isConnecting.set(false);

        try { if (chatSocket != null && !chatSocket.isClosed()) chatSocket.close(); } catch (Exception ignored) {}
        try { if (screenSocket != null && !screenSocket.isClosed()) screenSocket.close(); } catch (Exception ignored) {}
        try { if (controlSocket != null && !controlSocket.isClosed()) controlSocket.close(); } catch (Exception ignored) {}

        chatSocket = null;
        screenSocket = null;
        controlSocket = null;
        chatReader = null;
        chatWriter = null;
        screenDos = null;
        controlReader = null;

        SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(true);
            connectButton.setBackground(new Color(16, 185, 129));
            connectButton.setForeground(Color.WHITE);

            disconnectButton.setEnabled(false);
            disconnectButton.setBackground(new Color(63, 63, 70));
            disconnectButton.setForeground(new Color(161, 161, 170));

            serverIpField.setEnabled(true);
            statusBadge.setText("Status: Disconnected");
            statusBadge.setForeground(new Color(248, 113, 113));
            fpsBadge.setText("Stream: 0 FPS");
        });

        appendChat("System", "Session disconnected.");
    }

    /**
     * Creates a modern, high-contrast styled button that renders reliably across all OS platforms.
     */
    public static JButton createStyledButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(bg.brighter(), 1, true),
                new EmptyBorder(6, 16, 6, 16)
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

    // =========================================================================
    // MAIN LAUNCHER
    // =========================================================================
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            ClientApp client = new ClientApp();
            client.setVisible(true);
        });
    }
}

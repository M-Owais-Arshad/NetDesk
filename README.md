# 🖥️ NetDesk: Multi-Channel Remote Desktop & Full-Duplex Collaboration System

[![Java](https://img.shields.io/badge/Java-8%20%7C%2011%20%7C%2017%20%7C%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Architecture](https://img.shields.io/badge/Architecture-Multi--Channel%20TCP-blue?style=for-the-badge)](https://en.wikipedia.org/wiki/Transmission_Control_Protocol)
[![OSI Model](https://img.shields.io/badge/OSI%20Model-Layers%204%E2%80%947-success?style=for-the-badge)](https://en.wikipedia.org/wiki/OSI_model)
[![GUI](https://img.shields.io/badge/GUI-Java%20Swing%20Dark%20HUD-1f2937?style=for-the-badge)](https://docs.oracle.com/javase/tutorial/uiswing/)

**NetDesk** is a high-performance, low-latency Remote Desktop and Collaboration platform developed in core Java without any external dependencies. It combines real-time screen streaming, hardware-level remote control input execution (RDP), and full-duplex asynchronous chat over dedicated, isolated TCP network channels.

---

## 🚀 Key Features

- **🌐 Multi-Channel Transport Architecture:**
  - Dedicated TCP channels to eliminate head-of-line blocking and optimize throughput.
  - Channel 1 (Port `5000`): Full-Duplex Asynchronous Chat.
  - Channel 2 (Port `5001`): Continuous Desktop Frame Video Stream.
  - Channel 3 (Port `5002`): Low-Latency Hardware Remote Control (Mouse & Keyboard).

- **📺 Real-Time Desktop Streaming:**
  - High-performance display capture powered by `java.awt.Robot`.
  - In-memory JPEG compression stream with 4-byte length-prefixed binary framing to prevent TCP underflow/fragmentation.
  - Real-time stream telemetry with live **FPS (Frames Per Second)** diagnostics.

- **🎮 Full Remote Desktop Control (RDP):**
  - Bidirectional event serialization for mouse movements, clicks, drags, mouse wheel scrolling, and keyboard events.
  - **Proportional Coordinate Translation Engine** to map Server viewport coordinates accurately to the Client's native desktop resolution regardless of window resizing.

- **💬 Full-Duplex Asynchronous Chat:**
  - Dedicated bi-directional text messaging thread allowing both Server and Client operators to chat simultaneously while screen streaming and control are active.

- **🎨 Modern Dark HUD & Operator Dashboard:**
  - Zinc-inspired dark mode theme with clean status badges and connection indicators.
  - **Auto IP Detection:** Detects and displays the host machine's LAN IPv4.
  - **One-Click "Copy IP":** Instant clipboard copy button for rapid client connection.

---

## 📡 Networking Architecture & OSI Model Mapping

NetDesk is specifically engineered to demonstrate the implementation of Layers 4 through 7 of the **OSI (Open Systems Interconnection) Model**:

```
+-------------------------------------------------------------------------+
|                  Layer 7: APPLICATION LAYER                             |
|  - Swing GUI Dashboard, ScreenCanvas Viewport, Chat Transcript Logs      |
|  - java.awt.Robot OS Simulation (Mouse/Keyboard Input Dispatcher)       |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
|                  Layer 6: PRESENTATION LAYER                            |
|  - JPEG In-Memory Compression/Decompression (ImageIO)                   |
|  - 4-Byte Big-Endian Integer Length-Prefixed Binary Framing             |
|  - UTF-8 Character Encoding (BufferedReader / PrintWriter)              |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
|                  Layer 5: SESSION LAYER                                 |
|  - Concurrent Multi-Socket Session Lifecycle Synchronization            |
|  - Resolution Handshake (Transmitting Client Native Dimensions)         |
|  - Resilient Reconnect & Graceful Disconnect Resource Teardown          |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
|                  Layer 4: TRANSPORT LAYER (TCP)                         |
|  - Port 5000: Asynchronous Full-Duplex Chat Channel                     |
|  - Port 5001: High-Throughput Desktop Video Stream Channel              |
|  - Port 5002: Ultra-Low-Latency Remote Input Control Channel            |
|  - TCP_NODELAY (Nagle's Algorithm Disabled for Instant Dispatch)        |
+-------------------------------------------------------------------------+
```

---

## 🔌 Dedicated Port Allocation Table

| Port Number | Protocol | Channel Purpose | Data Characteristics |
|:---:|:---:|:---|:---|
| **5000** | TCP | **Full-Duplex Chat** | Low-bandwidth, UTF-8 text strings with newline delimiters |
| **5001** | TCP | **Desktop Video Stream** | High-throughput binary frames with 4-byte payload header |
| **5002** | TCP | **Remote Control (RDP)** | Latency-critical, serialized command tokens (`MV`, `MP`, `MR`, `MW`, `KP`, `KR`) |

---

## 📐 Proportional Coordinate Translation Mathematics

To support arbitrary window sizes on the server without losing mouse tracking precision on the remote machine, NetDesk computes proportional mouse coordinates before sending packets:

$$\text{Client}_X = \frac{\text{ServerEvent}_X \times \text{ClientNativeWidth}}{\text{ServerPanelWidth}}$$

$$\text{Client}_Y = \frac{\text{ServerEvent}_Y \times \text{ClientNativeHeight}}{\text{ServerPanelHeight}}$$

Coordinates are bounded and clamped to prevent boundary exceptions on the client machine.

---

## 🛠️ Project Structure

```bash
NetDesk/
├── ServerApp.java       # Server Station (Listener, Video Canvas, Chat, Input Sender)
├── ClientApp.java       # Client Station (Screen Streamer, RDP Executor, Chat)
├── .gitignore           # Git ignore configuration
└── README.md            # Comprehensive project documentation
```

---

## ⚡ How to Run

### Prerequisites
- Java Development Kit (JDK 8, 11, 17, or 21)
- Standard terminal / command prompt

### Step 1: Clone the Repository
```bash
git clone https://github.com/M-Owais-Arshad/NetDesk.git
cd NetDesk
```

### Step 2: Compile the Java Source Files
```bash
javac ServerApp.java ClientApp.java
```

### Step 3: Run the Server
On the machine acting as the host/controller:
```bash
java ServerApp
```
*Note: The Server window will display the local IPv4 address and an instant **[Copy IP]** button.*

### Step 4: Run the Client
On the machine sharing its screen:
```bash
java ClientApp
```
1. Paste or enter the **Server IPv4** address shown on the Server station.
2. Click **Connect**.
3. All three channels (Chat, Screen Sharing, Remote Control) will activate simultaneously in true full-duplex!

---

## 👤 Author
- **Muhammad Owais Arshad** - [GitHub Profile](https://github.com/M-Owais-Arshad)

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).

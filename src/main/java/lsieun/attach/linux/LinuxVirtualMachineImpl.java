package lsieun.attach.linux;

import lsieun.attach.AttachNotSupportedException;
import lsieun.attach.AttachProvider;
import lsieun.attach.hotspot.HotSpotVirtualMachine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/*
 * Linux implementation of HotSpotVirtualMachine
 */
public class LinuxVirtualMachineImpl extends HotSpotVirtualMachine {
    // "/tmp" is used as a global well-known location for the files .java_pid<pid>. and .attach_pid<pid>.
    // It is important that this location is the same for all processes,
    // otherwise the tools will not be able to find all Hotspot processes.
    // Any changes to this needs to be synchronized with HotSpot.
    private static final String tmpdir = "/tmp";
    String socket_path;

    /**
     * Attaches to the target VM
     */
    LinuxVirtualMachineImpl(AttachProvider provider, String vmid) throws AttachNotSupportedException, IOException {
        super(provider, vmid);

        // This provider only understands pids
        int pid;
        try {
            pid = Integer.parseInt(vmid);
            if (pid < 1) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException x) {
            throw new AttachNotSupportedException("Invalid process identifier: " + vmid);
        }

        // Try to resolve to the "inner most" pid namespace
        int ns_pid = getNamespacePid(pid);

        // Find the socket file. If not found then we attempt to start the
        // attach mechanism in the target VM by sending it a QUIT signal.
        // Then we attempt to find the socket file again.
        File socket_file = findSocketFile(pid, ns_pid);
        socket_path = socket_file.getPath();
        if (!socket_file.exists()) {
            // Keep canonical version of File, to delete, in case target process ends and /proc link has gone:
            File f = createAttachFile(pid, ns_pid).getCanonicalFile();
            try {
                sendQuitTo(pid);

                // give the target VM time to start the attach mechanism
                final int delay_step = 100;
                final long timeout = attachTimeout();
                long time_spend = 0;
                long delay = 0;
                do {
                    // Increase timeout on each attempt to reduce polling
                    delay += delay_step;
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException x) {
                    }

                    time_spend += delay;
                    if (time_spend > timeout / 2 && !socket_file.exists()) {
                        // Send QUIT again to give target VM the last chance to react
                        sendQuitTo(pid);
                    }
                } while (time_spend <= timeout && !socket_file.exists());
                if (!socket_file.exists()) {
                    throw new AttachNotSupportedException(
                            String.format("Unable to open socket file %s: " +
                                            "target process %d doesn't respond within %dms " +
                                            "or HotSpot VM not loaded", socket_path, pid,
                                    time_spend));
                }
            } finally {
                f.delete();
            }
        }

        // Check that the file owner/permission to avoid attaching to
        // bogus process
        checkPermissions(socket_path);

        // Check that we can connect to the process
        // - this ensures we throw the permission denied error now rather than
        // later when we attempt to enqueue a command.
        int s = socket();
        try {
            connect(s, socket_path);
        } finally {
            close(s);
        }
    }

    // Return the inner most namespace PID if there is one,
    // otherwise return the original PID.
    private int getNamespacePid(int pid) throws AttachNotSupportedException, IOException {
        // Assuming a real procfs sits beneath, reading this doesn't block
        // nor will it consume a lot of memory.
        String statusFile = "/proc/" + pid + "/status";
        File f = new File(statusFile);
        if (!f.exists()) {
            return pid; // Likely a bad pid, but this is properly handled later.
        }

        Path statusPath = Paths.get(statusFile);

        try {
            for (String line : Files.readAllLines(statusPath)) {
                String[] parts = line.split(":");
                if (parts.length == 2 && parts[0].trim().equals("NSpid")) {
                    parts = parts[1].trim().split("\\s+");
                    // The last entry represents the PID the JVM "thinks" it is.
                    // Even in non-namespaced pids these entries should be
                    // valid. You could refer to it as the inner most pid.
                    int ns_pid = Integer.parseInt(parts[parts.length - 1]);
                    return ns_pid;
                }
            }
            // Old kernels may not have NSpid field (i.e. 3.10).
            // Fallback to original pid in the event we cannot deduce.
            return pid;
        } catch (NumberFormatException | IOException x) {
            throw new AttachNotSupportedException("Unable to parse namespace");
        }
    }

    // Return the socket file for the given process.
    private File findSocketFile(int pid, int ns_pid) {
        // A process may not exist in the same mount namespace as the caller.
        // Instead, attach relative to the target root filesystem as exposed by
        // procfs regardless of namespaces.
        String root = "/proc/" + pid + "/root/" + tmpdir;
        return new File(root, ".java_pid" + ns_pid);
    }

    // On Linux a simple handshake is used to start the attach mechanism if not already started.
    // The client creates a .attach_pid<pid> file in the target VM's working directory (or temp directory),
    // and the SIGQUIT handler checks for the file.
    private File createAttachFile(int pid, int ns_pid) throws IOException {
        String fn = ".attach_pid" + ns_pid;
        String path = "/proc/" + pid + "/cwd/" + fn;
        File f = new File(path);
        try {
            // Do not canonicalize the file path, or we will fail to attach to a VM in a container.
            f.createNewFile();
        } catch (IOException x) {
            String root;
            if (pid != ns_pid) {
                // A process may not exist in the same mount namespace as the caller.
                // Instead, attach relative to the target root filesystem as exposed by
                // procfs regardless of namespaces.
                root = "/proc/" + pid + "/root/" + tmpdir;
            } else {
                root = tmpdir;
            }
            f = new File(root, fn);
            f.createNewFile();
        }
        return f;
    }

    //-- native methods

    static native void sendQuitTo(int pid) throws IOException;

    static native void checkPermissions(String path) throws IOException;

    static native int socket() throws IOException;

    static native void connect(int fd, String path) throws IOException;

    static native void close(int fd) throws IOException;

    static native int read(int fd, byte buf[], int off, int bufLen) throws IOException;

    static native void write(int fd, byte buf[], int off, int bufLen) throws IOException;

    static {
        System.loadLibrary("attach");
    }
}

package lsieun.attach.hotspot;

import lsieun.attach.AttachNotSupportedException;
import lsieun.attach.AttachProvider;
import lsieun.attach.VirtualMachine;
import sun.misc.VM;

import java.io.IOException;
import java.lang.management.ManagementFactory;

public abstract class HotSpotVirtualMachine extends VirtualMachine {
    private static final long CURRENT_PID = pid();
    private static final boolean ALLOW_ATTACH_SELF;

    static {
        String s = VM.getSavedProperty("jdk.attach.allowAttachSelf");
        ALLOW_ATTACH_SELF = "".equals(s) || Boolean.parseBoolean(s);
    }

    private static long pid() {
        String jvmName = ManagementFactory.getRuntimeMXBean().getName();
        String jvmPid = jvmName.substring(0, jvmName.indexOf('@'));
        return Long.parseLong(jvmPid);
    }

    protected HotSpotVirtualMachine(AttachProvider provider, String id) throws AttachNotSupportedException, IOException {
        super(provider, id);

        int pid;
        try {
            pid = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new AttachNotSupportedException("Invalid process identifier");
        }

        // The tool should be a different VM to the target. This check will
        // eventually be enforced by the target VM.
        if (!ALLOW_ATTACH_SELF && (pid == 0 || pid == CURRENT_PID)) {
            throw new IOException("Can not attach to current VM");
        }
    }

    // -- attach timeout support

    private static final long defaultAttachTimeout = 10000;
    private volatile long attachTimeout;

    /*
     * Return attach timeout based on the value of the sun.tools.attach.attachTimeout property,
     * or the default timeout if the property is not set to a positive value.
     */
    protected long attachTimeout() {
        if (attachTimeout == 0) {
            try {
                String s = System.getProperty("sun.tools.attach.attachTimeout");
                attachTimeout = Long.parseLong(s);
            } catch (SecurityException | NumberFormatException ignored) {
            }
            if (attachTimeout <= 0) {
                attachTimeout = defaultAttachTimeout;
            }
        }
        return attachTimeout;
    }
}

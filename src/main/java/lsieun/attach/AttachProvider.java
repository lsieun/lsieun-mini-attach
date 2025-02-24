package lsieun.attach;

import java.io.IOException;
import java.util.*;

public abstract class AttachProvider {
    private static List<AttachProvider> providers = null;

    public abstract String name();
    public abstract String type();

    public abstract VirtualMachine attachVirtualMachine(String id)
            throws AttachNotSupportedException, IOException;

    public static List<AttachProvider> providers() {
        if (providers == null) {
            providers = new ArrayList<AttachProvider>();

            ServiceLoader<AttachProvider> providerLoader =
                    ServiceLoader.load(AttachProvider.class,
                            AttachProvider.class.getClassLoader());

            Iterator<AttachProvider> it = providerLoader.iterator();

            while (it.hasNext()) {
                try {
                    providers.add(it.next());
                } catch (Throwable t) {
                    if (t instanceof ThreadDeath) {
                        ThreadDeath td = (ThreadDeath)t;
                        throw td;
                    }
                    // Log errors and exceptions since we cannot return them
                    t.printStackTrace();
                }
            }
        }
        return Collections.unmodifiableList(providers);
    }
}

package lsieun.attach;

public abstract class VirtualMachine {
    private AttachProvider provider;
    private String id;
    private volatile int hash;        // 0 => not computed

    protected VirtualMachine(AttachProvider provider, String id) {
        if (provider == null) {
            throw new NullPointerException("provider cannot be null");
        }
        if (id == null) {
            throw new NullPointerException("id cannot be null");
        }
        this.provider = provider;
        this.id = id;
    }
}

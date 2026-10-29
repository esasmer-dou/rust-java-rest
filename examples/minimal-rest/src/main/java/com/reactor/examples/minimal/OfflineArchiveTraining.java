package com.reactor.examples.minimal;

/** Finite metadata-only training: no server, native initialization or external I/O. */
public final class OfflineArchiveTraining {
    private OfflineArchiveTraining() { }

    public static void main(String[] args) throws ClassNotFoundException {
        if (args.length != 1 || !args[0].equals("--offline")) {
            throw new IllegalArgumentException("Only --offline is supported");
        }
        ClassLoader loader = OfflineArchiveTraining.class.getClassLoader();
        for (String name : new String[]{
                "com.reactor.examples.minimal.MinimalApplication",
                "com.reactor.examples.minimal.HelloHandler",
                "com.reactor.rust.app.RestApplication"
        }) {
            Class.forName(name, false, loader);
        }
    }
}

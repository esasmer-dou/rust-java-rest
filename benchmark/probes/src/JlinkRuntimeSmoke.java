import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;

public final class JlinkRuntimeSmoke {
    private JlinkRuntimeSmoke() {
    }

    public static void main(String[] args) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(256);
        int written = DslJsonService.writeToBuffer("jlink-smoke", buffer, 0);
        if (written <= 0) {
            throw new IllegalStateException("DSL-JSON jlink smoke produced no output");
        }
    }
}

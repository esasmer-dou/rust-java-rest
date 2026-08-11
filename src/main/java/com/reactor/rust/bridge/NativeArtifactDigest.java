package com.reactor.rust.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Provider-independent SHA-256 used only for packaged native artifact verification.
 */
final class NativeArtifactDigest {

    private static final int BLOCK_BYTES = 64;
    private static final int[] INITIAL_STATE = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    private static final int[] ROUND_CONSTANTS = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
            0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
            0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private final int[] state = INITIAL_STATE.clone();
    private final int[] schedule = new int[64];
    private final byte[] block = new byte[BLOCK_BYTES];
    private int blockLength;
    private long byteCount;
    private boolean finished;

    private NativeArtifactDigest() {}

    static String sha256Hex(byte[] input) {
        NativeArtifactDigest digest = new NativeArtifactDigest();
        digest.update(input, 0, input.length);
        return digest.finishHex();
    }

    static String sha256Hex(Path path) throws IOException {
        NativeArtifactDigest digest = new NativeArtifactDigest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return digest.finishHex();
    }

    private void update(byte[] input, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("Native artifact digest is already finalized");
        }
        if (offset < 0 || length < 0 || offset > input.length - length) {
            throw new IndexOutOfBoundsException("Invalid digest input range");
        }
        byteCount = Math.addExact(byteCount, length);

        int cursor = offset;
        int remaining = length;
        if (blockLength > 0) {
            int copied = Math.min(remaining, BLOCK_BYTES - blockLength);
            System.arraycopy(input, cursor, block, blockLength, copied);
            blockLength += copied;
            cursor += copied;
            remaining -= copied;
            if (blockLength == BLOCK_BYTES) {
                compress(block, 0);
                blockLength = 0;
            }
        }

        while (remaining >= BLOCK_BYTES) {
            compress(input, cursor);
            cursor += BLOCK_BYTES;
            remaining -= BLOCK_BYTES;
        }
        if (remaining > 0) {
            System.arraycopy(input, cursor, block, 0, remaining);
            blockLength = remaining;
        }
    }

    private String finishHex() {
        if (finished) {
            throw new IllegalStateException("Native artifact digest is already finalized");
        }
        finished = true;
        long bitCount = byteCount << 3;

        block[blockLength++] = (byte) 0x80;
        if (blockLength > 56) {
            clearBlock(blockLength, BLOCK_BYTES);
            compress(block, 0);
            blockLength = 0;
        }
        clearBlock(blockLength, 56);
        for (int index = 0; index < Long.BYTES; index++) {
            block[63 - index] = (byte) (bitCount >>> (index * 8));
        }
        compress(block, 0);

        byte[] result = new byte[32];
        for (int index = 0; index < state.length; index++) {
            writeInt(state[index], result, index * Integer.BYTES);
        }
        return HexFormat.of().formatHex(result);
    }

    private void clearBlock(int from, int to) {
        for (int index = from; index < to; index++) {
            block[index] = 0;
        }
    }

    private void compress(byte[] input, int offset) {
        for (int index = 0; index < 16; index++) {
            int wordOffset = offset + index * Integer.BYTES;
            schedule[index] = ((input[wordOffset] & 0xff) << 24)
                    | ((input[wordOffset + 1] & 0xff) << 16)
                    | ((input[wordOffset + 2] & 0xff) << 8)
                    | (input[wordOffset + 3] & 0xff);
        }
        for (int index = 16; index < schedule.length; index++) {
            int previous15 = schedule[index - 15];
            int sigma0 = Integer.rotateRight(previous15, 7)
                    ^ Integer.rotateRight(previous15, 18)
                    ^ (previous15 >>> 3);
            int previous2 = schedule[index - 2];
            int sigma1 = Integer.rotateRight(previous2, 17)
                    ^ Integer.rotateRight(previous2, 19)
                    ^ (previous2 >>> 10);
            schedule[index] = schedule[index - 16] + sigma0
                    + schedule[index - 7] + sigma1;
        }

        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        int f = state[5];
        int g = state[6];
        int h = state[7];

        for (int index = 0; index < schedule.length; index++) {
            int sum1 = Integer.rotateRight(e, 6)
                    ^ Integer.rotateRight(e, 11)
                    ^ Integer.rotateRight(e, 25);
            int choose = (e & f) ^ (~e & g);
            int temp1 = h + sum1 + choose + ROUND_CONSTANTS[index] + schedule[index];
            int sum0 = Integer.rotateRight(a, 2)
                    ^ Integer.rotateRight(a, 13)
                    ^ Integer.rotateRight(a, 22);
            int majority = (a & b) ^ (a & c) ^ (b & c);
            int temp2 = sum0 + majority;

            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }

        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
        state[5] += f;
        state[6] += g;
        state[7] += h;
    }

    private static void writeInt(int value, byte[] output, int offset) {
        output[offset] = (byte) (value >>> 24);
        output[offset + 1] = (byte) (value >>> 16);
        output[offset + 2] = (byte) (value >>> 8);
        output[offset + 3] = (byte) value;
    }
}

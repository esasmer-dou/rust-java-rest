package com.reactor.rust.view;

import com.reactor.rust.codegen.NativeViewProcessor;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiles real generated code without changing the module's processor configuration. */
public final class NativeViewTestSupport {
    private NativeViewTestSupport() {}

    public interface Fixture {
        long exercise(int iterations);
        void check();
    }

    public static Compilation compile(Path directory, String file, String source) throws Exception {
        Path input = directory.resolve(file);
        Files.createDirectories(input.getParent());
        Files.writeString(input, source, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path generated = Files.createDirectories(directory.resolve("generated"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(), "-s", generated.toString()),
                    null, files.getJavaFileObjects(input));
            task.setProcessors(List.of(new NativeViewProcessor()));
            boolean success = task.call();
            return new Compilation(success, diagnostics.getDiagnostics().toString(), classes, generated);
        }
    }

    public static Loaded loadFixture(Path directory) throws Exception {
        Compilation compilation = compile(directory, "fixture/Checks.java", FIXTURE);
        assertTrue(compilation.success(), compilation.diagnostics());
        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{compilation.classes().toUri().toURL()},
                NativeViewTestSupport.class.getClassLoader());
        // Reflection is test setup only, never inside production code or the measured loop.
        Fixture fixture = (Fixture) loader.loadClass("fixture.Checks").getConstructor().newInstance();
        return new Loaded(loader, fixture, compilation);
    }

    public record Compilation(boolean success, String diagnostics, Path classes, Path generated) {}
    public record Loaded(URLClassLoader loader, Fixture fixture, Compilation compilation) implements AutoCloseable {
        @Override public void close() throws Exception { loader.close(); }
    }

    private static final String FIXTURE = """
            package fixture;
            import com.reactor.rust.view.*;
            import java.nio.*;
            import java.nio.charset.StandardCharsets;
            import static org.junit.jupiter.api.Assertions.*;

            enum Status { OPEN, CLOSED }
            @NativeView(schemaVersion=3) interface SampleAccountView {
                @ViewField(index=0) long id();
                @ViewField(index=1) Utf8Slice code();
                @ViewField(index=2) Status status();
                @ViewField(index=3) long balanceCents();
                @ViewField(index=4) boolean active();
            }
            interface BaseFields { @ViewField(index=0) byte tiny(); }
            @NativeView interface Scalars extends BaseFields {
                @ViewField(index=1, offset=4) int count();
                @ViewField(index=2) short small();
                @ViewField(index=3) char letter();
                @ViewField(index=4) float ratio();
                @ViewField(index=5) double amount();
                default int computed() { return count()+1; }
            }
            @NativeView interface Pair {
                @ViewField(index=0) SampleAccountView first();
                @ViewField(index=1) SampleAccountView second();
                @ViewField(index=2) Scalars scalars();
            }
            public final class Checks implements com.reactor.rust.view.NativeViewTestSupport.Fixture {
                final ByteBuffer input = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
                final ByteBuffer output = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);
                final Utf8Slice code;
                final int size;
                volatile long sink;
                public Checks() {
                    byte[] bytes = "ACC-TR-42".getBytes(StandardCharsets.UTF_8);
                    code = new Utf8Slice().reset(ByteBuffer.wrap(bytes), 0, bytes.length);
                    size = SampleAccountView_NativeWriter.write(input, 7, Long.MIN_VALUE, code, Status.OPEN, Long.MAX_VALUE, true);
                }
                public long exercise(int iterations) {
                    long sum=0;
                    for (int i=0; i<iterations; i++) {
                        try (var view = SampleAccountView_NativeView.threadLocalView(input, 7, size)) {
                            sum += view.id();
                            sum += view.code().contentEqualsAscii("ACC-TR-42") ? 1 : 0;
                            sum += view.status().ordinal();
                            sum += view.balanceCents();
                            sum += view.active() ? 1 : 0;
                            output.clear();
                            sum += SampleAccountView_NativeWriter.writeJson(view, output);
                        }
                    }
                    sink=sum;
                    return sum;
                }
                static String json(ByteBuffer out) {
                    byte[] bytes=new byte[out.position()]; out.get(0,bytes);
                    return new String(bytes,StandardCharsets.UTF_8);
                }
                static void assertDetached(Object object) {
                    try {
                        for(Class<?> type=object.getClass(); type!=Object.class; type=type.getSuperclass()) {
                            for(var field:type.getDeclaredFields()) {
                                if(java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                                field.setAccessible(true);
                                Object value=field.get(object);
                                assertFalse(value instanceof ByteBuffer,"Idle view/slice retains a buffer");
                                assertFalse(value instanceof Thread,"Idle view retains its borrowing thread");
                                if(value instanceof NativeFlyweightView || value instanceof Utf8Slice) assertDetached(value);
                            }
                        }
                    } catch(IllegalAccessException e) { throw new AssertionError(e); }
                }
                public void check() {
                    assertEquals(3, SampleAccountView_NativeView.SCHEMA_VERSION);
                    assertEquals(33, SampleAccountView_NativeView.HEADER_SIZE);
                    assertEquals(24, SampleAccountView_NativeView.FIELD_3_OFFSET);
                    assertEquals(0, input.position());
                    assertEquals(ByteOrder.LITTLE_ENDIAN,input.order());
                    assertEquals((byte)0x80,input.get(7));
                    var view=SampleAccountView_NativeView.threadLocalView(input,7,size);
                    Utf8Slice borrowed=view.code();
                    assertEquals(size,view.byteSize());
                    assertEquals(Long.MIN_VALUE,view.id());
                    assertEquals(Status.OPEN,view.status());
                    assertSame(borrowed,view.code());
                    assertSame(input,view.backingBuffer());
                    assertEquals(7,view.baseOffset());
                    assertThrows(IllegalStateException.class,()->SampleAccountView_NativeView.threadLocalView(input,7,size));
                    assertThrows(IllegalStateException.class,()->view.wrap(input,7,size));
                    assertThrows(IllegalArgumentException.class,()->SampleAccountView_NativeWriter.write(view,input,7));
                    assertThrows(IllegalArgumentException.class,()->SampleAccountView_NativeWriter.writeJson(view,input));
                    assertEquals(Long.MIN_VALUE,view.id());
                    output.clear();
                    int jsonSize=SampleAccountView_NativeWriter.writeJson(view,output);
                    assertEquals(output.position(),jsonSize);
                    String expected="{\\"id\\":-9223372036854775808,\\"code\\":\\"ACC-TR-42\\",\\"status\\":\\"OPEN\\",\\"balanceCents\\":9223372036854775807,\\"active\\":true}";
                    assertEquals(expected,json(output));
                    output.clear().limit(5);
                    assertThrows(BufferOverflowException.class,()->SampleAccountView_NativeWriter.writeJson(view,output));
                    output.clear();
                    ByteBuffer binary=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
                    assertEquals(size,SampleAccountView_NativeWriter.write(view,binary,9));
                    assertEquals(0,binary.position());
                    assertEquals(ByteOrder.LITTLE_ENDIAN,binary.order());
                    for(int i=0;i<size;i++) assertEquals(input.get(7+i),binary.get(9+i));
                    Thread other=new Thread(()-> {
                        assertThrows(IllegalStateException.class,view::id);
                        assertThrows(IllegalStateException.class,view::clear);
                    });
                    other.setUncaughtExceptionHandler((t,e)->sink=-999);
                    sink=0; other.start();
                    try { other.join(); } catch(InterruptedException e) { throw new AssertionError(e); }
                    assertEquals(0,sink);
                    view.close(); view.clear();
                    assertDetached(view);
                    assertThrows(IllegalStateException.class,view::id);
                    assertThrows(IllegalStateException.class,view::backingBuffer);
                    assertThrows(IllegalStateException.class,borrowed::byteLength);
                    assertThrows(IndexOutOfBoundsException.class,()->view.wrap(input,Integer.MAX_VALUE,1));
                    assertThrows(IndexOutOfBoundsException.class,()->view.wrap(input,7,Integer.MAX_VALUE));
                    for(int limit=0;limit<SampleAccountView_NativeView.HEADER_SIZE;limit++) {
                        int truncated=limit;
                        assertThrows(IllegalArgumentException.class,()->view.wrap(input,7,truncated));
                    }
                    assertThrows(IllegalArgumentException.class,()->view.wrap(input,7,size-1));
                    assertThrows(IllegalStateException.class,view::id);
                    // Descriptor relative to the record, not the whole buffer, with overflow guards.
                    for(int relative : new int[]{-1,0,32,Integer.MAX_VALUE}) {
                        ViewSupport.write(binary,9+8,4,relative);
                        assertThrows(IllegalArgumentException.class,()->view.wrap(binary,9,size));
                    }
                    SampleAccountView_NativeWriter.write(inputView(),binary,9);
                    ViewSupport.write(binary,9+12,4,Integer.MAX_VALUE);
                    assertThrows(IllegalArgumentException.class,()->view.wrap(binary,9,size));
                    SampleAccountView_NativeWriter.write(inputView(),binary,9);
                    binary.put(9+16,(byte)255);
                    assertThrows(IllegalArgumentException.class,()->view.wrap(binary,9,size));
                    SampleAccountView_NativeWriter.write(inputView(),binary,9);
                    binary.put(9+32,(byte)2);
                    assertThrows(IllegalArgumentException.class,()->view.wrap(binary,9,size));
                    SampleAccountView_NativeWriter.write(inputView(),binary,9);
                    binary.put(9+33,(byte)0xff);
                    assertThrows(IllegalArgumentException.class,()->view.wrap(binary,9,size));
                    assertThrows(IllegalStateException.class,view::id);
                    binary.limit(10);
                    assertThrows(IndexOutOfBoundsException.class,()->SampleAccountView_NativeWriter.write(inputView(),binary,9));
                    binary.clear();
                    assertThrows(ReadOnlyBufferException.class,()->SampleAccountView_NativeWriter.write(inputView(),binary.asReadOnlyBuffer(),0));
                    var again=SampleAccountView_NativeView.threadLocalView(input,7,size);
                    assertSame(view,again); again.close(); assertDetached(again);
                    nestedChecks();
                }
                SampleAccountView inputView() {
                    return new SampleAccountView() {
                        public long id(){return Long.MIN_VALUE;}
                        public Utf8Slice code(){return code;}
                        public Status status(){return Status.OPEN;}
                        public long balanceCents(){return Long.MAX_VALUE;}
                        public boolean active(){return true;}
                    };
                }
                void nestedChecks() {
                    Scalars scalars=new Scalars(){
                        public byte tiny(){return -12;} public int count(){return Integer.MIN_VALUE;}
                        public short small(){return Short.MIN_VALUE;} public char letter(){return '\\u0130';}
                        public float ratio(){return -0.0f;} public double amount(){return 12.5;}
                    };
                    Pair pair=new Pair(){
                        public SampleAccountView first(){return inputView();}
                        public SampleAccountView second(){return inputView();}
                        public Scalars scalars(){return scalars;}
                    };
                    ByteBuffer binary=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);
                    int bytes=Pair_NativeWriter.write(pair,binary,3);
                    assertEquals(Pair_NativeWriter.byteSize(pair),bytes);
                    var root=Pair_NativeView.threadLocalView(binary,3,bytes);
                    // An outstanding top-level borrow of the nested type must remain independent.
                    var independent=SampleAccountView_NativeView.threadLocalView(input,7,size);
                    var first=root.first(); var second=root.second(); var fields=root.scalars();
                    assertNotSame(first,second);
                    assertSame(first,root.first());
                    assertEquals(Long.MIN_VALUE,first.id()); assertEquals(Long.MIN_VALUE,second.id());
                    assertEquals(Long.MIN_VALUE,independent.id());
                    assertNotSame(first,independent);
                    independent.close();
                    assertEquals(Long.MIN_VALUE,first.id());
                    assertEquals(-12,fields.tiny()); assertEquals(Integer.MIN_VALUE,fields.count());
                    assertEquals(Short.MIN_VALUE,fields.small()); assertEquals('\\u0130',fields.letter());
                    assertEquals(Float.floatToRawIntBits(-0.0f),Float.floatToRawIntBits(fields.ratio()));
                    assertEquals(12.5,fields.amount());
                    assertEquals(Integer.MIN_VALUE+1,fields.computed());
                    output.clear(); Scalars_NativeWriter.writeJson(fields,output);
                    assertEquals("{\\"tiny\\":-12,\\"count\\":-2147483648,\\"small\\":-32768,\\"letter\\":\\"\\\\u0130\\",\\"ratio\\":-0.0,\\"amount\\":12.5}",json(output));
                    output.clear(); Pair_NativeWriter.writeJson(root,output);
                    String text=json(output);
                    assertTrue(text.startsWith("{\\"first\\":{\\"id\\":"));
                    assertTrue(text.contains("},\\"second\\":{\\"id\\":"));
                    assertTrue(text.contains("\\"ratio\\":-0.0,\\"amount\\":12.5"));
                    assertTrue(text.endsWith("}}"));
                    root.close();
                    assertDetached(root);
                    assertThrows(IllegalStateException.class,first::id);
                    assertThrows(IllegalStateException.class,second::code);
                    assertThrows(IllegalStateException.class,fields::tiny);
                    assertThrows(IllegalArgumentException.class,()->root.wrap(binary,3,bytes-1));
                    assertThrows(IllegalStateException.class,root::backingBuffer);
                    assertDetached(root);
                    assertThrows(IllegalStateException.class,first::id);
                    // A nested descriptor cannot read its sibling even when the overall buffer fits.
                    ViewSupport.write(binary,3+24+8,4,100);
                    assertThrows(IllegalArgumentException.class,()->root.wrap(binary,3,bytes));
                    assertThrows(IllegalStateException.class,root::first);
                    assertThrows(IllegalArgumentException.class,()->ViewSupport.jsonDouble(output,Double.NaN));
                    assertThrows(IllegalArgumentException.class,()->ViewSupport.jsonFloat(output,Float.POSITIVE_INFINITY));
                }
            }
            """;
}

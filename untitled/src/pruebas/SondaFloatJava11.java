import java.io.*;
import runtime.jdk11.FloatingDecimal;

/** Oráculo fijado a FloatingDecimal de OpenJDK jdk-11+28, independiente del dtoa C. */
public final class SondaFloatJava11 {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.US_ASCII));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.US_ASCII));
        for (String line; (line = in.readLine()) != null;) {
            long bits = Long.parseUnsignedLong(line, 16);
            out.write(line + "\t" + FloatingDecimal.toJavaFormatString(Double.longBitsToDouble(bits))); out.newLine();
        }
        out.flush();
    }
}

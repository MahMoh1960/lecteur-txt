package ma.lecteurtxt;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Détection d'encodage, décodage et encodage des fichiers texte. */
final class TextCodec {

    static final class Result {
        String text;
        Charset charset;
        boolean bom;
        boolean crlf;
    }

    private TextCodec() {}

    static Charset forName(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    static Result decode(byte[] b, Charset forced) {
        Result r = new Result();
        int off = 0;
        Charset bomCs = null;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            bomCs = StandardCharsets.UTF_8;
            off = 3;
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            bomCs = StandardCharsets.UTF_16LE;
            off = 2;
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            bomCs = StandardCharsets.UTF_16BE;
            off = 2;
        }
        Charset cs = forced;
        if (cs == null) {
            cs = (bomCs != null) ? bomCs : detect(b);
        }
        if (bomCs != null && bomCs.equals(cs)) {
            r.bom = true;
        } else {
            off = 0;
        }
        String t = new String(b, off, b.length - off, cs);
        if (t.indexOf("\r\n") >= 0) {
            r.crlf = true;
            t = t.replace("\r\n", "\n");
        }
        if (t.indexOf('\r') >= 0) {
            t = t.replace('\r', '\n');
        }
        r.text = t;
        r.charset = cs;
        return r;
    }

    static Charset detect(byte[] b) {
        int nonAscii = 0;
        int arabic = 0;
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xFF;
            if (c >= 0x80) {
                nonAscii++;
                if ((c >= 0xC1 && c <= 0xD6) || (c >= 0xD8 && c <= 0xDF)
                        || c == 0xE1 || (c >= 0xE3 && c <= 0xE6) || c == 0xEC || c == 0xED) {
                    arabic++;
                }
            }
        }
        if (nonAscii == 0) {
            return StandardCharsets.UTF_8;
        }
        if (isValidUtf8(b)) {
            return StandardCharsets.UTF_8;
        }
        Charset w1256 = forName("windows-1256");
        Charset w1252 = forName("windows-1252");
        if (w1256 != null && arabic >= 3 && arabic * 10L >= nonAscii * 4L) {
            return w1256;
        }
        return w1252 != null ? w1252 : StandardCharsets.ISO_8859_1;
    }

    static boolean isValidUtf8(byte[] b) {
        int i = 0;
        int n = b.length;
        while (i < n) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                i++;
                continue;
            }
            int need;
            if (c >= 0xC2 && c <= 0xDF) {
                need = 1;
            } else if (c >= 0xE0 && c <= 0xEF) {
                need = 2;
            } else if (c >= 0xF0 && c <= 0xF4) {
                need = 3;
            } else {
                return false;
            }
            if (i + need >= n) {
                return false;
            }
            for (int k = 1; k <= need; k++) {
                if ((b[i + k] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += need + 1;
        }
        return true;
    }

    static boolean canEncode(String text, Charset cs, boolean crlf) {
        return cs.newEncoder().canEncode(text);
    }

    static byte[] encode(String text, Charset cs, boolean bom, boolean crlf) {
        String s = crlf ? text.replace("\n", "\r\n") : text;
        byte[] body = s.getBytes(cs);
        byte[] mark = new byte[0];
        if (bom) {
            if (StandardCharsets.UTF_8.equals(cs)) {
                mark = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            } else if (StandardCharsets.UTF_16LE.equals(cs)) {
                mark = new byte[]{(byte) 0xFF, (byte) 0xFE};
            } else if (StandardCharsets.UTF_16BE.equals(cs)) {
                mark = new byte[]{(byte) 0xFE, (byte) 0xFF};
            }
        }
        if (mark.length == 0) {
            return body;
        }
        byte[] out = new byte[mark.length + body.length];
        System.arraycopy(mark, 0, out, 0, mark.length);
        System.arraycopy(body, 0, out, mark.length, body.length);
        return out;
    }
}

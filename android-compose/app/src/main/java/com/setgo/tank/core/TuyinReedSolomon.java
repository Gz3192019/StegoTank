package com.setgo.tank.core;

/**
 * GF(2^8) 上的 Reed–Solomon 前向纠错（与 src/reed-solomon.js 一致）。
 * 本原多项式 0x11d、生成元 2；码字 255 字节，nsym 个校验符号，
 * 最多纠正 floor(nsym/2) 个符号错误。
 */
public final class TuyinReedSolomon {
    private static final int PRIMITIVE = 0x11d;
    private static final int FIELD_SIZE = 256;
    private static final int CODEWORD_BYTES = 255;

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[FIELD_SIZE];

    static {
        int x = 1;
        for (int i = 0; i < CODEWORD_BYTES; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= PRIMITIVE;
        }
        for (int i = CODEWORD_BYTES; i < 512; i++) EXP[i] = EXP[i - CODEWORD_BYTES];
    }

    private final int nsym;
    private final int[] generator;

    public TuyinReedSolomon(int nsym) {
        if (nsym <= 0 || nsym >= CODEWORD_BYTES) {
            throw new IllegalArgumentException("nsym must be in 1..254");
        }
        this.nsym = nsym;
        this.generator = generatorPoly(nsym);
    }

    public int nsym() { return nsym; }

    public double rate() {
        return (CODEWORD_BYTES - nsym) / (double) CODEWORD_BYTES;
    }

    /* ---------------- GF 基础运算 ---------------- */
    static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    static int gfDiv(int a, int b) {
        if (a == 0) return 0;
        return EXP[(LOG[a] - LOG[b] + CODEWORD_BYTES) % CODEWORD_BYTES];
    }

    static int gfInv(int a) {
        return EXP[CODEWORD_BYTES - LOG[a]];
    }

    static int gfPow(int a, int n) {
        int e = ((n % CODEWORD_BYTES) + CODEWORD_BYTES) % CODEWORD_BYTES;
        return EXP[(LOG[a] * e) % CODEWORD_BYTES];
    }

    /* ---------------- 多项式 ---------------- */
    /** [1, 0, 0, ..., 0]，长度 zeros+1。 */
    private static int[] leadingOne(int zeros) {
        int[] r = new int[zeros + 1];
        r[0] = 1;
        return r;
    }

    private static int[] polyScale(int[] p, int x) {        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = gfMul(p[i], x);
        return r;
    }

    private static int[] polyAdd(int[] p, int[] q) {
        int len = Math.max(p.length, q.length);
        int[] r = new int[len];
        for (int i = 0; i < p.length; i++) r[i + len - p.length] = p[i];
        for (int i = 0; i < q.length; i++) r[i + len - q.length] ^= q[i];
        return r;
    }

    private static int[] polyMul(int[] p, int[] q) {
        int[] r = new int[p.length + q.length - 1];
        for (int j = 0; j < q.length; j++) {
            for (int i = 0; i < p.length; i++) r[i + j] ^= gfMul(p[i], q[j]);
        }
        return r;
    }

    private static int polyEval(int[] p, int x) {
        int y = p[0];
        for (int i = 1; i < p.length; i++) y = gfMul(y, x) ^ p[i];
        return y;
    }

    /** 返回 [商, 余] 两段（对齐 JS slice(0, -sep) / slice(-sep) 的负数语义）。 */
    private static int[][] polyDiv(int[] dividend, int[] divisor) {
        int[] out = dividend.clone();
        int limit = dividend.length - (divisor.length - 1);
        for (int i = 0; i < limit; i++) {
            int c = out[i];
            if (c != 0) {
                for (int j = 1; j < divisor.length; j++) {
                    if (divisor[j] != 0) out[i + j] ^= gfMul(divisor[j], c);
                }
            }
        }
        int sep = divisor.length - 1; // |slice 负偏移|
        int qLen = out.length - sep;
        int[] q = new int[Math.max(0, qLen)];
        int[] r = new int[sep];
        System.arraycopy(out, 0, q, 0, Math.max(0, qLen));
        System.arraycopy(out, Math.max(0, qLen), r, 0, sep);
        return new int[][] { q, r };
    }

    private static int[] generatorPoly(int nsym) {
        int[] g = { 1 };
        for (int i = 0; i < nsym; i++) g = polyMul(g, new int[] { 1, gfPow(2, i) });
        return g;
    }

    private static int[] syndromes(int[] msg, int nsym) {
        int[] s = new int[nsym + 1];
        for (int i = 0; i < nsym; i++) s[i + 1] = polyEval(msg, gfPow(2, i));
        return s;
    }

    private static int[] findErrorLocator(int[] synd, int nsym) {
        int[] errLoc = { 1 };
        int[] oldLoc = { 1 };
        int shift = synd.length - nsym;
        for (int i = 0; i < nsym; i++) {
            int K = i + shift;
            int d = synd[K];
            for (int j = 1; j < errLoc.length; j++) {
                d ^= gfMul(errLoc[errLoc.length - 1 - j], synd[K - j]);
            }
            int[] oldLoc2 = new int[oldLoc.length + 1];
            System.arraycopy(oldLoc, 0, oldLoc2, 0, oldLoc.length);
            oldLoc = oldLoc2;
            if (d != 0) {
                if (oldLoc.length > errLoc.length) {
                    int[] next = polyScale(oldLoc, d);
                    oldLoc = polyScale(errLoc, gfInv(d));
                    errLoc = next;
                }
                errLoc = polyAdd(errLoc, polyScale(oldLoc, d));
            }
        }
        int i = 0;
        while (i < errLoc.length && errLoc[i] == 0) i++;
        int[] trimmed = new int[errLoc.length - i];
        System.arraycopy(errLoc, i, trimmed, 0, errLoc.length - i);
        errLoc = trimmed;
        if ((errLoc.length - 1) * 2 > nsym) return null;
        return errLoc;
    }

    private static int[] findErrorPositions(int[] errLoc, int messageLength) {
        int errorCount = errLoc.length - 1;
        int[] positions = new int[errorCount];
        int count = 0;
        for (int i = 0; i < messageLength; i++) {
            if (polyEval(errLoc, gfPow(2, -i)) == 0) {
                positions[count++] = messageLength - 1 - i;
            }
        }
        if (count != errorCount) return null;
        return positions;
    }

    private static int[] correctErrors(int[] msg, int[] synd, int[] errPos) {
        int m = msg.length;
        int[] coefPos = new int[errPos.length];
        for (int i = 0; i < errPos.length; i++) coefPos[i] = m - 1 - errPos[i];
        int[] errLoc = { 1 };
        for (int coefPo : coefPos) {
            errLoc = polyMul(errLoc, polyAdd(new int[] { 1 }, new int[] { gfPow(2, coefPo), 0 }));
        }
        int[] revSynd = new int[synd.length];
        for (int i = 0; i < synd.length; i++) revSynd[i] = synd[synd.length - 1 - i];
        // JS: [1].concat(new Array(errLoc.length).fill(0)) —— 前导 1 的多项式 [1,0,0,...]
        int[][] div = polyDiv(polyMul(revSynd, errLoc), leadingOne(errLoc.length));
        int[] errEval = div[1];
        // reverse errEval
        int[] rev = new int[errEval.length];
        for (int i = 0; i < errEval.length; i++) rev[i] = errEval[errEval.length - 1 - i];
        errEval = rev;

        int[] X = new int[coefPos.length];
        for (int i = 0; i < coefPos.length; i++) X[i] = gfPow(2, coefPos[i]);
        int[] out = msg.clone();
        for (int i = 0; i < X.length; i++) {
            int Xi = X[i];
            int XiInv = gfInv(Xi);
            int den = 1;
            for (int j = 0; j < X.length; j++) {
                if (j != i) den = gfMul(den, 1 ^ gfMul(XiInv, X[j]));
            }
            int[] revErr = new int[errEval.length];
            for (int j2 = 0; j2 < errEval.length; j2++) revErr[j2] = errEval[errEval.length - 1 - j2];
            int y = polyEval(revErr, XiInv);
            y = gfMul(gfPow(Xi, 1), y);
            out[errPos[i]] ^= gfDiv(y, den);
        }
        return out;
    }

    /* ---------------- 对外接口 ---------------- */
    /** 编码一个消息（<= 255-nsym 字节）为 255 字节码字。 */
    public byte[] encode(byte[] message) {
        int n = this.nsym;
        int[] gen = this.generator;
        int[] work = new int[message.length + n];
        for (int i = 0; i < message.length; i++) work[i] = message[i] & 0xff;
        for (int i = 0; i < message.length; i++) {
            int c = work[i];
            if (c != 0) {
                for (int j = 0; j < gen.length; j++) work[i + j] ^= gfMul(gen[j], c);
            }
        }
        byte[] out = new byte[message.length + n];
        for (int i = 0; i < message.length; i++) out[i] = message[i];
        for (int i = 0; i < n; i++) out[message.length + i] = (byte) work[message.length + i];
        return out;
    }

    /** 解码一个码字，纠正至多 floor(nsym/2) 个符号错误；不可纠正返回 null。 */
    public byte[] decode(byte[] received) {
        int n = this.nsym;
        int[] msg = new int[received.length];
        for (int i = 0; i < received.length; i++) msg[i] = received[i] & 0xff;
        int[] synd = syndromes(msg, n);
        boolean allZero = true;
        for (int s : synd) {
            if (s != 0) { allZero = false; break; }
        }
        if (allZero) {
            byte[] out = new byte[msg.length];
            for (int i = 0; i < msg.length; i++) out[i] = (byte) msg[i];
            return out;
        }

        int[] errLoc = findErrorLocator(synd, n);
        if (errLoc == null) return null;
        int[] errPos = findErrorPositions(errLoc, msg.length);
        if (errPos == null) return null;

        int[] corrected = correctErrors(msg, synd, errPos);
        int[] check = syndromes(corrected, n);
        for (int s : check) {
            if (s != 0) return null;
        }
        byte[] out = new byte[corrected.length];
        for (int i = 0; i < corrected.length; i++) out[i] = (byte) corrected[i];
        return out;
    }
}

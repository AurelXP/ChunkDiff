package dev.chunkdiff.delta;

import com.github.luben.zstd.Zstd;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Encode/decode un ChunkDelta en bytes compresses (zstd). Format versionne. */
public final class DeltaCodec {
    private static final int VERSION = 1;
    private static final int ZSTD_LEVEL = 9;

    private DeltaCodec() {}

    public static byte[] encode(ChunkDelta d) throws IOException {
        if (d.palette().size() > 0xFFFF) throw new IOException("palette trop grande");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(VERSION);
        out.writeInt(d.chunkX());
        out.writeInt(d.chunkZ());
        out.writeShort(d.palette().size());
        for (String s : d.palette()) out.writeUTF(s);
        out.writeInt(d.blocks().size());
        for (ChunkDelta.BlockChange b : d.blocks()) {
            if (b.paletteIndex() < 0 || b.paletteIndex() >= d.palette().size())
                throw new IOException("index de palette invalide: " + b.paletteIndex());
            out.writeByte(b.x());
            out.writeShort(b.y());
            out.writeByte(b.z());
            out.writeShort(b.paletteIndex());
        }
        writeBlobs(out, d.blockEntitiesNbt());
        writeBlobs(out, d.entitiesNbt());
        out.flush();

        byte[] rawBytes = raw.toByteArray();
        byte[] packed = Zstd.compress(rawBytes, ZSTD_LEVEL);
        ByteArrayOutputStream res = new ByteArrayOutputStream(packed.length + 4);
        DataOutputStream ro = new DataOutputStream(res);
        ro.writeInt(rawBytes.length);
        ro.write(packed);
        return res.toByteArray();
    }

    public static ChunkDelta decode(byte[] data) throws IOException {
        DataInputStream head = new DataInputStream(new ByteArrayInputStream(data));
        int rawLen = head.readInt();
        if (rawLen < 0 || rawLen > (64 << 20)) throw new IOException("taille brute invalide: " + rawLen);
        byte[] packed = new byte[data.length - 4];
        System.arraycopy(data, 4, packed, 0, packed.length);
        byte[] raw = Zstd.decompress(packed, rawLen);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int version = in.readUnsignedByte();
        if (version != VERSION) throw new IOException("version de delta inconnue: " + version);
        int cx = in.readInt();
        int cz = in.readInt();
        int pSize = in.readUnsignedShort();
        List<String> palette = new ArrayList<>(pSize);
        for (int i = 0; i < pSize; i++) palette.add(in.readUTF());
        int n = in.readInt();
        List<ChunkDelta.BlockChange> blocks = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            byte x = in.readByte();
            short y = in.readShort();
            byte z = in.readByte();
            int idx = in.readUnsignedShort();
            if (idx >= pSize) throw new IOException("index de palette hors limites: " + idx);
            blocks.add(new ChunkDelta.BlockChange(x, y, z, idx));
        }
        List<byte[]> be = readBlobs(in);
        List<byte[]> ent = readBlobs(in);
        return new ChunkDelta(cx, cz, palette, blocks, be, ent);
    }

    private static void writeBlobs(DataOutputStream out, List<byte[]> blobs) throws IOException {
        out.writeInt(blobs.size());
        for (byte[] b : blobs) {
            out.writeInt(b.length);
            out.write(b);
        }
    }

    private static List<byte[]> readBlobs(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<byte[]> res = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            int len = in.readInt();
            if (len < 0 || len > (16 << 20)) throw new IOException("blob invalide: " + len);
            byte[] b = new byte[len];
            in.readFully(b);
            res.add(b);
        }
        return res;
    }
}

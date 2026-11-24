package com.motexa.ninebotbletest;

/** Parses the checksummed manufacturer envelopes verified against official 6.10.11. */
public final class NinebotAdvertisement {
    public final boolean recognized;
    public final boolean valid;
    public final int authVersion;
    public final String reason;

    private NinebotAdvertisement(boolean recognized, boolean valid, int authVersion, String reason) {
        this.recognized = recognized;
        this.valid = valid;
        this.authVersion = authVersion;
        this.reason = reason;
    }

    public static NinebotAdvertisement parse(byte[] ad) {
        boolean recognized = false;
        int version = -1;
        if (ad == null) return new NinebotAdvertisement(false, false, -1, "missing");
        for (int offset = 0; offset < ad.length;) {
            int size = ad[offset++] & 255;
            if (size == 0) break;
            if (offset + size > ad.length) return new NinebotAdvertisement(recognized, false, -1, "truncated_ad");
            int type = ad[offset] & 255;
            if (type == 255 && size >= 3) {
                int id = ((ad[offset + 1] & 255) << 8) | (ad[offset + 2] & 255);
                if (id == 0x4e42 || id == 0x4e43 || id == 0x1f0f) {
                    recognized = true;
                    int begin = offset + 3, end = offset + size;
                    int minimum = id == 0x4e43 ? 4 : id == 0x4e42 ? 3 : 2;
                    if (end - begin < minimum) return new NinebotAdvertisement(true, false, -1, "short_manufacturer");
                    int sum = 0;
                    for (int i = begin; i < end - 1; i++) sum += ad[i] & 255;
                    if (((~sum) & 255) != (ad[end - 1] & 255))
                        return new NinebotAdvertisement(true, false, -1, "checksum");
                    int found = ad[begin + (id == 0x4e42 ? 1 : id == 0x4e43 ? 2 : 0)] & 255;
                    if (id == 0x1f0f) {
                        found &= 15;
                        for (int i = begin + 1; i < end - 1;) {
                            int length = ad[i++] & 31;
                            if (i + length > end - 1) return new NinebotAdvertisement(true, false, -1, "truncated_tlv");
                            i += length;
                        }
                    }
                    if (version >= 0 && version != found)
                        return new NinebotAdvertisement(true, false, -1, "conflicting_versions");
                    version = found;
                }
            }
            offset += size;
        }
        return new NinebotAdvertisement(recognized, recognized && version >= 0, version,
                recognized ? "validated" : "unknown_manufacturer");
    }
}

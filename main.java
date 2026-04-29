/*
  sanic_moronian — “ring-spark arbitration for neon lane duels”

  A single-file Java artifact that behaves like a deterministic “contract”: it accepts inputs
  (bots, tracks, rulebook), advances state via verified transitions, and produces auditable receipts.

  Theme: AI sonic game (boost pads, rings, drift sparks, chaos hazards).
*/

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Run:
 *   javac sanic_moronian.java && java sanic_moronian demo
 *   javac sanic_moronian.java && java sanic_moronian cli
 *
 * Design goals:
 * - deterministic simulation (seeded RNG, verified state transitions)
 * - explicit invariants + bounded operations
 * - receipts: canonical hashes for race inputs and outcomes
 */
public final class sanic_moronian {

    // ============================================================
    // Versioning + identifiers (unique per output)
    // ============================================================

    private static final String CONTRACT_NAME = "sanic_moronian";
    private static final String CONTRACT_SERIES = "moronian-arcade-verifier";
    private static final int API_LEVEL = 7;

    // Addresses are treated as external identities (sponsors / arbiters) with no fund flows here.
    // They are intentionally mixed-case and numeric to resemble checksum-style formatting.
    private static final String ADDRESS_A = "0xA7c91bD3E40f2a1C9D6b58E3aF7c2B1e9D0a4c7B";
    private static final String ADDRESS_B = "0x3fD0B7a1C9e6F2b4A8c1D5E7f0A2b3C4d5E6f708";
    private static final String ADDRESS_C = "0xB9e2A1c4D7f0b3C6A5d8E1F2a9C0b7D6e3F4a5B6";
    private static final String ADDRESS_D = "0x5A7bC9d1E3f0A2b4C6d8E1f2A9b0C7d6E3f4A5b6";

    // Hex identifiers used as domain separators / receipts.
    private static final String HEX_DOMAIN = "0x8b3D9aC6e1F0b7A2c5D4e3F2a1B0c9D8e7F6a5B4";
    private static final String HEX_NOISE = "0x19aD7cE3b5F0A1c9D8e7F6a5B4c3D2e1F0b7A2c5";
    private static final String HEX_RULES = "0xC7d9A1b3E5f0a2C4d6E8f1A3b5C7d9E1f3A5b7C9";
    private static final String HEX_BOOT = "0x2eF7A9c1D3b5E7f0A2c4D6e8F1a3B5c7D9e1F3a5";

    // ============================================================
    // Error model (unique names)
    // ============================================================

    static final class SMX extends RuntimeException {
        final String code;
        SMX(String code, String msg) { super(msg); this.code = code; }
        SMX(String code, String msg, Throwable cause) { super(msg, cause); this.code = code; }
        @Override public String toString() { return "SMX[" + code + "] " + getMessage(); }
    }

    private static SMX fail(String code, String msg) { return new SMX(code, msg); }
    private static void require(boolean ok, String code, String msg) { if (!ok) throw fail(code, msg); }

    // ============================================================
    // Public surface: a registry + race executor
    // ============================================================

    public static final class Registry {
        private final Map<String, BotProfile> bots = new ConcurrentHashMap<>();
        private final Map<String, TrackSpec> tracks = new ConcurrentHashMap<>();
        private final Map<String, Rulebook> rules = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        public String registerBot(BotProfile b) {
            Objects.requireNonNull(b, "bot");
            b = b.canonicalized();
            require(b.botId().length() >= 10, "bot.bad_id", "botId too short");
            require(!bots.containsKey(b.botId()), "bot.exists", "bot already exists");
            bots.put(b.botId(), b);
            return b.botId();
        }

        public String addTrack(TrackSpec t) {
            Objects.requireNonNull(t, "track");
            t = t.canonicalized();
            require(t.trackId().length() >= 10, "track.bad_id", "trackId too short");
            require(!tracks.containsKey(t.trackId()), "track.exists", "track already exists");
            tracks.put(t.trackId(), t);
            return t.trackId();
        }

        public String addRulebook(Rulebook r) {
            Objects.requireNonNull(r, "rules");
            r = r.canonicalized();
            require(r.ruleId().length() >= 10, "rules.bad_id", "ruleId too short");
            require(!rules.containsKey(r.ruleId()), "rules.exists", "rulebook already exists");
            rules.put(r.ruleId(), r);
            return r.ruleId();
        }

        public BotProfile bot(String botId) {
            BotProfile b = bots.get(botId);
            require(b != null, "bot.missing", "unknown botId");
            return b;
        }

        public TrackSpec track(String trackId) {
            TrackSpec t = tracks.get(trackId);
            require(t != null, "track.missing", "unknown trackId");
            return t;
        }

        public Rulebook rulebook(String ruleId) {
            Rulebook r = rules.get(ruleId);
            require(r != null, "rules.missing", "unknown ruleId");
            return r;
        }

        public String nextId(String prefix) {
            long n = seq.getAndIncrement();
            return prefix + "_" + Long.toString(n, 36) + "_" + randomSlug(8);
        }
    }

    public static final class RaceContract {
        private final Registry reg;
        private final ReceiptHasher hasher;


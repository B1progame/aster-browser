package app.aster.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parses Tor's GETINFO circuit-status and stream-status replies without retaining destinations. */
public final class TorCircuitSnapshotParser {
    private TorCircuitSnapshotParser() {}

    public static Route select(String circuitStatus, String streamStatus, String currentHost) {
        List<Circuit> circuits = parseCircuits(circuitStatus);
        if (circuits.isEmpty()) return null;
        String wanted = normalizeHost(currentHost);
        if (!wanted.isEmpty()) {
            String selectedId = findStreamCircuit(streamStatus, wanted);
            if (selectedId != null) {
                for (Circuit circuit : circuits) {
                    if (circuit.id.equals(selectedId)) return new Route(circuit, true);
                }
            }
        }
        Circuit latest = circuits.get(0);
        for (Circuit circuit : circuits) {
            if (circuit.created.compareTo(latest.created) > 0) latest = circuit;
        }
        return new Route(latest, false);
    }

    private static List<Circuit> parseCircuits(String data) {
        if (data == null || data.isBlank()) return Collections.emptyList();
        List<Circuit> circuits = new ArrayList<>();
        for (String line : data.split("\\r?\\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3 || !"BUILT".equals(parts[1])) continue;
            String purpose = "";
            String created = "";
            for (int i = 2; i < parts.length; i++) {
                if (parts[i].startsWith("PURPOSE=")) purpose = parts[i].substring("PURPOSE=".length());
                else if (parts[i].startsWith("TIME_CREATED=")) created = parts[i].substring("TIME_CREATED=".length());
            }
            if (!"GENERAL".equals(purpose)) continue;
            String path = parts[2];
            if (path.startsWith("PURPOSE=") || path.startsWith("BUILD_FLAGS=") || path.isEmpty()) continue;
            List<Relay> relays = new ArrayList<>();
            for (String item : path.split(",")) {
                Relay relay = parseRelay(item);
                if (relay != null) relays.add(relay);
            }
            if (!relays.isEmpty()) circuits.add(new Circuit(parts[0], created, relays));
        }
        return circuits;
    }

    private static Relay parseRelay(String raw) {
        String token = raw.trim();
        if (token.isEmpty()) return null;
        if (token.startsWith("$")) token = token.substring(1);
        String identity = token;
        String nickname = "";
        int tilde = token.indexOf('~');
        if (tilde >= 0) {
            identity = token.substring(0, tilde);
            nickname = token.substring(tilde + 1);
        }
        // Some older Tor versions provide only nicknames; keep them displayable but do not
        // query GETINFO using an unvalidated token.
        String fingerprint = identity.matches("(?i)[0-9a-f]{40}") ? identity.toUpperCase(Locale.ROOT) : "";
        if (nickname.isBlank()) nickname = fingerprint.isEmpty() ? identity : "Relay " + fingerprint.substring(0, 8);
        if (nickname.length() > 64) nickname = nickname.substring(0, 64);
        return new Relay(fingerprint, nickname);
    }

    private static String findStreamCircuit(String data, String wantedHost) {
        if (data == null) return null;
        String selected = null;
        for (String line : data.split("\\r?\\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4 || "0".equals(parts[2])) continue;
            if (!("SUCCEEDED".equals(parts[1]) || "SENTCONNECT".equals(parts[1]) || "REMAP".equals(parts[1]))) continue;
            String targetHost = normalizeHost(parts[3]);
            if (wantedHost.equals(targetHost)) selected = parts[2];
        }
        return selected;
    }

    private static String normalizeHost(String value) {
        if (value == null) return "";
        String target = value.trim().toLowerCase(Locale.ROOT);
        int scheme = target.indexOf("://");
        if (scheme >= 0) target = target.substring(scheme + 3);
        int slash = target.indexOf('/');
        if (slash >= 0) target = target.substring(0, slash);
        if (target.startsWith("[")) {
            int end = target.indexOf(']');
            return end < 0 ? target : target.substring(1, end);
        }
        int colon = target.lastIndexOf(':');
        if (colon > 0 && target.indexOf(':') == colon) target = target.substring(0, colon);
        return target;
    }

    /** Parses the IPv4/IPv6 address field from a Tor consensus `r` line. */
    public static String routerAddress(String routerLine) {
        if (routerLine == null) return "";
        String[] fields = routerLine.trim().split("\\s+");
        // r nickname identity digest publication-date publication-time IP ORPort DirPort
        if (fields.length < 7 || !"r".equals(fields[0])) return "";
        String ip = fields[6];
        return ip.matches("[0-9a-fA-F:.]+") && ip.length() <= 64 ? ip : "";
    }

    public static final class Route {
        public final String circuitId;
        public final boolean siteCircuit;
        public final List<Relay> relays;
        private Route(Circuit circuit, boolean siteCircuit) {
            this.circuitId = circuit.id;
            this.siteCircuit = siteCircuit;
            this.relays = Collections.unmodifiableList(new ArrayList<>(circuit.relays));
        }
    }

    public static final class Relay {
        public final String fingerprint;
        public final String nickname;
        public String country = "";
        private Relay(String fingerprint, String nickname) {
            this.fingerprint = fingerprint;
            this.nickname = nickname;
        }
        public String role(int index, int count) {
            if (index == 0) return "Guard";
            if (index == count - 1) return "Exit";
            return "Middle";
        }
    }

    private static final class Circuit {
        final String id;
        final String created;
        final List<Relay> relays;
        Circuit(String id, String created, List<Relay> relays) {
            this.id = id;
            this.created = created;
            this.relays = relays;
        }
    }
}

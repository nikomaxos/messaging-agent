package com.messagingagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/**
 * BI analytics service — computes grouped traffic stats, spam suspects,
 * and AIT (Artificially Inflated Traffic) suspects from message_log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrafficAnalyticsService {

    private final DataSource dataSource;

    // ── By Sender ID ──────────────────────────────────────────────────
    public List<Map<String, Object>> bySender(Instant since, int limit) {
        String sql = """
            SELECT source_address,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status = 'DELIVERED') AS delivered,
                   COUNT(*) FILTER (WHERE status IN ('FAILED','RCS_FAILED')) AS failed,
                   COUNT(DISTINCT destination_address) AS unique_destinations,
                   MIN(created_at) AS first_seen,
                   MAX(created_at) AS last_seen
            FROM message_log
            WHERE created_at >= ? AND source_address IS NOT NULL
            GROUP BY source_address
            ORDER BY total DESC
            LIMIT ?
            """;
        return queryList(sql, since, limit, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sender", rs.getString("source_address"));
            long total = rs.getLong("total");
            long delivered = rs.getLong("delivered");
            long failed = rs.getLong("failed");
            row.put("total", total);
            row.put("delivered", delivered);
            row.put("failed", failed);
            row.put("deliveryRate", total > 0 ? Math.round(delivered * 1000.0 / total) / 10.0 : 0);
            row.put("failRate", total > 0 ? Math.round(failed * 1000.0 / total) / 10.0 : 0);
            row.put("uniqueDestinations", rs.getLong("unique_destinations"));
            row.put("firstSeen", rs.getTimestamp("first_seen").toInstant().toString());
            row.put("lastSeen", rs.getTimestamp("last_seen").toInstant().toString());
            return row;
        });
    }

    // ── By Content ────────────────────────────────────────────────────
    public List<Map<String, Object>> byContent(Instant since, int limit) {
        String sql = """
            SELECT message_text,
                   COUNT(*) AS total,
                   COUNT(DISTINCT destination_address) AS unique_destinations,
                   COUNT(DISTINCT source_address) AS unique_senders,
                   MIN(created_at) AS first_seen,
                   MAX(created_at) AS last_seen
            FROM message_log
            WHERE created_at >= ? AND message_text IS NOT NULL AND message_text != ''
            GROUP BY message_text
            ORDER BY total DESC
            LIMIT ?
            """;
        return queryList(sql, since, limit, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String text = rs.getString("message_text");
            row.put("content", text.length() > 120 ? text.substring(0, 120) + "…" : text);
            row.put("fullContent", text);
            row.put("total", rs.getLong("total"));
            row.put("uniqueDestinations", rs.getLong("unique_destinations"));
            row.put("uniqueSenders", rs.getLong("unique_senders"));
            row.put("firstSeen", rs.getTimestamp("first_seen").toInstant().toString());
            row.put("lastSeen", rs.getTimestamp("last_seen").toInstant().toString());
            return row;
        });
    }

    // ── Spam Suspects ─────────────────────────────────────────────────
    public List<Map<String, Object>> spamSuspects(Instant since) {
        // Step 1: Get per-sender stats
        List<Map<String, Object>> senders = bySender(since, 500);
        List<Map<String, Object>> suspects = new ArrayList<>();

        for (Map<String, Object> s : senders) {
            String sender = (String) s.get("sender");
            long total = ((Number) s.get("total")).longValue();
            List<String> reasons = new ArrayList<>();
            int score = 0;

            // Heuristic 1: High volume (>100 msgs in window)
            if (total > 100) {
                reasons.add("HIGH_VOLUME");
                score += 30;
            }

            // Heuristic 2: Content repetition (>50% identical content)
            double repetitionRate = getContentRepetitionRate(sender, since);
            if (repetitionRate > 0.50) {
                reasons.add("REPETITIVE_CONTENT");
                score += 40;
            }

            // Heuristic 3: Burst rate (>10 msgs/min in any 5-min window)
            double maxBurstRate = getMaxBurstRate(sender, since);
            if (maxBurstRate > 10) {
                reasons.add("BURST_RATE");
                score += 30;
            }

            if (!reasons.isEmpty()) {
                Map<String, Object> suspect = new LinkedHashMap<>(s);
                suspect.put("reasons", reasons);
                suspect.put("score", Math.min(score, 100));
                suspect.put("repetitionRate", Math.round(repetitionRate * 1000) / 10.0);
                suspect.put("maxBurstPerMin", Math.round(maxBurstRate * 10) / 10.0);
                suspects.add(suspect);
            }
        }

        suspects.sort((a, b) -> Integer.compare(
                ((Number) b.get("score")).intValue(), ((Number) a.get("score")).intValue()));
        return suspects;
    }

    // ── AIT Suspects ──────────────────────────────────────────────────
    public List<Map<String, Object>> aitSuspects(Instant since) {
        // AIT = Artificially Inflated Traffic
        // Look for senders with suspicious destination patterns
        String sql = """
            SELECT source_address,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status IN ('FAILED','RCS_FAILED')) AS failed,
                   COUNT(DISTINCT destination_address) AS unique_dests,
                   array_agg(DISTINCT destination_address) AS destinations
            FROM message_log
            WHERE created_at >= ? AND source_address IS NOT NULL
            GROUP BY source_address
            HAVING COUNT(*) >= 20
            ORDER BY total DESC
            LIMIT 100
            """;

        List<Map<String, Object>> suspects = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString("source_address");
                    long total = rs.getLong("total");
                    long failed = rs.getLong("failed");
                    long uniqueDests = rs.getLong("unique_dests");
                    java.sql.Array destArray = rs.getArray("destinations");
                    String[] destinations = destArray != null ? (String[]) destArray.getArray() : new String[0];

                    List<String> reasons = new ArrayList<>();
                    int score = 0;

                    // Heuristic 1: High failure rate (>90%)
                    double failRate = total > 0 ? (double) failed / total : 0;
                    if (failRate > 0.90 && total >= 20) {
                        reasons.add("HIGH_FAILURE_RATE");
                        score += 35;
                    }

                    // Heuristic 2: Sequential destination numbers
                    double seqScore = computeSequentialScore(destinations);
                    if (seqScore > 0.5 && uniqueDests >= 10) {
                        reasons.add("SEQUENTIAL_NUMBERS");
                        score += 35;
                    }

                    // Heuristic 3: Narrow number range
                    long range = computeNumberRange(destinations);
                    if (range > 0 && range < 100 && uniqueDests >= 10) {
                        reasons.add("NARROW_RANGE");
                        score += 30;
                    }

                    if (!reasons.isEmpty()) {
                        Map<String, Object> suspect = new LinkedHashMap<>();
                        suspect.put("sender", sender);
                        suspect.put("total", total);
                        suspect.put("failed", failed);
                        suspect.put("failRate", Math.round(failRate * 1000) / 10.0);
                        suspect.put("uniqueDestinations", uniqueDests);
                        suspect.put("reasons", reasons);
                        suspect.put("score", Math.min(score, 100));
                        suspect.put("numberRange", range);
                        suspect.put("sequentialScore", Math.round(seqScore * 1000) / 10.0);
                        suspects.add(suspect);
                    }
                }
            }
        } catch (Exception e) {
            log.error("AIT detection query failed", e);
        }

        suspects.sort((a, b) -> Integer.compare(
                ((Number) b.get("score")).intValue(), ((Number) a.get("score")).intValue()));
        return suspects;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private double getContentRepetitionRate(String sender, Instant since) {
        String sql = """
            SELECT COALESCE(
                (SELECT COUNT(*) FROM message_log WHERE source_address = ? AND created_at >= ?
                 AND message_text = (
                     SELECT message_text FROM message_log WHERE source_address = ? AND created_at >= ?
                     GROUP BY message_text ORDER BY COUNT(*) DESC LIMIT 1
                 ))::double precision /
                NULLIF((SELECT COUNT(*) FROM message_log WHERE source_address = ? AND created_at >= ?), 0),
            0)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setTimestamp(2, java.sql.Timestamp.from(since));
            ps.setString(3, sender);
            ps.setTimestamp(4, java.sql.Timestamp.from(since));
            ps.setString(5, sender);
            ps.setTimestamp(6, java.sql.Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private double getMaxBurstRate(String sender, Instant since) {
        // Count max messages in any 5-minute bucket, convert to per-minute
        String sql = """
            SELECT COALESCE(MAX(cnt), 0) / 5.0 AS burst_per_min FROM (
                SELECT COUNT(*) AS cnt
                FROM message_log
                WHERE source_address = ? AND created_at >= ?
                GROUP BY date_trunc('minute', created_at) - (EXTRACT(MINUTE FROM created_at)::int % 5) * interval '1 minute'
            ) sub
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setTimestamp(2, java.sql.Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private double computeSequentialScore(String[] destinations) {
        if (destinations.length < 3) return 0;
        List<Long> numbers = new ArrayList<>();
        for (String d : destinations) {
            try {
                numbers.add(Long.parseLong(d.replaceAll("[^0-9]", "")));
            } catch (NumberFormatException ignored) {}
        }
        if (numbers.size() < 3) return 0;
        Collections.sort(numbers);

        int sequential = 0;
        for (int i = 1; i < numbers.size(); i++) {
            long gap = numbers.get(i) - numbers.get(i - 1);
            if (gap >= 1 && gap <= 5) sequential++;
        }
        return (double) sequential / (numbers.size() - 1);
    }

    private long computeNumberRange(String[] destinations) {
        if (destinations.length < 2) return 0;
        List<Long> numbers = new ArrayList<>();
        for (String d : destinations) {
            try {
                numbers.add(Long.parseLong(d.replaceAll("[^0-9]", "")));
            } catch (NumberFormatException ignored) {}
        }
        if (numbers.size() < 2) return 0;
        return Collections.max(numbers) - Collections.min(numbers);
    }

    // Generic query helper
    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws Exception;
    }

    private <T> List<T> queryList(String sql, Instant since, int limit, RowMapper<T> mapper) {
        List<T> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(since));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
            }
        } catch (Exception e) {
            log.error("Analytics query failed: {}", e.getMessage(), e);
        }
        return results;
    }
}

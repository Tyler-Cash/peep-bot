package dev.tylercash.event.rewind;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventClusteringService {

    private final EmbeddingService embeddingService;
    private final RewindConfiguration config;

    @PersistenceContext
    private EntityManager entityManager;

    @Scheduled(fixedDelayString = "PT10M")
    @SchedulerLock(name = "updateEventClusters", lockAtLeastFor = "PT2M", lockAtMostFor = "PT15M")
    @Transactional
    public void updateClusters() {
        if (!config.isEnabled()) return;

        if (embeddingService.isEmbeddingsAvailable() && hasEmbeddings()) {
            clusterByEmbeddings();
        } else {
            clusterByName();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasEmbeddings() {
        Number count = (Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM event_embedding")
                .getSingleResult();
        return count.longValue() > 0;
    }

    @SuppressWarnings("unchecked")
    private void clusterByEmbeddings() {
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT ee.event_id, ee.name_text, ee.embedding::text FROM event_embedding ee")
                .getResultList();

        if (rows.isEmpty()) return;

        int n = rows.size();
        UUID[] ids = new UUID[n];
        String[] names = new String[n];
        List<List<Double>> embeddings = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            Object[] row = rows.get(i);
            ids[i] = (UUID) row[0];
            names[i] = (String) row[1];
            embeddings.add(parseVector((String) row[2]));
        }

        int[] parent = new int[n];
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        double threshold = config.getClusterSimilarityThreshold();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (cosineSimilarity(embeddings.get(i), embeddings.get(j)) >= threshold) {
                    union(parent, rank, i, j);
                }
            }
        }

        Map<Integer, List<Integer>> clusters = new HashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        writeCategories(ids, names, clusters);
    }

    @SuppressWarnings("unchecked")
    private void clusterByName() {
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT e.id, e.name FROM event e")
                .getResultList();

        if (rows.isEmpty()) return;

        int n = rows.size();
        UUID[] ids = new UUID[n];
        String[] names = new String[n];
        int[] parent = new int[n];
        int[] rank = new int[n];
        Map<String, Integer> nameToFirst = new HashMap<>();

        for (int i = 0; i < n; i++) {
            Object[] row = rows.get(i);
            ids[i] = (UUID) row[0];
            names[i] = (String) row[1];
            parent[i] = i;
            String normalized = names[i].trim().toLowerCase();
            int idx = i;
            nameToFirst.compute(normalized, (k, existing) -> {
                if (existing == null) return idx;
                union(parent, rank, idx, existing);
                return existing;
            });
        }

        Map<Integer, List<Integer>> clusters = new HashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        writeCategories(ids, names, clusters);
    }

    private void writeCategories(UUID[] ids, String[] names, Map<Integer, List<Integer>> clusters) {
        entityManager.createNativeQuery("DELETE FROM event_category").executeUpdate();

        for (List<Integer> members : clusters.values()) {
            String label = mostFrequentName(names, members);
            UUID centroidId = ids[members.get(0)];

            for (int idx : members) {
                entityManager
                        .createNativeQuery("INSERT INTO event_category "
                                + "(event_id, category_label, category_centroid_event_id, assigned_at) "
                                + "VALUES (CAST(:eventId AS UUID), :label, CAST(:centroidId AS UUID), NOW())")
                        .setParameter("eventId", ids[idx].toString())
                        .setParameter("label", label)
                        .setParameter("centroidId", centroidId.toString())
                        .executeUpdate();
            }
        }

        log.info("Updated {} event categories across {} clusters", ids.length, clusters.size());
    }

    private String mostFrequentName(String[] names, List<Integer> members) {
        Map<String, Long> counts = members.stream()
                .collect(Collectors.groupingBy(i -> names[i].trim().toLowerCase(), Collectors.counting()));
        String bestLower = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .get()
                .getKey();
        return members.stream()
                .filter(i -> names[i].trim().toLowerCase().equals(bestLower))
                .findFirst()
                .map(i -> names[i].trim())
                .orElse(names[members.get(0)].trim());
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            double ai = a.get(i), bi = b.get(i);
            dot += ai * bi;
            normA += ai * ai;
            normB += bi * bi;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private int find(int[] parent, int i) {
        if (parent[i] != i) parent[i] = find(parent, parent[i]);
        return parent[i];
    }

    private void union(int[] parent, int[] rank, int i, int j) {
        int ri = find(parent, i), rj = find(parent, j);
        if (ri == rj) return;
        if (rank[ri] < rank[rj]) parent[ri] = rj;
        else if (rank[ri] > rank[rj]) parent[rj] = ri;
        else {
            parent[rj] = ri;
            rank[ri]++;
        }
    }

    private List<Double> parseVector(String vectorStr) {
        String inner = vectorStr.substring(1, vectorStr.length() - 1);
        return Arrays.stream(inner.split(",")).map(Double::parseDouble).collect(Collectors.toList());
    }
}

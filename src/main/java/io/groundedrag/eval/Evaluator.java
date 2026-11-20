package io.groundedrag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import io.groundedrag.chat.ChatAnswer;
import io.groundedrag.chat.RagService;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.ingestion.IngestionService;
import io.groundedrag.retrieval.ConfidenceLevel;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.RetrievalMode;
import io.groundedrag.retrieval.RetrievalResult;
import io.groundedrag.store.KnowledgeStore;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline evaluation over the golden set. Two levels:
 * <ul>
 *   <li>Retrieval, per mode (hybrid / vector / keyword): recall@k, MRR, nDCG@k at document level.
 *       This is the ablation that justifies hybrid search with numbers instead of opinion.</li>
 *   <li>End to end: answer contains the expected fact, answer is grounded (verifier), and
 *       unanswerable questions are declined instead of answered.</li>
 * </ul>
 * Runs in CI as a quality gate (see RetrievalQualityGateTest) and against real models with
 * {@code --rag.eval.run=true}.
 */
public class Evaluator {

    public record RetrievalScores(RetrievalMode mode, int k, double recallAtK, double mrr, double ndcgAtK,
            List<String> misses) {
    }

    /** Model-graded scores are null when no judge model was configured. */
    public record AnswerScores(int answerable, double factAccuracy, double groundedRate, int unanswerable,
            double abstentionAccuracy, double falseAbstentionRate, Double faithfulness, Double contextConsistency,
            Double factCheck, List<String> failures) {
    }

    /**
     * Model-based graders, all optional: our claim-by-claim {@link FaithfulnessJudge}, and Spring
     * AI's built-in {@code RelevancyEvaluator} (is the response in line with the retrieved context)
     * and {@code FactCheckingEvaluator} (is the whole answer supported by the context).
     */
    public record Graders(FaithfulnessJudge judge, org.springframework.ai.evaluation.Evaluator contextConsistency,
            org.springframework.ai.evaluation.Evaluator factCheck) {

        public static Graders none() {
            return new Graders(null, null, null);
        }
    }

    private final KnowledgeStore store;
    private final IngestionService ingestion;
    private final Embedder embedder;
    private final HybridRetriever retriever;

    public Evaluator(KnowledgeStore store, IngestionService ingestion, Embedder embedder, HybridRetriever retriever) {
        this.store = store;
        this.ingestion = ingestion;
        this.embedder = embedder;
        this.retriever = retriever;
    }

    public static List<EvalCase> loadGoldenSet(Path file) {
        try {
            return JsonMapper.builder().build().readValue(Files.readString(file), new TypeReference<List<EvalCase>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ingests every file under corpusDir/&lt;space&gt;/ synchronously. Returns documents indexed. */
    public int ingestCorpus(Path corpusDir) {
        int n = 0;
        try (Stream<Path> spaces = Files.list(corpusDir)) {
            for (Path spaceDir : spaces.filter(Files::isDirectory).sorted().toList()) {
                SpaceId space = SpaceId.of(spaceDir.getFileName().toString());
                try (Stream<Path> files = Files.list(spaceDir)) {
                    for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                        byte[] bytes = Files.readAllBytes(f);
                        DocumentRecord doc = store.createDocument(space, f.getFileName().toString(), sha(bytes), "text/markdown");
                        ingestion.index(doc, bytes);
                        DocumentRecord after = store.findDocument(space, doc.id()).orElseThrow();
                        if (after.status() != DocumentStatus.ACTIVE) {
                            throw new IllegalStateException("Eval corpus file failed to index: " + f + " " + after.error());
                        }
                        n++;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }

    public RetrievalScores retrieval(List<EvalCase> cases, RetrievalMode mode, int k) {
        double recall = 0, mrr = 0, ndcg = 0;
        int n = 0;
        List<String> misses = new ArrayList<>();
        for (EvalCase c : cases) {
            if (!c.answerable()) {
                continue;
            }
            n++;
            RetrievalResult r = retriever.retrieve(c.question(), embedder.embed(c.question()), spaces(c), mode, k);
            List<String> rankedDocs = distinctSources(r.chunks());
            Set<String> expected = Set.copyOf(c.expectedSources());
            int firstHit = -1;
            double dcg = 0;
            for (int i = 0; i < rankedDocs.size(); i++) {
                if (expected.contains(rankedDocs.get(i))) {
                    if (firstHit < 0) {
                        firstHit = i;
                    }
                    dcg += 1 / log2(i + 2);
                }
            }
            double idcg = 0;
            for (int i = 0; i < Math.min(expected.size(), k); i++) {
                idcg += 1 / log2(i + 2);
            }
            if (firstHit >= 0) {
                recall += 1;
                mrr += 1.0 / (firstHit + 1);
            } else {
                misses.add(c.id());
            }
            ndcg += dcg / idcg;
        }
        return new RetrievalScores(mode, k, recall / n, mrr / n, ndcg / n, misses);
    }

    public AnswerScores answers(List<EvalCase> cases, RagService rag) {
        return answers(cases, rag, Graders.none());
    }

    public AnswerScores answers(List<EvalCase> cases, RagService rag, FaithfulnessJudge judge) {
        return answers(cases, rag, new Graders(judge, null, null));
    }

    public AnswerScores answers(List<EvalCase> cases, RagService rag, Graders graders) {
        FaithfulnessJudge judge = graders.judge();
        int answerable = 0, correct = 0, grounded = 0, unanswerable = 0, abstained = 0, falseAbstain = 0;
        double faithfulnessSum = 0;
        int judged = 0, consistencyPass = 0, consistencyRuns = 0, factPass = 0, factRuns = 0;
        List<String> failures = new ArrayList<>();
        for (EvalCase c : cases) {
            ChatAnswer a = rag.ask(new RagService.Question("eval", spaces(c), c.question(), null));
            boolean declined = a.sources().isEmpty() || (a.verification() != null && a.verification().refusal());
            if (c.answerable()) {
                answerable++;
                String text = a.answer().toLowerCase(Locale.ROOT);
                boolean hasFacts = c.mustContain().stream().allMatch(f -> text.contains(f.toLowerCase(Locale.ROOT)));
                if (hasFacts) {
                    correct++;
                } else {
                    failures.add(c.id() + " missing " + c.mustContain());
                }
                if (a.verification() != null && a.verification().grounded()) {
                    grounded++;
                }
                if (declined) {
                    falseAbstain++;
                } else {
                    if (judge != null) {
                        FaithfulnessJudge.Verdict v = judge.judge(a);
                        faithfulnessSum += v.score();
                        judged++;
                        v.claims().stream().filter(cl -> !cl.supported())
                                .forEach(cl -> failures.add(c.id() + " unsupported claim: " + cl.claim()));
                    }
                    if (graders.contextConsistency() != null || graders.factCheck() != null) {
                        var request = new org.springframework.ai.evaluation.EvaluationRequest(c.question(),
                                a.sources().stream().map(s -> new org.springframework.ai.document.Document(s.excerpt())).toList(),
                                a.answer());
                        if (graders.contextConsistency() != null) {
                            consistencyRuns++;
                            if (graders.contextConsistency().evaluate(request).isPass()) {
                                consistencyPass++;
                            } else {
                                failures.add(c.id() + " response not in line with context (Spring AI RelevancyEvaluator)");
                            }
                        }
                        if (graders.factCheck() != null) {
                            factRuns++;
                            if (graders.factCheck().evaluate(request).isPass()) {
                                factPass++;
                            } else {
                                failures.add(c.id() + " answer not supported (Spring AI FactCheckingEvaluator)");
                            }
                        }
                    }
                }
            } else {
                unanswerable++;
                if (declined || a.confidence().level() == ConfidenceLevel.NONE) {
                    abstained++;
                } else {
                    failures.add(c.id() + " answered an unanswerable question");
                }
            }
        }
        return new AnswerScores(answerable, ratio(correct, answerable), ratio(grounded, answerable), unanswerable,
                ratio(abstained, unanswerable), ratio(falseAbstain, answerable),
                judge == null || judged == 0 ? null : faithfulnessSum / judged,
                consistencyRuns == 0 ? null : (double) consistencyPass / consistencyRuns,
                factRuns == 0 ? null : (double) factPass / factRuns, failures);
    }

    public static String markdown(List<RetrievalScores> retrieval, AnswerScores answers, String setup) {
        StringBuilder sb = new StringBuilder("# Evaluation report\n\n").append(setup).append("\n\n");
        sb.append("## Retrieval (document level)\n\n| Mode | Recall@k | MRR | nDCG@k | Misses |\n|---|---|---|---|---|\n");
        for (RetrievalScores s : retrieval) {
            sb.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %s |%n", s.mode(), s.recallAtK(), s.mrr(),
                    s.ndcgAtK(), s.misses().isEmpty() ? "-" : String.join(", ", s.misses())));
        }
        if (answers != null) {
            sb.append(String.format(Locale.ROOT, """

                    ## End to end

                    | Metric | Value |
                    |---|---|
                    | Answerable questions | %d |
                    | Answer contains expected fact | %.3f |
                    | Grounded (verifier) | %.3f |
                    | False abstentions | %.3f |
                    | Unanswerable questions | %d |
                    | Correctly declined | %.3f |
                    | Faithfulness, claim by claim (FaithfulnessJudge) | %s |
                    | In line with context (Spring AI RelevancyEvaluator) | %s |
                    | Whole answer supported (Spring AI FactCheckingEvaluator) | %s |
                    """, answers.answerable(), answers.factAccuracy(), answers.groundedRate(),
                    answers.falseAbstentionRate(), answers.unanswerable(), answers.abstentionAccuracy(),
                    rate(answers.faithfulness()), rate(answers.contextConsistency()), rate(answers.factCheck())));
            if (!answers.failures().isEmpty()) {
                sb.append("\nFailures: ").append(String.join("; ", answers.failures())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String rate(Double d) {
        return d == null ? "not run (no judge model)" : String.format(Locale.ROOT, "%.3f", d);
    }

    private static List<String> distinctSources(List<RetrievedChunk> chunks) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        chunks.forEach(c -> seen.putIfAbsent(c.sourceName(), true));
        return new ArrayList<>(seen.keySet());
    }

    private static Set<SpaceId> spaces(EvalCase c) {
        Set<SpaceId> out = new TreeSet<>();
        c.spaces().forEach(s -> out.add(SpaceId.of(s)));
        return out;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static double ratio(int a, int b) {
        return b == 0 ? 1.0 : (double) a / b;
    }

    private static String sha(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

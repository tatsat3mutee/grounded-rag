package io.groundedrag.springai;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.retrieval.ConfidenceLevel;
import io.groundedrag.retrieval.ContextAssembler;
import io.groundedrag.retrieval.ContextSource;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.RetrievalResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

/**
 * This service's retrieval as a Spring AI {@link DocumentRetriever}, so any {@code ChatClient}
 * can use it through {@code RetrievalAugmentationAdvisor}: hybrid search, RRF, reranking,
 * confidence and parent expansion under the token budget, returned as Spring AI documents.
 *
 * <p>Tenant isolation survives the hop: the caller's spaces must be in the query context under
 * {@link #SPACES} (put there from the authenticated caller, never from user input). No spaces,
 * no documents: it fails closed.
 *
 * <p>When retrieval confidence is NONE it returns no documents, so the advisor's
 * {@code ContextualQueryAugmenter} makes the model decline instead of guessing.
 */
public class GroundedDocumentRetriever implements DocumentRetriever {

    /** Query-context key holding the caller's authorised spaces ({@code Collection<SpaceId>}). */
    public static final String SPACES = "groundedrag.spaces";

    private final Embedder embedder;
    private final HybridRetriever retriever;
    private final ContextAssembler assembler;

    public GroundedDocumentRetriever(Embedder embedder, HybridRetriever retriever, ContextAssembler assembler) {
        this.embedder = embedder;
        this.retriever = retriever;
        this.assembler = assembler;
    }

    @Override
    public List<Document> retrieve(Query query) {
        Set<SpaceId> spaces = spacesOf(query.context());
        if (spaces.isEmpty()) {
            return List.of();
        }
        RetrievalResult result = retriever.retrieve(query.text(), embedder.embed(query.text()), spaces);
        if (result.confidence() == ConfidenceLevel.NONE) {
            return List.of();
        }
        return assembler.assemble(result.chunks()).stream()
                .map(s -> toDocument(s, result))
                .toList();
    }

    static Set<SpaceId> spacesOf(Map<String, Object> context) {
        Set<SpaceId> out = new TreeSet<>();
        if (context.get(SPACES) instanceof Collection<?> raw) {
            for (Object o : raw) {
                if (o instanceof SpaceId id) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static Document toDocument(ContextSource s, RetrievalResult result) {
        RetrievedChunk c = s.chunk();
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", c.sourceName());
        meta.put("space", c.space().value());
        meta.put("number", s.number());
        meta.put("confidence", result.confidence().name());
        if (c.page() != null) {
            meta.put("page", c.page());
        }
        if (c.section() != null) {
            meta.put("section", c.section());
        }
        return Document.builder()
                .id(c.id().toString())
                .text(s.text())
                .metadata(meta)
                .score(c.rerankScore())
                .build();
    }
}
